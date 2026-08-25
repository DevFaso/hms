import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  BedBoard,
  BedBoardEntry,
  BedBoardService,
  BedOccupant,
  WardBoard,
} from '../services/bed-board.service';
import {
  IsolationPrecautionResponse,
  IsolationPrecautionType,
  IsolationService,
} from '../services/isolation.service';
import { TransferOrderResponse, TransferService } from '../services/transfer.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

/**
 * The ward board (Tier 2 items 30, 31 and 32).
 *
 * <p>The occupancy tiles on the admin dashboard answer "how many beds are
 * free". This answers "who is in bay 3, and can the next admission go beside
 * them" — which is the question a charge nurse is actually holding.
 *
 * <p>Isolation is rendered on the bed itself rather than behind a click. The
 * people who most need to see it are the ones who never open the chart: the
 * porter moving the bed and the clerk assigning the next admission.
 *
 * <p>Transfers live here too, because the destination picker is the board: you
 * choose where a patient goes by looking at which beds are free, not by typing
 * an identifier into a separate screen.
 */
@Component({
  selector: 'app-bed-board',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './bed-board.html',
  styleUrl: './bed-board.scss',
})
export class BedBoardComponent implements OnInit {
  private readonly boardService = inject(BedBoardService);
  private readonly isolation = inject(IsolationService);
  private readonly transfers = inject(TransferService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

  loading = signal(false);
  board = signal<BedBoard | null>(null);
  pendingTransfers = signal<TransferOrderResponse[]>([]);

  /** Filters. Empty ward = all wards. */
  wardFilter = signal<string>('');
  showOnlyIsolation = signal(false);
  showOnlyAvailable = signal(false);

  /** Precaution modal state. */
  showPrecautionModal = signal(false);
  precautionTarget = signal<BedOccupant | null>(null);
  precautionHistory = signal<IsolationPrecautionResponse[]>([]);
  savingPrecaution = signal(false);

  precautionForm: { precautionType: IsolationPrecautionType; reason: string; organism: string } = {
    precautionType: 'CONTACT',
    reason: '',
    organism: '',
  };

  readonly precautionTypes: IsolationPrecautionType[] = [
    'CONTACT',
    'DROPLET',
    'AIRBORNE',
    'PROTECTIVE',
  ];

  readonly canManagePrecautions = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);

  /** Same roles the backend allows to order and carry out a move. */
  readonly canTransfer = this.canManagePrecautions;

  /** Transfer modal state. */
  showTransferModal = signal(false);
  transferTarget = signal<BedOccupant | null>(null);
  savingTransfer = signal(false);

  transferForm: {
    toBedId: string;
    reason: string;
    isolationOverride: boolean;
    isolationOverrideReason: string;
  } = { toBedId: '', reason: '', isolationOverride: false, isolationOverrideReason: '' };

  /**
   * Free beds, as the destination picker. Built from the board itself: you
   * choose where a patient goes by seeing what is empty, not by typing an id.
   * Beds already spoken for by a pending transfer are excluded — the backend
   * refuses them, and offering them would be an error waiting to happen.
   */
  readonly destinationOptions = computed<{ bedId: string; label: string; isolation: boolean }[]>(
    () => {
      const current = this.board();
      if (!current) {
        return [];
      }
      const spokenFor = new Set(this.pendingTransfers().map((t) => t.toBedId));
      return current.wards.flatMap((w) =>
        w.rooms
          .flatMap((r) => r.beds)
          .filter((b) => b.status === 'AVAILABLE' && !spokenFor.has(b.bedId))
          .map((b) => ({
            bedId: b.bedId,
            label: `${w.wardName} — ${b.bedNumber}`,
            isolation: w.isolationCapable,
          })),
      );
    },
  );

