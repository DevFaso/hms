import { Component, inject, signal, OnInit, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  ReceptionService,
  RecallRequest,
  RecallResponse,
  RecallStatus,
  RecallType,
} from '../reception.service';
import { PatientService, PatientResponse } from '../../services/patient.service';
import { ReferralService, DepartmentMinimal } from '../../services/referral.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { debounceTime, distinctUntilChanged, Subject, switchMap, catchError, of } from 'rxjs';

type RecallFilter = 'ALL' | RecallStatus;

/**
 * Patient recalls (P3 #22): the return visits the practice owes patients.
 * Fed by checkout follow-up requests and manual desk entry; the sweep
 * notifies patients as due dates approach, and the desk books from here.
 */
@Component({
  selector: 'app-recalls-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './recalls-panel.component.html',
  styleUrl: './recalls-panel.component.scss',
})
export class RecallsPanelComponent implements OnInit {
  @Output() patientClicked = new EventEmitter<string>();

  private readonly receptionService = inject(ReceptionService);
  private readonly patientService = inject(PatientService);
  private readonly referralService = inject(ReferralService);
  private readonly roleCtx = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /* ── List state ─────────── */
  recalls = signal<RecallResponse[]>([]);
  loading = signal(false);
  statusFilter = signal<RecallFilter>('PENDING');
  readonly statusOptions: RecallFilter[] = [
    'ALL',
    'PENDING',
    'NOTIFIED',
    'SCHEDULED',
    'CLOSED',
    'CANCELLED',
  ];
  actingOnId = signal<string | null>(null);

  /* ── Create-recall modal ── */
  showAddForm = signal(false);
  saving = signal(false);
  patientQuery = signal('');
  patientResults = signal<PatientResponse[]>([]);
  selectedPatient = signal<PatientResponse | null>(null);
  private patientSearch$ = new Subject<string>();

  departments = signal<DepartmentMinimal[]>([]);
  selectedDeptId = signal('');
  recallType = signal<RecallType>('FOLLOW_UP');
  readonly recallTypes: RecallType[] = ['FOLLOW_UP', 'PREVENTIVE', 'RESULT_FOLLOW_UP', 'OTHER'];
  dueDate = signal('');
  reason = signal('');
  notes = signal('');

  ngOnInit(): void {
    const hospitalId = this.roleCtx.activeHospitalId ?? undefined;
    if (hospitalId) {
      this.referralService.getDepartmentsByHospital(hospitalId).subscribe({
        next: (list) => this.departments.set(list),
      });
    }

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

    this.loadRecalls();
  }

  loadRecalls(): void {
    this.loading.set(true);
    const status =
      this.statusFilter() === 'ALL' ? undefined : (this.statusFilter() as RecallStatus);
    this.receptionService.getRecalls({ status }).subscribe({
      next: (list) => {
        this.recalls.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('RECEPTION.LOAD_RECALLS_FAILED'));
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
    this.recallType.set('FOLLOW_UP');
    this.dueDate.set('');
    this.reason.set('');
    this.notes.set('');
    this.showAddForm.set(true);
  }

  submitAdd(): void {
    if (!this.selectedPatient() || !this.dueDate() || !this.reason().trim()) {
      this.toast.error(this.translate.instant('RECEPTION.RECALL_FIELDS_REQUIRED'));
      return;
    }
    if (this.saving()) return;
    const req: RecallRequest = {
      patientId: this.selectedPatient()!.id,
      departmentId: this.selectedDeptId() || null,
      recallType: this.recallType(),
      dueDate: this.dueDate(),
      reason: this.reason().trim(),
      notes: this.notes().trim() || null,
    };
    this.saving.set(true);
    this.receptionService.createRecall(req).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.RECALL_CREATED'));
        this.showAddForm.set(false);
        this.saving.set(false);
        this.loadRecalls();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('RECEPTION.RECALL_CREATE_FAILED'),
        );
        this.saving.set(false);
      },
    });
  }

  closeRecall(recall: RecallResponse): void {
    if (this.actingOnId()) return;
    this.actingOnId.set(recall.id);
    this.receptionService.closeRecall(recall.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.RECALL_CLOSED'));
        this.actingOnId.set(null);
        this.loadRecalls();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('RECEPTION.RECALL_ACTION_FAILED'),
        );
        this.actingOnId.set(null);
      },
    });
  }

  cancelRecall(recall: RecallResponse): void {
    if (this.actingOnId()) return;
    this.actingOnId.set(recall.id);
    this.receptionService.cancelRecall(recall.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.RECALL_CANCELLED'));
        this.actingOnId.set(null);
        this.loadRecalls();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('RECEPTION.RECALL_ACTION_FAILED'),
        );
        this.actingOnId.set(null);
      },
    });
  }

  isOverdue(recall: RecallResponse): boolean {
    if (recall.status !== 'PENDING' && recall.status !== 'NOTIFIED') return false;
    return recall.dueDate < new Date().toISOString().split('T')[0];
  }

  isOpen(recall: RecallResponse): boolean {
    return recall.status === 'PENDING' || recall.status === 'NOTIFIED';
  }

  filterLabel(status: RecallFilter): string {
    if (status === 'ALL') return this.translate.instant('COMMON.ALL');
    return this.translate.instant('RECEPTION.RECALL_STATUS_' + status);
  }

  statusClass(status: string): string {
    return 'recall-status-' + status.toLowerCase();
  }
}
