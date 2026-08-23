import { Component, inject, signal, OnInit, computed, Output, EventEmitter } from '@angular/core';
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
import { StaffService, StaffResponse } from '../../services/staff.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { debounceTime, distinctUntilChanged, Subject, switchMap, catchError, of } from 'rxjs';

type WaitlistStatus = 'ALL' | 'WAITING' | 'OFFERED' | 'CLOSED';

@Component({
  selector: 'app-waitlist-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './waitlist-panel.component.html',
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
    const hospitalId = this.roleCtx.activeHospitalId ?? undefined;

    if (hospitalId) {
      this.referralService.getDepartmentsByHospital(hospitalId).subscribe({
        next: (list) => this.departments.set(list),
      });
    }

    this.staffService.list(hospitalId).subscribe({
      next: (list) => this.allStaff.set(list),
    });

    this.patientSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
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
    this.patientQuery.set(p.firstName + ' ' + p.lastName);
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
    this.priority.set('ROUTINE');
    this.reason.set('');
    this.showAddForm.set(true);
  }

  submitAdd(): void {
    if (!this.selectedPatient() || !this.selectedDeptId()) {
      this.toast.error(this.translate.instant('RECEPTION.PATIENT_DEPT_REQUIRED'));
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
          err?.error?.message ?? this.translate.instant('RECEPTION.ADD_WAITLIST_FAILED'),
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
        this.toast.error(err?.error?.message ?? this.translate.instant('RECEPTION.OFFER_FAILED'));
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
          err?.error?.message ?? this.translate.instant('RECEPTION.ACCEPT_OFFER_FAILED'),
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
          err?.error?.message ?? this.translate.instant('RECEPTION.DECLINE_OFFER_FAILED'),
        );
        this.actingOnEntryId.set(null);
      },
    });
  }

  offerExpired(entry: WaitlistEntryResponse): boolean {
    return !!entry.offerExpiresAt && new Date(entry.offerExpiresAt).getTime() < Date.now();
  }

  closeEntry(id: string): void {
    this.receptionService.closeWaitlistEntry(id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.ENTRY_CLOSED'));
        this.loadEntries();
      },
      error: () => this.toast.error(this.translate.instant('RECEPTION.CLOSE_FAILED')),
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