  /**
   * True when the chosen destination cannot contain the patient's airborne
   * precaution. Drives the override prompt BEFORE submitting, so the refusal
   * is not the first the operator hears of it.
   */
  readonly destinationNeedsOverride = computed<boolean>(() => {
    const target = this.transferTarget();
    if (!target?.requiresIsolationWard || !this.transferForm.toBedId) {
      return false;
    }
    const chosen = this.destinationOptions().find((o) => o.bedId === this.transferForm.toBedId);
    return !!chosen && !chosen.isolation;
  });

  /** Wards after the filters, so the template stays declarative. */
  readonly visibleWards = computed<WardBoard[]>(() => {
    const current = this.board();
    if (!current) {
      return [];
    }
    const wardId = this.wardFilter();
    const isolationOnly = this.showOnlyIsolation();
    const availableOnly = this.showOnlyAvailable();

    return current.wards
      .filter((w) => !wardId || w.wardId === wardId)
      .map((w) => ({
        ...w,
        rooms: w.rooms
          .map((room) => ({
            ...room,
            beds: room.beds.filter((bed) => this.matches(bed, isolationOnly, availableOnly)),
          }))
          .filter((room) => room.beds.length > 0),
      }))
      .filter((w) => w.rooms.length > 0);
  });

  /** Every mismatch on the board — an airborne case in a ward that cannot hold it. */
  readonly mismatches = computed<BedOccupant[]>(() => {
    const current = this.board();
    if (!current) {
      return [];
    }
    return current.wards
      .flatMap((w) => w.rooms)
      .flatMap((r) => r.beds)
      .map((b) => b.occupant)
      .filter((o): o is BedOccupant => !!o && o.isolationMismatch);
  });

  ngOnInit(): void {
    this.loadBoard();
    this.loadPendingTransfers();
  }

  loadPendingTransfers(): void {
    this.transfers.getPending().subscribe({
      next: (list) => this.pendingTransfers.set(list),
      error: () => this.toast.error(this.translate.instant('BED_BOARD.TRANSFERS_LOAD_ERROR')),
    });
  }

