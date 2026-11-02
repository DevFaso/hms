import { ReasonDialogComponent } from '../../shared/reason-dialog/reason-dialog.component';
import { focusWhenRendered } from '../../shared/a11y/focus-when-rendered';
import { refusalReason } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { PersonNamePipe, personName } from '../../shared/pipes/person-name.pipe';
import { DialogFocusDirective } from '../../shared/a11y/dialog-focus.directive';
import {
  Component,
  inject,
  signal,
  OnInit,
  computed,
  Output,
  EventEmitter,
  ChangeDetectionStrategy,
  ElementRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  ReceptionService,
  WaitlistEntryRequest,
  WaitlistEntryResponse,
} from '../reception.service';
import {
  SlotInventoryService,
  AppointmentSlotResponse,
} from '../../services/slot-inventory.service';
import { PatientService, PatientResponse } from '../../services/patient.service';
import { ReferralService, DepartmentMinimal } from '../../services/referral.service';
import { StaffService, isClinicalProvider, StaffResponse } from '../../services/staff.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { debounceTime, Subject, switchMap, catchError, of } from 'rxjs';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { localDateString, serverInstant } from '../../shared/date-utils';
import { endsBeforeStart, isBeforeToday } from '../../shared/forms/date-rules';
import { PatientSublinePipe } from '../../shared/patient-picker/patient-subline';

import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
type WaitlistStatus = 'ALL' | 'WAITING' | 'OFFERED' | 'CLOSED';

/** Every field the add form may refuse, so a corrected one loses its mark. */
const WAITLIST_FIELDS = ['wl-patient', 'wl-dept', 'wl-to'];

@Component({
  selector: 'app-waitlist-panel',
  standalone: true,
  imports: [
    ReasonDialogComponent,
    SubmitGuardDirective,
    PersonNamePipe,
    DialogFocusDirective,
    PatientSublinePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
  ],
  templateUrl: './waitlist-panel.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './waitlist-panel.component.scss',
})
export class WaitlistPanelComponent implements OnInit {
  @Output() patientClicked = new EventEmitter<string>();

  private readonly receptionService = inject(ReceptionService);
  private readonly slotInventoryService = inject(SlotInventoryService);
  private readonly patientService = inject(PatientService);
  private readonly referralService = inject(ReferralService);
  private readonly staffService = inject(StaffService);
  private readonly roleCtx = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /* ── List state ─────────── */
  entries = signal<WaitlistEntryResponse[]>([]);
  loading = signal(false);
  statusFilter = signal<WaitlistStatus>('WAITING');
  readonly statusOptions: WaitlistStatus[] = ['ALL', 'WAITING', 'OFFERED', 'CLOSED'];

  /* ── Add-to-waitlist modal ─ */
  showAddForm = signal(false);
  saving = signal(false);

  /* ── Patient search ──────── */
  patientQuery = signal('');
  patientResults = signal<PatientResponse[]>([]);
  selectedPatient = signal<PatientResponse | null>(null);
  private patientSearch$ = new Subject<string>();

  /* ── Departments & Staff ─── */
  departments = signal<DepartmentMinimal[]>([]);
  allStaff = signal<StaffResponse[]>([]);
  filteredStaff = computed(() => {
    const q = this.staffQuery().toLowerCase();
    return q ? this.allStaff().filter((s) => s.name.toLowerCase().includes(q)) : this.allStaff();
  });
  staffQuery = signal('');
  selectedStaff = signal<StaffResponse | null>(null);

  /* ── Form fields ─────────── */
  selectedDeptId = signal('');
  dateFrom = signal('');
  dateTo = signal('');
  /**
   * QA D1215 (round 23, portal half): the server refuses a window that ends
   * before today (`waitlist.dateRange.past`); the picker never offers one.
   */
  readonly today = signal(localDateString());

  /** The earliest end the picker offers: the start, and never before today. */
  dateToMin(): string {
    const from = this.dateFrom();
    return from && from > this.today() ? from : this.today();
  }
  priority = signal<'ROUTINE' | 'URGENT' | 'STAT'>('ROUTINE');
  reason = signal('');
  readonly priorities = ['ROUTINE', 'URGENT', 'STAT'] as const;

