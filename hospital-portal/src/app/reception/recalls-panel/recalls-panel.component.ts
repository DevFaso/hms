import { ReasonDialogComponent } from '../../shared/reason-dialog/reason-dialog.component';
import { focusWhenRendered } from '../../shared/a11y/focus-when-rendered';
import { refusalReason } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { PersonNamePipe, personName } from '../../shared/pipes/person-name.pipe';
import { RouterLink } from '@angular/router';
import { DialogFocusDirective } from '../../shared/a11y/dialog-focus.directive';
import {
  Component,
  inject,
  signal,
  OnInit,
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
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { localDateString } from '../../shared/date-utils';
import { isBeforeToday } from '../../shared/forms/date-rules';
import { PatientSublinePipe } from '../../shared/patient-picker/patient-subline';

type RecallFilter = 'ALL' | RecallStatus;

/**
 * Patient recalls (P3 #22): the return visits the practice owes patients.
 * Fed by checkout follow-up requests and manual desk entry; the sweep
 * notifies patients as due dates approach, and the desk books from here.
 */
/** Every field the add form may refuse, so a corrected one loses its mark. */
const RECALL_FIELDS = ['rc-patient', 'rc-due', 'rc-reason'];

@Component({
  selector: 'app-recalls-panel',
  standalone: true,
  imports: [
    ReasonDialogComponent,
    PersonNamePipe,
    DialogFocusDirective,
    PatientSublinePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    RouterLink,
  ],
  templateUrl: './recalls-panel.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
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
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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
  /** QA D1215: the earliest due date the picker offers (a recall is never due in the past). */
  readonly today = signal(localDateString());
  reason = signal('');
  notes = signal('');

  ngOnInit(): void {
    // QA D912 (#967 review): the request scope, so a super-admin scoped to
    // another hospital searches its patients (and MRNs), not their home one.
    const hospitalId = this.roleCtx.effectiveHospitalIdForRequest() ?? undefined;
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
    this.patientQuery.set(personName(p));
    this.patientResults.set([]);
  }

  openAddForm(): void {
    this.today.set(localDateString());
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
      // QA D1072: the missing field is marked and focused.
      refuseFields(
        this.host.nativeElement,
        [
          !this.selectedPatient() ? 'rc-patient' : null,
          !this.dueDate() ? 'rc-due' : null,
          !this.reason().trim() ? 'rc-reason' : null,
        ].filter((id): id is string => id !== null),
        RECALL_FIELDS,
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      this.toast.error(this.translate.instant('RECEPTION.RECALL_FIELDS_REQUIRED'));
      return;
    }
    // QA D1215 (round 23): a recall due in 2020 sat at the top of the list as
    // overdue the moment it was made. The picker starts at today; a typed past
    // date is refused here, on the field, with the reason.
    this.today.set(localDateString());
    if (isBeforeToday(this.dueDate(), this.today())) {
      const message = this.translate.instant('DATE_RULES.NOT_IN_PAST');
      refuseFields(this.host.nativeElement, ['rc-due'], RECALL_FIELDS, message);
      this.toast.error(message);
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
          refusalReason(err) ?? this.translate.instant('RECEPTION.RECALL_CREATE_FAILED'),
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
        // QA D1199 (round 22): the row leaves the open list with its "Fermer":
        // the keyboard lands on the list's filter, as after a cancel, not the page body.
        focusWhenRendered(this.host.nativeElement, '.filter-btn.active');
        this.loadRecalls();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('RECEPTION.RECALL_ACTION_FAILED'),
        );
        this.actingOnId.set(null);
      },
    });
  }

  /** QA D1157: "Annuler" asks why before anything is sent. */
  readonly cancelling = signal<RecallResponse | null>(null);

  askCancelRecall(recall: RecallResponse): void {
    if (this.actingOnId()) return;
    this.cancelling.set(recall);
  }

  cancelRecall(recall: RecallResponse, reason: string): void {
    if (this.actingOnId()) return;
    this.actingOnId.set(recall.id);
    this.receptionService.cancelRecall(recall.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.RECALL_CANCELLED'));
        this.actingOnId.set(null);
        this.cancelling.set(null);
        // The row leaves the open list: the keyboard lands on the list's filter, not the page body.
        focusWhenRendered(this.host.nativeElement, '.filter-btn.active');
        this.loadRecalls();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('RECEPTION.RECALL_ACTION_FAILED'),
        );
        this.actingOnId.set(null);
      },
    });
  }

  isOverdue(recall: RecallResponse): boolean {
    if (recall.status !== 'PENDING' && recall.status !== 'NOTIFIED') return false;
    return recall.dueDate < localDateString();
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
