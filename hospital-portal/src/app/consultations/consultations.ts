import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  ConsultationService,
  ConsultationResponse,
  ConsultationRequest,
  ConsultationStats,
  ConsultationType,
  ConsultationUrgency,
} from '../services/consultation.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

@Component({
  selector: 'app-consultations',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './consultations.html',
  styleUrl: './consultations.scss',
})
export class ConsultationsComponent implements OnInit {
  private readonly consultService = inject(ConsultationService);
  private readonly hospitalService = inject(HospitalService);
  private readonly patientService = inject(PatientService);
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);

  consultations = signal<ConsultationResponse[]>([]);
  filtered = signal<ConsultationResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'pending' | 'active' | 'completed' | 'mine' | 'overdue'>('all');
  selectedConsult = signal<ConsultationResponse | null>(null);

  stats = signal<ConsultationStats | null>(null);
  statsLoading = signal(false);
  showStats = signal(false);

  hospitals = signal<HospitalResponse[]>([]);

  // Patient picker
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

  /* ── CRUD signals ── */
  showModal = signal(false);
  saving = signal(false);
  form: ConsultationRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingItem = signal<ConsultationResponse | null>(null);
  deleting = signal(false);
  cancelReason = signal('');

  /* ── Assign modal ── */
  showAssignModal = signal(false);
  assigningItem = signal<ConsultationResponse | null>(null);
  assignStaff = signal<StaffResponse[]>([]);
  assignStaffLoading = signal(false);
  assignConsultantId = signal('');
  assignNote = signal('');
  assigning = signal(false);
  isReassign = signal(false);
  assignSpecialtyFilter = signal('');

  /* ── Schedule modal ── */
  showScheduleModal = signal(false);
  schedulingItem = signal<ConsultationResponse | null>(null);
  scheduling = signal(false);
  scheduleForm = { scheduledAt: '', scheduleNote: '' };

  /* ── Complete modal ── */
  showCompleteModal = signal(false);
  completingItem = signal<ConsultationResponse | null>(null);
  completing = signal(false);
  completeForm = {
    recommendations: '',
    consultantNote: '',
    followUpRequired: false,
    followUpInstructions: '',
  };

  /* ── Decline modal ── */
  showDeclineModal = signal(false);
  decliningItem = signal<ConsultationResponse | null>(null);
  declining = signal(false);
  declineReasonValue = signal('');

  consultationTypes: ConsultationType[] = [
    'OUTPATIENT_CONSULT',
    'INPATIENT_CONSULT',
    'FOLLOW_UP_CONSULT',
    'CURBSIDE_CONSULT',
    'EMERGENCY_CONSULT',
  ];
  urgencies: ConsultationUrgency[] = ['ROUTINE', 'URGENT', 'EMERGENCY', 'STAT'];

  readonly typeLabel: Record<string, string> = {
    OUTPATIENT_CONSULT: 'Outpatient',
    INPATIENT_CONSULT: 'Inpatient',
    FOLLOW_UP_CONSULT: 'Follow-up',
    CURBSIDE_CONSULT: 'Curbside',
    EMERGENCY_CONSULT: 'Emergency',
  };

  ngOnInit(): void {
    this.load();
    this.loadAssignedHospitals();
    this.initPatientSearch();
    this.loadStats();
  }

  emptyForm(): ConsultationRequest {
    return {
      patientId: '',
      hospitalId: '',
      consultationType: 'OUTPATIENT_CONSULT' as ConsultationType,
      specialtyRequested: '',
      reasonForConsult: '',
      urgency: 'ROUTINE' as ConsultationUrgency,
    };
  }

  /** ── TENANT ISOLATION: only SUPER_ADMIN may choose from all hospitals ── */
  private loadAssignedHospitals(): void {
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe((h) => this.hospitals.set(h ?? []));
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.hospitals.set([h]);
          this.form.hospitalId = h.id;
        },
      });
    }
  }

  get lockedHospitalName(): string {
    const h = this.hospitals();
    return h.length === 1 ? h[0].name : 'No hospital assigned';
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          // ── TENANT ISOLATION: scope patient search to active hospital ──
          const hid = this.roleContext.activeHospitalId ?? undefined;
          return this.patientService.list(hid, q);
        }),
      )
      .subscribe({
        next: (list) => {
          this.patientSuggestions.set(list.slice(0, 8));
          this.patientDropdownOpen.set(list.length > 0);
          this.patientSearchLoading.set(false);
        },
        error: () => this.patientSearchLoading.set(false),
      });
  }

  onPatientQueryChange(q: string): void {
    this.patientQuery.set(q);
    if (q.length >= 2) this.patientSearch$.next(q);
    else {
      this.patientSuggestions.set([]);
      this.patientDropdownOpen.set(false);
    }
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.form.patientId = p.id;
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    this.form.patientId = '';
    this.patientQuery.set('');
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    // Re-apply locked hospital after emptyForm() reset
    if (this.hospitalLocked) {
      const h = this.hospitals();
      if (h.length === 1) this.form.hospitalId = h[0].id;
    }
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    this.consultService.create(this.form).subscribe({
      next: () => {
        this.toast.success('Consultation created');
        this.closeModal();
        this.saving.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Save failed');
        this.saving.set(false);
      },
    });
  }

  confirmCancel(c: ConsultationResponse): void {
    this.deletingItem.set(c);
    this.cancelReason.set('');
    this.showDeleteConfirm.set(true);
  }
  cancelDeleteAction(): void {
    this.showDeleteConfirm.set(false);
    this.deletingItem.set(null);
  }
  executeCancel(): void {
    const reason = this.cancelReason().trim();
    if (!reason) {
      this.toast.error('Please enter a cancellation reason');
      return;
    }
    this.deleting.set(true);
    this.consultService.cancel(this.deletingItem()!.id, reason).subscribe({
      next: () => {
        this.toast.success('Consultation cancelled');
        this.cancelDeleteAction();
        this.deleting.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Cancel failed');
        this.deleting.set(false);
      },
    });
  }

  load(): void {
    this.loading.set(true);
    const tab = this.activeTab();
    const source$ =
      tab === 'mine'
        ? this.consultService.getMine()
        : tab === 'overdue'
          ? this.consultService.getOverdue(this.roleContext.activeHospitalId ?? undefined)
          : this.consultService.getAll();

    source$.subscribe({
      next: (list) => {
        this.consultations.set(Array.isArray(list) ? list : []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load consultations');
        this.loading.set(false);
      },
    });
  }

  loadStats(): void {
    this.statsLoading.set(true);
    this.consultService.getStats(this.roleContext.activeHospitalId ?? undefined).subscribe({
      next: (s) => {
        this.stats.set(s);
        this.statsLoading.set(false);
      },
      error: () => this.statsLoading.set(false),
    });
  }

  setTab(tab: 'all' | 'pending' | 'active' | 'completed' | 'mine' | 'overdue'): void {
    this.activeTab.set(tab);
    this.load();
  }

  applyFilter(): void {
    let list = this.consultations();
    const tab = this.activeTab();
    if (tab === 'pending') list = list.filter((c) => ['REQUESTED'].includes(c.status));
    else if (tab === 'active')
      list = list.filter((c) =>
        ['ASSIGNED', 'ACKNOWLEDGED', 'SCHEDULED', 'IN_PROGRESS'].includes(c.status),
      );
    else if (tab === 'completed')
      list = list.filter((c) => ['COMPLETED', 'CANCELLED', 'DECLINED'].includes(c.status));
    // 'mine' and 'overdue' come pre-filtered from the API
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (c) =>
          (c.patientName ?? '').toLowerCase().includes(term) ||
          (c.specialtyRequested ?? '').toLowerCase().includes(term) ||
          (c.consultantName ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(c: ConsultationResponse): void {
    this.selectedConsult.set(c);
  }
  closeDetail(): void {
    this.selectedConsult.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'REQUESTED':
        return 'status-requested';
      case 'ASSIGNED':
      case 'ACKNOWLEDGED':
      case 'SCHEDULED':
        return 'status-acknowledged';
      case 'IN_PROGRESS':
        return 'status-progress';
      case 'COMPLETED':
        return 'status-completed';
      case 'CANCELLED':
        return 'status-cancelled';
      case 'DECLINED':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  getUrgencyClass(urgency: string): string {
    switch (urgency) {
      case 'STAT':
      case 'EMERGENCY':
        return 'urgency-stat';
      case 'URGENT':
        return 'urgency-urgent';
      case 'ROUTINE':
        return 'urgency-routine';
      default:
        return '';
    }
  }

  /* ── Assign / Reassign ── */
  openAssign(c: ConsultationResponse, isReassign = false): void {
    this.assigningItem.set(c);
    this.isReassign.set(isReassign);
    this.assignConsultantId.set('');
    this.assignNote.set('');
    this.assignSpecialtyFilter.set('');
    this.showAssignModal.set(true);
    this.loadAssignableStaff(c.hospitalId);
  }

  filteredAssignStaff(): StaffResponse[] {
    const filter = this.assignSpecialtyFilter().toLowerCase().trim();
    if (!filter) return this.assignStaff();
    return this.assignStaff().filter(
      (s) =>
        (s.specialization ?? '').toLowerCase().includes(filter) ||
        (s.roleName ?? '').toLowerCase().includes(filter) ||
        (s.departmentName ?? '').toLowerCase().includes(filter),
    );
  }

  closeAssignModal(): void {
    this.showAssignModal.set(false);
    this.assigningItem.set(null);
    this.assignStaff.set([]);
  }

  private loadAssignableStaff(hospitalId: string): void {
    this.assignStaffLoading.set(true);
    this.staffService.list(hospitalId).subscribe({
      next: (staff) => {
        this.assignStaff.set(staff.filter((s) => s.active));
        this.assignStaffLoading.set(false);
      },
      error: () => {
        this.assignStaffLoading.set(false);
      },
    });
  }

  submitAssign(): void {
    const consultantId = this.assignConsultantId();
    if (!consultantId) {
      this.toast.error('Please select a consultant');
      return;
    }
    const item = this.assigningItem()!;
    this.assigning.set(true);
    if (this.isReassign()) {
      const reason = this.assignNote().trim();
      if (!reason) {
        this.toast.error('Please enter a reassignment reason');
        this.assigning.set(false);
        return;
      }
      this.consultService.reassign(item.id, consultantId, reason).subscribe({
        next: () => {
          this.toast.success('Consultation reassigned');
          this.closeAssignModal();
          this.assigning.set(false);
          this.load();
        },
        error: () => {
          this.toast.error('Reassignment failed');
          this.assigning.set(false);
        },
      });
    } else {
      this.consultService.assign(item.id, consultantId, this.assignNote() || undefined).subscribe({
        next: () => {
          this.toast.success('Consultation assigned');
          this.closeAssignModal();
          this.assigning.set(false);
          this.load();
        },
        error: () => {
          this.toast.error('Assignment failed');
          this.assigning.set(false);
        },
      });
    }
  }

  countByGroup(group: string): number {
    if (group === 'pending')
      return this.consultations().filter((c) => c.status === 'REQUESTED').length;
    if (group === 'active')
      return this.consultations().filter((c) =>
        ['ASSIGNED', 'ACKNOWLEDGED', 'SCHEDULED', 'IN_PROGRESS'].includes(c.status),
      ).length;
    if (group === 'completed')
      return this.consultations().filter((c) => c.status === 'COMPLETED').length;
    if (group === 'overdue')
      return this.stats()?.overdue ?? this.consultations().filter((c) => this.isOverdue(c)).length;
    return 0;
  }

  isOverdue(c: ConsultationResponse): boolean {
    if (!c.slaDueBy) return false;
    if (['COMPLETED', 'CANCELLED', 'DECLINED'].includes(c.status)) return false;
    return new Date(c.slaDueBy) < new Date();
  }

  getTimelineEvents(c: ConsultationResponse): { label: string; date: string }[] {
    const events: { label: string; date: string }[] = [];
    if (c.requestedAt) events.push({ label: 'Requested', date: c.requestedAt });
    if (c.assignedAt) events.push({ label: 'Assigned', date: c.assignedAt });
    if (c.acknowledgedAt) events.push({ label: 'Acknowledged', date: c.acknowledgedAt });
    if (c.scheduledAt) events.push({ label: 'Scheduled For', date: c.scheduledAt });
    if (c.startedAt) events.push({ label: 'Started', date: c.startedAt });
    if (c.completedAt) events.push({ label: 'Completed', date: c.completedAt });
    if (c.cancelledAt) events.push({ label: 'Cancelled', date: c.cancelledAt });
    if (c.declinedAt) events.push({ label: 'Declined', date: c.declinedAt });
    return events.sort((a, b) => new Date(a.date).getTime() - new Date(b.date).getTime());
  }

  /* ── Schedule workflow ── */
  openSchedule(c: ConsultationResponse): void {
    this.schedulingItem.set(c);
    this.scheduleForm = { scheduledAt: '', scheduleNote: '' };
    this.showScheduleModal.set(true);
  }

  closeScheduleModal(): void {
    this.showScheduleModal.set(false);
    this.schedulingItem.set(null);
  }

  submitSchedule(): void {
    if (!this.scheduleForm.scheduledAt) {
      this.toast.error('Please select a scheduled date/time');
      return;
    }
    this.scheduling.set(true);
    this.consultService
      .schedule(
        this.schedulingItem()!.id,
        this.scheduleForm.scheduledAt,
        this.scheduleForm.scheduleNote || undefined,
      )
      .subscribe({
        next: () => {
          this.toast.success('Consultation scheduled');
          this.closeScheduleModal();
          this.scheduling.set(false);
          this.load();
        },
        error: () => {
          this.toast.error('Schedule failed');
          this.scheduling.set(false);
        },
      });
  }

  /* ── Acknowledge workflow ── */
  acknowledgeConsultation(c: ConsultationResponse): void {
    this.consultService.acknowledge(c.id).subscribe({
      next: () => {
        this.toast.success('Consultation acknowledged');
        this.load();
      },
      error: () => this.toast.error('Acknowledge failed'),
    });
  }

  /* ── Start workflow ── */
  startConsultation(c: ConsultationResponse): void {
    this.consultService.start(c.id).subscribe({
      next: () => {
        this.toast.success('Consultation started');
        this.load();
      },
      error: () => this.toast.error('Start failed'),
    });
  }

  /* ── Complete workflow ── */
  openComplete(c: ConsultationResponse): void {
    this.completingItem.set(c);
    this.completeForm = {
      recommendations: '',
      consultantNote: '',
      followUpRequired: false,
      followUpInstructions: '',
    };
    this.showCompleteModal.set(true);
  }

  closeCompleteModal(): void {
    this.showCompleteModal.set(false);
    this.completingItem.set(null);
  }

  submitComplete(): void {
    if (!this.completeForm.recommendations.trim()) {
      this.toast.error('Recommendations are required');
      return;
    }
    this.completing.set(true);
    this.consultService
      .complete(this.completingItem()!.id, {
        recommendations: this.completeForm.recommendations,
        consultantNote: this.completeForm.consultantNote || undefined,
        followUpRequired: this.completeForm.followUpRequired,
        followUpInstructions: this.completeForm.followUpInstructions || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success('Consultation completed');
          this.closeCompleteModal();
          this.completing.set(false);
          this.load();
        },
        error: () => {
          this.toast.error('Complete failed');
          this.completing.set(false);
        },
      });
  }

  /* ── Decline workflow ── */
  openDecline(c: ConsultationResponse): void {
    this.decliningItem.set(c);
    this.declineReasonValue.set('');
    this.showDeclineModal.set(true);
  }

  closeDeclineModal(): void {
    this.showDeclineModal.set(false);
    this.decliningItem.set(null);
  }

  submitDecline(): void {
    const reason = this.declineReasonValue().trim();
    if (!reason) {
      this.toast.error('Please enter a decline reason');
      return;
    }
    this.declining.set(true);
    this.consultService.decline(this.decliningItem()!.id, reason).subscribe({
      next: () => {
        this.toast.success('Consultation declined');
        this.closeDeclineModal();
        this.declining.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Decline failed');
        this.declining.set(false);
      },
    });
  }
}
