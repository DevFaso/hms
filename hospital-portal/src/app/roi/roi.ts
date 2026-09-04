import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';

import {
  RoiCreateRequest,
  RoiRequest,
  RoiRequestStatus,
  RoiRequesterType,
  RoiService,
} from '../services/roi.service';
import { PatientResponse } from '../services/patient.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

type DecisionKind = 'fulfil' | 'deny' | 'cancel';

/**
 * Release of information (Tier 2 item 39b) — the records-desk triage
 * surface. One status at a time, oldest first (the queue order); intake on
 * the shared patient picker; decisions gated to the roles that answer for
 * a release, mirroring RoiWorklistController exactly. Every request here
 * is hospital-pinned server-side, so a super-admin in global view defers
 * until the scope chip pins one (the #549 lesson).
 */
@Component({
  selector: 'app-roi',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule,
    TranslateModule,
    PatientPickerComponent,
    HospitalScopeChipComponent,
  ],
  templateUrl: './roi.html',
  styleUrl: './roi.scss',
})
export class RoiComponent implements OnInit {
  private readonly roiService = inject(RoiService);
  private readonly roleCtx = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly statuses: RoiRequestStatus[] = ['PENDING', 'FULFILLED', 'DENIED', 'CANCELLED'];
  readonly requesterTypes: RoiRequesterType[] = ['THIRD_PARTY', 'PATIENT'];

  /** One server page; the server caps at 200 anyway. */
  private static readonly PAGE_SIZE = 200;

  activeStatus = signal<RoiRequestStatus>('PENDING');
  rows = signal<RoiRequest[]>([]);
  total = signal(0);
  loading = signal(false);
  loadFailed = signal(false);

  /* ── intake modal ── */
  showIntake = signal(false);
  saving = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  formRequesterType = signal<RoiRequesterType>('THIRD_PARTY');
  formRequesterName = signal('');
  formRequesterContact = signal('');
  formPurpose = signal('');
  formScope = signal('');
  formRequestedOn = signal('');

  /* ── decision modal ── */
  deciding = signal<{ row: RoiRequest; kind: DecisionKind } | null>(null);
  decisionNote = signal('');

  /* ── dialog focus management (registries pattern) ── */
  private readonly intakeDialog = viewChild<ElementRef<HTMLElement>>('intakeDialog');
  private readonly decisionDialog = viewChild<ElementRef<HTMLElement>>('decisionDialog');
  private dialogOpener: HTMLElement | null = null;

  readonly pickerHospitalId = computed(() => this.roleCtx.effectiveHospitalIdForRequest());
  readonly scopeReady = computed(() => this.pickerHospitalId() != null);
  /** Mirrors RoiWorklistController.DECISION_ROLES exactly. */
  readonly canDecide = computed(() =>
    this.roleCtx.hasAnyActiveRole(['ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN']),
  );

  readonly truncated = computed(() => this.total() > this.rows().length);

  ngOnInit(): void {
    this.reloadForScope();
  }

  onScopeChange(_hospitalId: string | null): void {
    this.reloadForScope();
  }

  private reloadForScope(): void {
    this.rows.set([]);
    this.total.set(0);
    this.loadFailed.set(false);
    if (!this.scopeReady()) {
      return;
    }
    this.load();
  }

  setStatus(status: RoiRequestStatus): void {
    this.activeStatus.set(status);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.roiService.worklist(this.activeStatus(), 0, RoiComponent.PAGE_SIZE).subscribe({
      next: (page) => {
        this.loading.set(false);
        this.rows.set(page.content ?? []);
        this.total.set(page.totalElements ?? 0);
        this.loadFailed.set(false);
      },
      // Unavailable, never "no requests": an outage must not read as an
      // empty queue (house stance).
      error: () => {
        this.loading.set(false);
        this.loadFailed.set(true);
      },
    });
  }

  /* ── intake ── */