  /* ── Offer-a-slot modal (P3 #22) ─ */
  offerEntry = signal<WaitlistEntryResponse | null>(null);
  offerSlots = signal<AppointmentSlotResponse[]>([]);
  offerSlotsLoading = signal(false);
  selectedSlotId = signal('');
  offerHours = signal(48);
  offering = signal(false);
  actingOnEntryId = signal<string | null>(null);

  ngOnInit(): void {
    // QA D912 (#967 review): the request scope, so a super-admin scoped to
    // another hospital searches its patients (and MRNs), not their home one.
    const hospitalId = this.roleCtx.effectiveHospitalIdForRequest() ?? undefined;

    if (hospitalId) {
      this.referralService.getDepartmentsByHospital(hospitalId).subscribe({
        next: (list) => this.departments.set(list),
      });
    }

    this.staffService.list(hospitalId).subscribe({
      next: (list) => this.allStaff.set(list.filter(isClinicalProvider)),
    });

    this.patientSearch$
      .pipe(
        debounceTime(300),
        switchMap((q) => {
          if (!q || q.length < 2) return of([]);
          return this.patientService.list(hospitalId, q).pipe(catchError(() => of([])));
        }),
      )
      .subscribe((results) => this.patientResults.set(results as PatientResponse[]));

    this.loadEntries();
  }

