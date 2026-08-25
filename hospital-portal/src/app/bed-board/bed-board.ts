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
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

/**
 * The ward board (Tier 2 items 31 and 32).
 *
 * <p>The occupancy tiles on the admin dashboard answer "how many beds are
 * free". This answers "who is in bay 3, and can the next admission go beside
 * them" — which is the question a charge nurse is actually holding.
 *
 * <p>Isolation is rendered on the bed itself rather than behind a click. The
 * people who most need to see it are the ones who never open the chart: the
 * porter moving the bed and the clerk assigning the next admission.
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
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

  loading = signal(false);
  board = signal<BedBoard | null>(null);

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
}