  loadBoard(): void {
    this.loading.set(true);
    this.boardService.getBoard().subscribe({
      next: (board) => {
        this.board.set(board);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('BED_BOARD.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  private matches(bed: BedBoardEntry, isolationOnly: boolean, availableOnly: boolean): boolean {
    if (availableOnly && bed.status !== 'AVAILABLE') {
      return false;
    }
    if (isolationOnly && (!bed.occupant || bed.occupant.isolationPrecautions.length === 0)) {
      return false;
    }
    return true;
  }

  bedStatusClass(bed: BedBoardEntry): string {
    if (bed.occupant?.isolationMismatch) {
      return 'bed-tile bed-mismatch';
    }
    if (bed.occupant && bed.occupant.isolationPrecautions.length > 0) {
      return 'bed-tile bed-isolation';
    }
    return `bed-tile bed-${bed.status.toLowerCase().replace(/_/g, '-')}`;
  }

  precautionClass(type: IsolationPrecautionType): string {
    return `chip chip-${type.toLowerCase()}`;
  }

  /** A bed marked OCCUPIED that no admission points at. */
  get hasOrphanedBeds(): boolean {
    return (this.board()?.census.orphanedOccupiedBeds ?? 0) > 0;
  }

  // ── Precautions ─────────────────────────────────────────────────────

  openPrecautions(occupant: BedOccupant): void {
    this.precautionTarget.set(occupant);
    this.precautionForm = { precautionType: 'CONTACT', reason: '', organism: '' };
    this.precautionHistory.set([]);
    this.showPrecautionModal.set(true);

    this.isolation.getActiveForPatient(occupant.patientId).subscribe({
      next: (list) => this.precautionHistory.set(list),
      error: () => this.toast.error(this.translate.instant('BED_BOARD.PRECAUTIONS_LOAD_ERROR')),
    });
  }

  closePrecautions(): void {
    this.showPrecautionModal.set(false);
    this.precautionTarget.set(null);
  }

  submitPrecaution(): void {
    const target = this.precautionTarget();
    if (!target || !this.precautionForm.reason.trim()) {
      return;
    }
    this.savingPrecaution.set(true);
    this.isolation
      .startPrecaution({
        patientId: target.patientId,
        admissionId: target.admissionId,
        precautionType: this.precautionForm.precautionType,
        reason: this.precautionForm.reason.trim(),
        suspectedOrganism: this.precautionForm.organism.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('BED_BOARD.PRECAUTION_STARTED'));
          this.savingPrecaution.set(false);
          this.closePrecautions();
          this.loadBoard();
        },
        error: () => {
          this.toast.error(this.translate.instant('BED_BOARD.PRECAUTION_ERROR'));
          this.savingPrecaution.set(false);
        },
      });
  }

  discontinue(precaution: IsolationPrecautionResponse): void {
    const reason = window.prompt(this.translate.instant('BED_BOARD.DISCONTINUE_PROMPT'));
    if (!reason || !reason.trim()) {
      return;
    }
    this.isolation
      .discontinuePrecaution(precaution.id, { discontinuationReason: reason.trim() })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('BED_BOARD.PRECAUTION_LIFTED'));
          const target = this.precautionTarget();
          if (target) {
            this.openPrecautions(target);
          }
          this.loadBoard();
        },
        error: () => this.toast.error(this.translate.instant('BED_BOARD.DISCONTINUE_ERROR')),
      });
  }

  // ── Transfers ───────────────────────────────────────────────────────

  openTransfer(occupant: BedOccupant): void {
    this.transferTarget.set(occupant);
    this.transferForm = {
      toBedId: '',
      reason: '',
      isolationOverride: false,
      isolationOverrideReason: '',
    };
    this.showTransferModal.set(true);
  }

  closeTransfer(): void {
    this.showTransferModal.set(false);
    this.transferTarget.set(null);
  }

  /** Blocked until the override is acknowledged with a reason. */
  get transferSubmittable(): boolean {
    if (!this.transferForm.toBedId || !this.transferForm.reason.trim()) {
      return false;
    }
    if (this.destinationNeedsOverride()) {
      return (
        this.transferForm.isolationOverride && !!this.transferForm.isolationOverrideReason.trim()
      );
    }
    return true;
  }

  submitTransfer(): void {
    const target = this.transferTarget();
    if (!target || !this.transferSubmittable) {
      return;
    }
    const override = this.destinationNeedsOverride();
    this.savingTransfer.set(true);
    this.transfers
      .requestTransfer({
        admissionId: target.admissionId,
        toBedId: this.transferForm.toBedId,
        reason: this.transferForm.reason.trim(),
        isolationOverride: override || undefined,
        isolationOverrideReason: override
          ? this.transferForm.isolationOverrideReason.trim()
          : undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('BED_BOARD.TRANSFER_ORDERED'));
          this.savingTransfer.set(false);
          this.closeTransfer();
          this.refreshAll();
        },
        error: () => {
          this.toast.error(this.translate.instant('BED_BOARD.TRANSFER_ERROR'));
          this.savingTransfer.set(false);
        },
      });
  }

  completeTransfer(order: TransferOrderResponse): void {
    this.transfers.completeTransfer(order.id, {}).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BED_BOARD.TRANSFER_COMPLETED'));
        this.refreshAll();
      },
      error: () => this.toast.error(this.translate.instant('BED_BOARD.TRANSFER_COMPLETE_ERROR')),
    });
  }

  cancelTransfer(order: TransferOrderResponse): void {
    const reason = window.prompt(this.translate.instant('BED_BOARD.TRANSFER_CANCEL_PROMPT'));
    if (!reason || !reason.trim()) {
      return;
    }
    this.transfers.cancelTransfer(order.id, { cancellationReason: reason.trim() }).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BED_BOARD.TRANSFER_CANCELLED'));
        this.refreshAll();
      },
      error: () => this.toast.error(this.translate.instant('BED_BOARD.TRANSFER_CANCEL_ERROR')),
    });
  }

  /** A transfer changes both the board and the worklist, so reload both. */
  private refreshAll(): void {
    this.loadBoard();
    this.loadPendingTransfers();
  }
}