  loadEntries(): void {
    this.loading.set(true);
    const status = this.statusFilter() === 'ALL' ? undefined : this.statusFilter();
    this.receptionService.getWaitlist({ status }).subscribe({
      next: (list) => {
        this.entries.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('RECEPTION.LOAD_WAITLIST_FAILED'));
        this.loading.set(false);
      },
    });
  }

  onPatientQueryChange(value: string): void {
    this.patientQuery.set(value);
    this.patientSearch$.next(value);
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.patientQuery.set(personName(p));
    this.patientResults.set([]);
  }

  openAddForm(): void {
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.selectedDeptId.set('');
    this.selectedStaff.set(null);
    this.staffQuery.set('');
    this.dateFrom.set('');
    this.dateTo.set('');
    this.today.set(localDateString());
    this.priority.set('ROUTINE');
    this.reason.set('');
    this.showAddForm.set(true);
  }

  submitAdd(): void {
    if (!this.selectedPatient() || !this.selectedDeptId()) {
      // QA D1072: the missing field is marked and focused.
      refuseFields(
        this.host.nativeElement,
        [
          !this.selectedPatient() ? 'wl-patient' : null,
          !this.selectedDeptId() ? 'wl-dept' : null,
        ].filter((id): id is string => id !== null),
        WAITLIST_FIELDS,
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      this.toast.error(this.translate.instant('RECEPTION.PATIENT_DEPT_REQUIRED'));
      return;
    }
    // QA D1215 (round 23): a window that ends before it starts can never be
    // offered a slot. The picker bounds each end by the other; a typed date
    // that slips past them is refused here, on the field, with the reason.
    if (endsBeforeStart(this.dateFrom(), this.dateTo())) {
      const message = this.translate.instant('DATE_RULES.END_BEFORE_START');
      refuseFields(this.host.nativeElement, ['wl-to'], WAITLIST_FIELDS, message);
      this.toast.error(message);
      return;
    }
    // QA D1215 (round 23): a window that is already over can never be offered a slot.
    this.today.set(localDateString());
    if (isBeforeToday(this.dateTo(), this.today())) {
      const message = this.translate.instant('DATE_RULES.NOT_IN_PAST');
      refuseFields(this.host.nativeElement, ['wl-to'], WAITLIST_FIELDS, message);
      this.toast.error(message);
      return;
    }
    const req: WaitlistEntryRequest = {
      patientId: this.selectedPatient()!.id,
      departmentId: this.selectedDeptId(),
      preferredProviderId: this.selectedStaff()?.id ?? null,
      requestedDateFrom: this.dateFrom() || null,
      requestedDateTo: this.dateTo() || null,
      priority: this.priority(),
      reason: this.reason() || null,
    };
    this.saving.set(true);
    this.receptionService.addToWaitlist(req).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.ADDED_TO_WAITLIST'));
        this.showAddForm.set(false);
        this.saving.set(false);
        this.loadEntries();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('RECEPTION.ADD_WAITLIST_FAILED'),
        );
        this.saving.set(false);
      },
    });
  }

  openOfferModal(entry: WaitlistEntryResponse): void {
    this.offerEntry.set(entry);
    this.offerSlots.set([]);
    this.selectedSlotId.set('');
    this.offerHours.set(48);
    this.offerSlotsLoading.set(true);
    this.slotInventoryService
      .searchOpen({
        departmentId: entry.departmentId,
        staffId: entry.preferredProviderId ?? undefined,
        from: entry.requestedDateFrom ?? undefined,
        to: entry.requestedDateTo ?? undefined,
        limit: 50,
      })
      .subscribe({
        next: (slots) => {
          this.offerSlots.set(slots);
          this.offerSlotsLoading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('RECEPTION.LOAD_SLOTS_FAILED'));
          this.offerSlotsLoading.set(false);
        },
      });
  }

  submitOffer(): void {
    const entry = this.offerEntry();
    const slotId = this.selectedSlotId();
    if (!entry || !slotId || this.offering()) {
      if (!slotId) this.toast.error(this.translate.instant('RECEPTION.SELECT_SLOT_FIRST'));
      return;
    }
    this.offering.set(true);
    this.receptionService.offerWaitlistSlot(entry.id, slotId, this.offerHours() || 48).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.SLOT_OFFERED'));
        this.offering.set(false);
        this.offerEntry.set(null);
        this.loadEntries();
      },
      error: (err) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('RECEPTION.OFFER_FAILED'));
        this.offering.set(false);
      },
    });
  }

  acceptOffer(entry: WaitlistEntryResponse): void {
    if (this.actingOnEntryId()) return;
    this.actingOnEntryId.set(entry.id);
    this.receptionService.acceptWaitlistOffer(entry.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.OFFER_ACCEPTED'));
        this.actingOnEntryId.set(null);
        this.loadEntries();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('RECEPTION.ACCEPT_OFFER_FAILED'),
        );
        this.actingOnEntryId.set(null);
      },
    });
  }

  declineOffer(entry: WaitlistEntryResponse): void {
    if (this.actingOnEntryId()) return;
    this.actingOnEntryId.set(entry.id);
    this.receptionService.declineWaitlistOffer(entry.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.OFFER_DECLINED'));
        this.actingOnEntryId.set(null);
        this.loadEntries();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('RECEPTION.DECLINE_OFFER_FAILED'),
        );
        this.actingOnEntryId.set(null);
      },
    });
  }

  offerExpired(entry: WaitlistEntryResponse): boolean {
    return !!entry.offerExpiresAt && serverInstant(entry.offerExpiresAt).getTime() < Date.now();
  }

  /** QA D1157: "Fermer" asks why before anything is sent. */
  readonly closing = signal<WaitlistEntryResponse | null>(null);
  readonly closeBusy = signal(false);

  askCloseEntry(entry: WaitlistEntryResponse): void {
    this.closing.set(entry);
  }

  closeEntry(id: string, reason: string): void {
    if (this.closeBusy()) return;
    this.closeBusy.set(true);
    this.receptionService.closeWaitlistEntry(id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.ENTRY_CLOSED'));
        this.closeBusy.set(false);
        this.closing.set(null);
        // The row leaves the list: the keyboard lands on the list's filter, not the page body.
        focusWhenRendered(this.host.nativeElement, '.filter-btn.active');
        this.loadEntries();
      },
      error: (err: unknown) => {
        this.closeBusy.set(false);
        this.toast.error(refusalReason(err) ?? this.translate.instant('RECEPTION.CLOSE_FAILED'));
      },
    });
  }

  filterLabel(status: WaitlistStatus): string {
    if (status === 'ALL') return this.translate.instant('COMMON.ALL');
    return this.translate.instant('RECEPTION.' + status);
  }

  priorityClass(priority: string): string {
    return 'priority-' + priority.toLowerCase();
  }

  statusClass(status: string): string {
    return 'status-' + status.toLowerCase();
  }
}
