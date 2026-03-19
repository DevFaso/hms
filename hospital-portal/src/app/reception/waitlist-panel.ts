import { Component, inject, signal, OnInit, computed, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ReceptionService, WaitlistEntryRequest, WaitlistEntryResponse } from './reception.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ReferralService, DepartmentMinimal } from '../services/referral.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { debounceTime, distinctUntilChanged, Subject, switchMap, catchError, of } from 'rxjs';

type WaitlistStatus = 'ALL' | 'WAITING' | 'OFFERED' | 'CLOSED';

@Component({
  selector: 'app-waitlist-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './waitlist-panel.html',
  styleUrl: './waitlist-panel.scss',
})
export class WaitlistPanelComponent implements OnInit {
  @Output() patientClicked = new EventEmitter<string>();

  private readonly receptionService = inject(ReceptionService);
  private readonly patientService = inject(PatientService);
  private readonly referralService = inject(ReferralService);
  private readonly staffService = inject(StaffService);
  private readonly roleCtx = inject(RoleContextService);
  private readonly toast = inject(ToastService);

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
        this.toast.error('Failed to load waitlist');
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
      this.toast.error('Patient and department are required');
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
        this.toast.success('Added to waitlist');
        this.showAddForm.set(false);
        this.saving.set(false);
        this.loadEntries();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to add to waitlist');
        this.saving.set(false);
      },
    });
  }

  offerSlot(id: string): void {
    this.receptionService.offerWaitlistSlot(id).subscribe({
      next: () => {
        this.toast.success('Slot offered — schedule an appointment for this patient');
        this.loadEntries();
      },
      error: () => this.toast.error('Failed to offer slot'),
    });
  }

  closeEntry(id: string): void {
    this.receptionService.closeWaitlistEntry(id).subscribe({
      next: () => {
        this.toast.success('Waitlist entry closed');
        this.loadEntries();
      },
      error: () => this.toast.error('Failed to close entry'),
    });
  }

  priorityClass(p: string): string {
    const map: Record<string, string> = {
      ROUTINE: 'priority-routine',
      URGENT: 'priority-urgent',
      STAT: 'priority-stat',
    };
    return map[p] ?? '';
  }

  statusClass(s: string): string {
    const map: Record<string, string> = {
      WAITING: 'status-waiting',
      OFFERED: 'status-offered',
      CLOSED: 'status-closed',
    };
    return map[s] ?? '';
  }
}