  openIntake(event?: Event): void {
    this.dialogOpener = (event?.currentTarget as HTMLElement) ?? null;
    this.showIntake.set(true);
    this.selectedPatient.set(null);
    this.formRequesterType.set('THIRD_PARTY');
    this.formRequesterName.set('');
    this.formRequesterContact.set('');
    this.formPurpose.set('');
    this.formScope.set('');
    this.formRequestedOn.set('');
    this.focusDialogSoon(() => this.intakeDialog()?.nativeElement);
  }

  closeIntake(): void {
    this.showIntake.set(false);
    this.restoreOpenerFocus();
  }

  onPatientSelected(patient: PatientResponse | null): void {
    this.selectedPatient.set(patient);
  }

  intakeValid(): boolean {
    return !!this.selectedPatient() && !!this.formPurpose().trim() && !!this.formScope().trim();
  }

  submitIntake(): void {
    const patient = this.selectedPatient();
    if (!patient || !this.intakeValid() || this.saving()) return;
    this.saving.set(true);
    const req: RoiCreateRequest = {
      requesterType: this.formRequesterType(),
      requesterName: this.formRequesterName().trim() || undefined,
      requesterContact: this.formRequesterContact().trim() || undefined,
      purpose: this.formPurpose().trim(),
      scopeDescription: this.formScope().trim(),
      requestedOn: this.formRequestedOn() || undefined,
    };
    this.roiService.create(patient.id, req).subscribe({
      next: () => {
        this.saving.set(false);
        this.closeIntake();
        this.toast.success(this.translate.instant('ROI.LOGGED'));
        this.setStatus('PENDING');
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('ROI.ACTION_FAILED'));
      },
    });
  }

  /* ── decisions ── */

  openDecision(row: RoiRequest, kind: DecisionKind, event?: Event): void {
    this.dialogOpener = (event?.currentTarget as HTMLElement) ?? null;
    this.deciding.set({ row, kind });
    this.decisionNote.set('');
    this.focusDialogSoon(() => this.decisionDialog()?.nativeElement);
  }

  closeDecision(): void {
    this.deciding.set(null);
    this.restoreOpenerFocus();
  }

  decisionValid(): boolean {
    const d = this.deciding();
    if (!d) return false;
    // The deny reason is the outcome the requester is told.
    return d.kind !== 'deny' || !!this.decisionNote().trim();
  }

  submitDecision(): void {
    const d = this.deciding();
    if (!d || !this.decisionValid() || this.saving()) return;
    this.saving.set(true);
    const note = this.decisionNote().trim() || undefined;
    const call =
      d.kind === 'fulfil'
        ? this.roiService.fulfil(d.row.id, note)
        : d.kind === 'deny'
          ? this.roiService.deny(d.row.id, note as string)
          : this.roiService.cancel(d.row.id, note);
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.closeDecision();
        this.toast.success(this.translate.instant('ROI.DECIDED_' + d.kind.toUpperCase()));
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('ROI.ACTION_FAILED'));
      },
    });
  }

  requesterKey(type: RoiRequesterType): string {
    return type === 'PATIENT' ? 'ROI.REQUESTER_PATIENT' : 'ROI.REQUESTER_THIRD_PARTY';
  }

  statusKey(status: RoiRequestStatus): string {
    return 'ROI.STATUS_' + status;
  }

  /* ── Dialog focus: move in on open, cycle on Tab, restore on close ── */

  trapTab(event: KeyboardEvent, dialog: HTMLElement): void {
    const focusables = Array.from(
      dialog.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((el) => !el.hasAttribute('disabled'));
    if (focusables.length === 0) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusDialogSoon(resolve: () => HTMLElement | undefined): void {
    setTimeout(() => resolve()?.focus(), 0);
  }

  private restoreOpenerFocus(): void {
    this.dialogOpener?.focus();
    this.dialogOpener = null;
  }
}
