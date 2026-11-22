import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { textMatches } from '../shared/person-search';
import {
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ImagingService,
  ImagingOrderResponse,
  ImagingOrderRequest,
  ImagingOrderStatus,
  ImagingModality,
  ImagingPriority,
  ImagingLaterality,
  ImagingReportResponse,
  ImagingReportStatus,
  ImagingReportAuthorRequest,
  ImagingDuplicateMatch,
  ImagingDuplicateOverrideReason,
  IMAGING_DUPLICATE_OVERRIDE_REASONS,
} from '../services/imaging.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { ImagingReadingService } from '../services/imaging-reading.service';
import { ImagingReadingContextComponent } from './reading-context/imaging-reading-context.component';
import { printOnly } from '../shared/print/print-only';
import { PrintIdentityComponent } from '../shared/print/print-identity.component';
import { MeasurePipe } from '../shared/pipes/measure.pipe';
import { PatientService, PatientResponse } from '../services/patient.service';
import {
  EncounterService,
  EncounterResponse,
  isClosedEncounterStatus,
} from '../services/encounter.service';
import { encounterOptionLabel } from '../shared/encounter-option-label';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { actionRoles } from '../core/action-permissions';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { ImagingImagesComponent } from './imaging-images/imaging-images.component';
import { ImagingExamRecordComponent } from './exam-record/imaging-exam-record.component';
import { IMAGING_EXAM_WIDER_ROLES, IMAGING_TECHNICIAN_ROLES } from '../shared/imaging-access';
import {
  labelledRefusalReason,
  refusalReason,
  refusedFieldReasons,
} from '../shared/refusal-reason';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { InlineSubFormDirective } from '../shared/a11y/inline-sub-form.directive';
import { InlineDiscardAskComponent } from '../shared/a11y/inline-discard-ask.component';
import { AuthorshipService } from '../shared/authorship.service';
import { CANCEL_REASON_REQUIRED_KEY, isOrderCancellable } from '../shared/order-lifecycle';
import { refuseFields } from '../shared/forms/invalid-fields';
import { joinReasons } from '../shared/required-fields';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
import { localDateString, serverLocalDate } from '../shared/date-utils';
import { ActivatedRoute } from '@angular/router';
import { ImagingStudyViewerComponent } from './study-viewer/imaging-study-viewer.component';
import { catchError, forkJoin, map, of, Subscription } from 'rxjs';

/** The cancellation reason's field (QA D1098), marked when it is refused. */
const CANCEL_REASON_ID = 'imaging-cancel-reason';

type ImagingForm = Omit<ImagingOrderRequest, 'laterality'> & {
  laterality?: ImagingLaterality | '';
};

/** QA D1922 (r53): the order's free-text fields, sent trimmed. */
const ORDER_TEXT_FIELDS = [
  'contrastType',
  'contrastAllergyDetails',
  'scheduledTime',
  'appointmentLocation',
  'authorizationNumber',
] as const;

/** The statuses a radiologist may assert while authoring. */
type AuthorableReportStatus = Extract<
  ImagingReportStatus,
  'DRAFT' | 'PRELIMINARY' | 'ADDENDUM' | 'CORRECTED' | 'AMENDED'
>;

/** The administrative outcomes the void modal may write. */
type VoidReportStatus = Extract<ImagingReportStatus, 'CANCELLED' | 'ERROR'>;

/**
 * Bound to the authoring form. Strings rather than optionals throughout so
 * `[(ngModel)]` never sees undefined; they are trimmed to undefined on submit.
 */
interface ReportForm {
  reportTitle: string;
  modality: ImagingModality;
  bodyRegion: string;
  accessionNumber: string;
  /** V317: the DICOM study this report reads, for the in-app viewer. */
  studyInstanceUid: string;
  technique: string;
  comparisonStudies: string;
  findings: string;
  impression: string;
  recommendations: string;
  contrastAdministered: boolean;
  contrastDetails: string;
  criticalFinding: boolean;
  reportStatus: AuthorableReportStatus;
}

/** The orders view's tabs. */
export type ImagingOrderTab = 'all' | 'ordered' | 'completed' | 'cancelled';

/**
 * r44: the one bucketing the tabs, the stat cards and the rows share. The
 * cards counted ORDERED/SCHEDULED/IN_PROGRESS as "active" while a DRAFT or
 * PENDING_AUTHORIZATION order belonged to no tab but "All", so two rows
 * showed as 0 active and 1 completed.
 */
export const IMAGING_ORDER_TAB_STATUSES: Readonly<
  Record<Exclude<ImagingOrderTab, 'all'>, readonly ImagingOrderStatus[]>
> = {
  ordered: ['DRAFT', 'ORDERED', 'PENDING_AUTHORIZATION', 'SCHEDULED', 'IN_PROGRESS'],
  completed: ['COMPLETED', 'RESULTS_AVAILABLE'],
  cancelled: ['CANCELLED'],
};

/**
 * User decision 2026-11-14: the administrative roles read Imagerie and never
 * act on it; a clinical role held on its own is what acts.
 */
export const IMAGING_ADMIN_READER_ROLES: readonly string[] = [
  'ROLE_SUPER_ADMIN',
  'ROLE_HOSPITAL_ADMIN',
];
export const IMAGING_CLINICAL_ROLES: readonly string[] = [
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_RADIOLOGIST',
];
/** The report statuses an administrator may read: released (signed) reads only. */
export const RELEASED_REPORT_STATUSES: readonly ImagingReportStatus[] = [
  'FINAL',
  'ADDENDUM',
  'CORRECTED',
  'AMENDED',
];

/** Provisional rule 2: the note « Autre » requires, in the duplicate warning. */
const DUPLICATE_NOTE_ID = 'img-duplicate-note';
/** Provisional rule 1: the drawer's pre-authorisation number field. */
const RECORD_AUTH_NUMBER_ID = 'img-record-authNumber';

/** QA D1821: the order statuses a study is started from (placed, not yet performed). */
export const STARTABLE_ORDER_STATUSES: readonly ImagingOrderStatus[] = ['ORDERED', 'SCHEDULED'];

/** The new-order form: each request field's element id (QA D1156). */
const ORDER_FIELD_IDS: Readonly<Record<string, string>> = {
  patientId: 'img-patientSearch',
  hospitalId: 'img-hospitalId',
  modality: 'img-modality',
  priority: 'img-priority',
  studyType: 'img-studyType',
  bodyRegion: 'img-bodyRegion',
  laterality: 'img-laterality',
  clinicalQuestion: 'img-clinicalQ',
  contrastType: 'img-contrastType',
  contrastAllergyDetails: 'img-contrastAllergy',
  scheduledDate: 'img-scheduledDate',
  scheduledTime: 'img-scheduledTime',
  appointmentLocation: 'img-location',
  authorizationNumber: 'img-authNumber',
};

/** The new-order form: each request field's label key (QA D1156). */
const ORDER_FIELD_LABELS: Readonly<Record<string, string>> = {
  patientId: 'PATIENTS.PATIENT_NAME',
  hospitalId: 'ENCOUNTERS.HOSPITAL',
  modality: 'IMAGING.MODALITY',
  priority: 'IMAGING.PRIORITY',
  studyType: 'IMAGING.STUDY_TYPE',
  bodyRegion: 'IMAGING.BODY_REGION',
  laterality: 'IMAGING.LATERALITY',
  clinicalQuestion: 'IMAGING.CLINICAL_QUESTION',
  contrastType: 'IMAGING.CONTRAST_TYPE',
  contrastAllergyDetails: 'IMAGING.CONTRAST_ALLERGY_DETAILS',
  scheduledDate: 'IMAGING.SCHEDULED_DATE',
  scheduledTime: 'IMAGING.SCHEDULED_TIME',
  appointmentLocation: 'IMAGING.LOCATION',
  authorizationNumber: 'IMAGING.AUTHORIZATION_NUMBER',
};

@Component({
  selector: 'app-imaging',
  standalone: true,
  imports: [
    DateTimePipe,
    SubmitGuardDirective,
    ModalDialogDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    PatientPickerComponent,
    InlineSubFormDirective,
    InlineDiscardAskComponent,
    ImagingImagesComponent,
    ImagingExamRecordComponent,
    ImagingStudyViewerComponent,
    ImagingReadingContextComponent,
    MeasurePipe,
    PrintIdentityComponent,
  ],
  templateUrl: './imaging.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './imaging.scss',
})
export class ImagingComponent implements OnInit {
  private readonly imagingService = inject(ImagingService);
  /** r63: the « À signer » list and the order's reading context. */
  private readonly readingService = inject(ImagingReadingService);
  private readonly authorship = inject(AuthorshipService);
  private readonly hospitalService = inject(HospitalService);
  private readonly patientService = inject(PatientService);
  private readonly encounterService = inject(EncounterService);
  /**
   * QA D3154 (r70a): the selected patient's open visits here, newest first. A
   * new order is placed in one of them (the latest by default), as a lab order
   * is, so the study belongs to the visit it was asked in.
   */
  readonly orderVisits = signal<EncounterResponse[]>([]);
  /** Bumped on every patient or hospital change: a late answer for another patient is dropped. */
  private orderVisitsRequest = 0;
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  /** QA D1229: the in-app confirmation, never the browser's. */
  private readonly confirmService = inject(ConfirmService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** QA D1924 (r53): the chart opens an order here with `?order=<id>`. */
  private readonly route = inject(ActivatedRoute, { optional: true });
  /** QA D1098: the shared rule for which orders still offer edit and cancel. */
  readonly isOrderCancellable = isOrderCancellable;
  /** QA D1098: why the order is cancelled (required, kept on the order). */
  readonly cancelReason = signal('');

  orders = signal<ImagingOrderResponse[]>([]);
  filtered = signal<ImagingOrderResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  /** r44: the ordered-on range (yyyy-MM-dd, the reader's calendar), '' = open. */
  dateFrom = '';
  dateTo = '';
  /** The orders the search and the dates keep, before the tab: what the cards count. */
  readonly matched = signal<ImagingOrderResponse[]>([]);
  activeTab = signal<ImagingOrderTab>('all');
  selectedOrder = signal<ImagingOrderResponse | null>(null);
  selectedOrderReports = signal<ImagingReportResponse[]>([]);

  hospitals = signal<HospitalResponse[]>([]);

  // Patient picker
  selectedPatient = signal<PatientResponse | null>(null);

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  editId = '';
  form: ImagingForm = this.emptyForm();
  /** QA D1922: the earliest exam day the form offers (the server refuses a past one). */
  readonly today = localDateString();

  /**
   * QA D1921 (r53): the warning shown before a new order is sent, when the
   * patient already has a recent order of the same modality (and region)
   * here. The clinician goes back, or orders anyway. Same pattern as the
   * lab's (#1150).
   */
  readonly duplicateMatches = signal<ImagingDuplicateMatch[]>([]);
  /** Each check, close, reopen or dismissal advances it; a stale reply is ignored. */
  private duplicateCheckSeq = 0;
  /**
   * Rex on #1169: the preview on the wire. Closing, reopening, dismissing or
   * leaving the page cancels it, so a late answer never places an order and
   * never leaves the form busy.
   */
  private previewSub: Subscription | null = null;
  /** A create or update is on the wire; closing the dialog never releases it. */
  private writeInFlight = false;
  /** Advanced on every open and close: a write answers only to the dialog that sent it. */
  private dialogSession = 0;
  /** The patient, hospital, modality and region the shown warning is about. */
  private duplicateWarningKey: string | null = null;

  /**
   * Provisional rule 2 (2026-11-15, pending the radiology approver):
   * « Commander quand même » names why, from a short fixed list; « Autre »
   * needs a note. The server refuses an override without its reason too.
   */
  readonly overrideReasons = IMAGING_DUPLICATE_OVERRIDE_REASONS;
  readonly overrideReason = signal<ImagingDuplicateOverrideReason | null>(null);
  readonly overrideNote = signal('');
  readonly overrideError = signal<string | null>(null);

  /**
   * Provisional rule 1 (2026-11-15): the pre-authorisation number obtained
   * after an urgent study, recorded from the request drawer.
   */
  readonly authorizationNumberDraft = signal('');
  readonly authorizationSaving = signal(false);

  showDeleteConfirm = signal(false);
  deletingItem = signal<ImagingOrderResponse | null>(null);
  deleting = signal(false);

  /* ── Results state ── */
  view = signal<'orders' | 'results'>('orders');
  reports = signal<ImagingReportResponse[]>([]);
  reportsLoading = signal(false);
  reportStatusFilter = signal<ImagingReportStatus>('FINAL');
  reportModalityFilter = signal<ImagingModality | ''>('');
  criticalOnly = signal(false);
  selectedReport = signal<ImagingReportResponse | null>(null);
  /**
   * QA D2307 (r63): every other version of the open report's study (the
   * signed original and its addenda), so an addendum is read with the report.
   */
  readonly selectedReportVersions = signal<ImagingReportResponse[]>([]);
  /** QA D2306 (r63): the results list shows the reads waiting for my signature. */
  readonly toSignOnly = signal(false);
  /** How many reads wait for my signature; null until read. */
  readonly toSignCount = signal<number | null>(null);
  reportLoading = signal(false);

  showStatusModal = signal(false);
  /** Separate from selectedReport so the status modal never drags the detail overlay open. */
  statusTarget = signal<ImagingReportResponse | null>(null);
  statusForm: { status: VoidReportStatus; statusReason: string } = {
    status: 'CANCELLED',
    statusReason: '',
  };
  statusSubmitting = signal(false);
  acknowledging = signal(false);

  /* ── Authoring state (Tier 2 item 26) ── */
  showReportModal = signal(false);
  reportSubmitting = signal(false);
  signing = signal(false);
  /** Set when revising an existing draft; null when authoring a new report. */
  reportEditId = signal<string | null>(null);
  readonly addendumSource = signal<ImagingReportResponse | null>(null);
  readonly writingAddendum = signal(false);
  reportOrderId = signal<string | null>(null);
  reportForm: ReportForm = this.emptyReportForm();
  /** QA D1923 (r53): whose report the authoring form writes. */
  readonly reportPatient = signal<{ name: string | null; mrn: string | null } | null>(null);

  /**
   * User decision 2026-11-14: an administrator (super-admin, hospital admin,
   * admin) with no clinical role reads orders and released reports, nothing
   * else; the server answers the same way.
   */
  readonly isAdminReader =
    this.roleContext.hasAnyActiveRole([...IMAGING_ADMIN_READER_ROLES]) &&
    !this.roleContext.hasAnyActiveRole([...IMAGING_CLINICAL_ROLES]);

  /** The super-admin's « Tous les hôpitaux » view and its hospital filter ('' = all). */
  readonly canPickHospital = this.roleContext.isSuperAdmin();
  /** The super-admin's hospital directory has answered (PR #1163: results wait for it). */
  readonly hospitalsLoaded = signal(false);
  /** « Tous les hôpitaux » results: the hospitals whose reports could not be read. */
  readonly reportsFailedHospitals = signal<string[]>([]);
  readonly hospitalFilter = signal(
    this.roleContext.isSuperAdmin() && !this.roleContext.globalView()
      ? (this.roleContext.selectedHospitalId() ?? '')
      : '',
  );
  /** The combined view names each row's hospital. */
  readonly showHospitalColumn = computed(() => this.canPickHospital && !this.hospitalFilter());

  /** Filter tabs on the Results view — reading by any status is fine. */
  private readonly allReportStatuses: ImagingReportStatus[] = [
    'DRAFT',
    'PRELIMINARY',
    'FINAL',
    'ADDENDUM',
    'CORRECTED',
    'AMENDED',
    'CANCELLED',
  ];

  /** The tabs offered: an administrator's are the released statuses only. */
  readonly reportStatuses: ImagingReportStatus[] = this.isAdminReader
    ? [...RELEASED_REPORT_STATUSES]
    : this.allReportStatuses;

  /**
   * The statuses an author may assert. FINAL is absent because signing is the
   * only path to it, and CANCELLED/ERROR because those are administrative
   * outcomes that require a reason — both enforced backend-side too, so this
   * list is a convenience, not the gate.
   */
  private readonly allAuthorableStatuses = [
    'DRAFT',
    'PRELIMINARY',
    'ADDENDUM',
    'CORRECTED',
    'AMENDED',
  ] as const;

  get authorableStatuses(): readonly ImagingReportStatus[] {
    if (this.writingAddendum()) return ['ADDENDUM'];
    return this.reportEditId() ? this.allAuthorableStatuses : ['DRAFT', 'PRELIMINARY'];
  }

  /** What the void modal may write. */
  readonly voidStatuses: VoidReportStatus[] = ['CANCELLED', 'ERROR'];

  readonly canSeeResults = this.roleContext.hasAnyActiveRole(
    actionRoles('imagingReportsByHospital'),
  );
  /** Mirrors CREATE_RADIOLOGY_REPORTS / SIGN_IMAGING_REPORTS on the controller. */
  readonly canAuthorReports = this.roleContext.hasAnyActiveRole(actionRoles('imagingReportCreate'));
  /** V317: the in-app viewer of a report's images, as DicomProxyController#viewer admits. */
  readonly canViewImages = this.roleContext.hasAnyActiveRole(actionRoles('imagingViewer'));
  readonly canUpdateReportStatus = this.roleContext.hasAnyActiveRole(
    actionRoles('imagingReportStatus'),
  );
  /** QA D527: POST /imaging/orders — the ordering clinicians, not the radiologist. */
  readonly canCreateOrder = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('imagingOrderCreate')),
  );
  /** QA D527: PUT /imaging/orders/{id} — the ordering clinicians. */
  readonly canEditOrder = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('imagingOrderUpdate')),
  );
  /** The server's reason when a save is refused, shown in the open modal (QA D611). */
  readonly formError = signal<string | null>(null);

  /**
   * QA D611 (regression of D527): the role alone is not enough. PUT
   * /imaging/orders/{id} refuses a signed order (it is what the signer
   * attested) and an unsigned order of another ordering clinician; the edit
   * button was offered on a signed order and the save took the page to a 403.
   */
  canEditThisOrder(o: ImagingOrderResponse): boolean {
    if (!this.canEditOrder() || o.providerSignedAt) {
      return false;
    }
    if (this.roleContext.isSuperAdmin() && this.roleContext.globalView()) {
      return true;
    }
    const me = this.auth.getUserProfile()?.id;
    return !o.orderingProviderUserId || o.orderingProviderUserId === me;
  }

  /**
   * QA D527: cancelling goes through PUT /imaging/orders/{id}/status, which a
   * midwife or nurse is refused; she was offered the button and got a 403.
   */
  readonly canCancelOrder = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('imagingOrderStatus')),
  );
  /**
   * Policy rule 3 (2026-11-20, provisional): the caller acts on a study as an imaging technician
   * (the role, and neither a doctor's nor a radiologist's wider reach on its status).
   */
  readonly actsAsTechnician =
    this.roleContext.hasAnyActiveRole([...IMAGING_TECHNICIAN_ROLES]) &&
    !this.roleContext.hasAnyActiveRole([...IMAGING_EXAM_WIDER_ROLES]);
  /**
   * QA D1081: cancelling an imaging order is its author's (the server refuses
   * any other clinician after the confirm). Per row, through the shared rule.
   */
  canCancelThisOrder(o: ImagingOrderResponse): boolean {
    return (
      this.canCancelOrder() &&
      // Policy rule 3 (provisional): a technician never cancels an order; they discontinue a
      // started exam, with a reason, from the exam record block.
      !this.actsAsTechnician &&
      // QA D1098: a resulted order (RESULTS_AVAILABLE) offered "Annuler la demande".
      isOrderCancellable(o.status) &&
      this.authorship.isAuthor({ authorUserId: o.authorUserId ?? o.orderingProviderUserId })
    );
  }

  /**
   * QA D1821 (r51): the portal could neither start nor complete a study, so
   * a new order could not be reported from the UI. Both go through PUT
   * /imaging/orders/{id}/status, so they are offered to its roles
   * (imagingOrderStatus), no others. A placed order (ordered or scheduled)
   * is started; a study in progress is completed. A draft or an order
   * awaiting authorisation is not started from here, and a study is not
   * completed before it is started.
   */
  canStartStudy(o: ImagingOrderResponse): boolean {
    return (
      this.canCancelOrder() &&
      STARTABLE_ORDER_STATUSES.includes(o.status) &&
      this.mayPerform(o) &&
      !this.technicianAwaitsAuthorizationNumber(o)
    );
  }

  /**
   * QA D2914 (r66): a routine study whose pre-authorisation number is not recorded does not start
   * (the server refuses), and a technician cannot record the number: they are not offered
   * « Démarrer l'examen », and are told who records it. An urgent or STAT study starts without it.
   */
  technicianAwaitsAuthorizationNumber(o: ImagingOrderResponse): boolean {
    return (
      this.actsAsTechnician &&
      STARTABLE_ORDER_STATUSES.includes(o.status) &&
      this.awaitsAuthorizationNumber(o) &&
      o.priority !== 'URGENT' &&
      o.priority !== 'STAT'
    );
  }

  /**
   * QA D2914 (r66): « Voir le compte rendu » from a request. A technician reads the released
   * report of an exam they performed and no other (the server answers a missing report), so the
   * link is offered to them only there. Everyone else as before.
   */
  canViewReportOfOrder(o: ImagingOrderResponse): boolean {
    if (!this.actsAsTechnician) {
      return o.status === 'COMPLETED' || o.status === 'RESULTS_AVAILABLE';
    }
    const me = this.auth.getUserProfile()?.id;
    return o.status === 'RESULTS_AVAILABLE' && !!me && o.performedByUserId === me;
  }

  canCompleteStudy(o: ImagingOrderResponse): boolean {
    return this.canCancelOrder() && o.status === 'IN_PROGRESS' && this.mayPerform(o);
  }

  /**
   * Owner decision 2026-11-20 (provisional): an ultrasound is performed by a physician unless the
   * hospital's setting lets an imaging technician do it. The server says so on the order
   * (`technicianMayPerform`) and refuses the step otherwise; nobody else is concerned.
   */
  private mayPerform(o: ImagingOrderResponse): boolean {
    return !this.actsAsTechnician || o.technicianMayPerform !== false;
  }

  /** The order whose status write is in flight; its buttons stay disabled until it answers. */
  readonly studyStatusSavingId = signal<string | null>(null);

  startStudy(o: ImagingOrderResponse): void {
    this.writeStudyStatus(o, 'IN_PROGRESS', 'IMAGING.STUDY_STARTED');
  }

  /** Completing closes the study for good (a resulted order is not reopened), so it asks first. */
  async completeStudy(o: ImagingOrderResponse): Promise<void> {
    if (
      !(await this.confirmService.ask({
        titleKey: 'IMAGING.COMPLETE_STUDY_CONFIRM',
        confirmKey: 'IMAGING.COMPLETE_STUDY',
      }))
    )
      return;
    this.writeStudyStatus(o, 'COMPLETED', 'IMAGING.STUDY_COMPLETED');
  }

  /**
   * The status alone travels: the server keeps the schedule, the location,
   * the notes and the pre-authorisation it is not sent (QA D1822).
   */
  private writeStudyStatus(
    o: ImagingOrderResponse,
    status: Extract<ImagingOrderStatus, 'IN_PROGRESS' | 'COMPLETED'>,
    doneKey: string,
  ): void {
    if (this.studyStatusSavingId()) return;
    this.studyStatusSavingId.set(o.id);
    this.imagingService.updateOrderStatus(o.id, { status }).subscribe({
      next: (updated) => {
        this.studyStatusSavingId.set(null);
        this.toast.success(this.translate.instant(doneKey));
        if (this.selectedOrder()?.id === o.id) {
          this.selectedOrder.set(updated ?? { ...o, status });
        }
        this.load();
      },
      error: (err: unknown) => {
        this.studyStatusSavingId.set(null);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('IMAGING.STUDY_STATUS_ERROR'),
        );
      },
    });
  }

  /**
   * Policy rule 3 (provisional): the exam record block answered (a check-in, a saved record, a
   * discontinued exam). The drawer shows the order as the server returned it; the list reloads.
   */
  onExamChanged(updated: ImagingOrderResponse): void {
    if (this.selectedOrder()?.id === updated.id) {
      this.selectedOrder.set(updated);
    }
    this.load();
  }

  /** From the request drawer: the authoring form opens over the list, the drawer closes. */
  authorFromDrawer(o: ImagingOrderResponse): void {
    this.closeDetail();
    this.authorForOrder(o);
  }

  /**
   * QA D2306 (r63): « Rédiger le compte rendu » opened a blank form even when
   * the study already had a saved, unsigned read, which would file a second
   * report. It now reopens that read; after a signature it opens an addendum
   * (QA D2307); only a study with no live read gets a new one.
   */
  authorForOrder(o: ImagingOrderResponse): void {
    this.imagingService.getReportsForOrder(o.id).subscribe({
      next: (reports) => {
        const live = (reports ?? []).filter(
          (r) => r.reportStatus !== 'CANCELLED' && r.reportStatus !== 'ERROR',
        );
        const draft = live.find((r) => !r.signed);
        if (draft) {
          this.openEditReport(draft);
        } else {
          this.openAuthorReport(
            o,
            live.some((r) => r.signed),
          );
        }
      },
      error: () => this.openAuthorReport(o, o.status === 'RESULTS_AVAILABLE'),
    });
  }

  /** RADIOLOGIST joins DOCTOR here — the endpoint has admitted the role since item 26. */
  readonly canAckCritical = this.roleContext.hasAnyActiveRole(actionRoles('imagingCriticalAck'));
  /** QA D2306 (r63): the « À signer » list. */
  readonly canSeeToSign = this.roleContext.hasAnyActiveRole(actionRoles('imagingReportsToSign'));
  /** r63: age, sex, pregnancy, creatinine and prior studies beside the order (clinical readers). */
  readonly canSeeReadingContext = this.roleContext.hasAnyActiveRole(
    actionRoles('imagingReadingContext'),
  );

  modalities: ImagingModality[] = [
    'XRAY',
    'CT',
    'MRI',
    'ULTRASOUND',
    'PET',
    'MAMMOGRAPHY',
    'FLUOROSCOPY',
    'NUCLEAR_MEDICINE',
    'INTERVENTIONAL_RADIOLOGY',
    'DEXA',
    'OTHER',
  ];
  priorities: ImagingPriority[] = ['ROUTINE', 'URGENT', 'STAT'];
  /**
   * Laterality options for the order form. A getter, not a field, so the
   * labels follow a runtime language switch; the template still reads
   * `lat.value` / `lat.label`.
   */
  get lateralities(): { value: ImagingLaterality; label: string }[] {
    return [
      { value: 'LEFT', label: this.translate.instant('IMAGING.LATERALITY_LEFT') },
      { value: 'RIGHT', label: this.translate.instant('IMAGING.LATERALITY_RIGHT') },
      { value: 'BILATERAL', label: this.translate.instant('IMAGING.LATERALITY_BILATERAL') },
      { value: 'MIDLINE', label: this.translate.instant('IMAGING.LATERALITY_MIDLINE') },
      {
        value: 'NOT_APPLICABLE',
        label: this.translate.instant('IMAGING.LATERALITY_NOT_APPLICABLE'),
      },
    ];
  }

  constructor() {
    // Rex on #1169: a preview still pending when the page is left is dropped.
    inject(DestroyRef).onDestroy(() => this.clearDuplicateWarning());
  }

  ngOnInit(): void {
    this.load();
    this.loadAssignedHospitals();
    this.openOrderFromLink();
  }

  /** QA D1924 (r53): the chart's link to a study lands on the order's drawer and its reports. */
  private openOrderFromLink(): void {
    const orderId = this.route?.snapshot?.queryParamMap?.get('order');
    if (!orderId) return;
    this.imagingService.getOrder(orderId).subscribe({
      next: (order) => {
        if (order) this.viewDetail(order);
      },
      error: () => this.toast.error(this.translate.instant('IMAGING.ORDER_NOT_FOUND')),
    });
  }

  emptyForm(): ImagingForm {
    return {
      patientId: '',
      hospitalId: '',
      modality: 'XRAY' as ImagingModality,
      studyType: '',
      priority: 'ROUTINE' as ImagingPriority,
      laterality: undefined,
      contrastRequired: false,
      contrastType: '',
      hasContrastAllergy: false,
      contrastAllergyDetails: '',
      scheduledDate: '',
      scheduledTime: '',
      appointmentLocation: '',
      requiresAuthorization: false,
      authorizationNumber: '',
    };
  }

  /** ── TENANT ISOLATION: only SUPER_ADMIN may choose from all hospitals ── */
  private loadAssignedHospitals(): void {
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe({
        next: (h) => {
          this.hospitals.set(h ?? []);
          this.hospitalsLoaded.set(true);
          // Results opened while the directory was loading wait for it (PR #1163).
          if (this.view() === 'results' && !this.hospitalFilter()) this.loadReports();
        },
        error: () => this.hospitalsLoaded.set(true),
      });
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
    return h.length === 1 ? h[0].name : this.translate.instant('COMMON.NO_HOSPITAL_ASSIGNED');
  }

  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  onPatientPicked(p: PatientResponse | null): void {
    this.selectedPatient.set(p);
    this.form.patientId = p?.id ?? '';
    this.loadOrderVisits();
  }

  /** A super-admin changing the hospital: the visits offered are the patient's there. */
  onOrderHospitalChange(hospitalId: string): void {
    this.form.hospitalId = hospitalId;
    this.loadOrderVisits();
  }

  /**
   * QA D3154 (r70a): the patient's open visits at the order's hospital, the
   * latest chosen. A closed visit takes no new order, so it is not offered;
   * with none open the order stands alone, as the server would place it.
   */
  private loadOrderVisits(): void {
    const request = ++this.orderVisitsRequest;
    this.orderVisits.set([]);
    this.form.encounterId = undefined;
    const patientId = this.form.patientId;
    const hospitalId = this.form.hospitalId;
    if (this.editing() || !patientId || !hospitalId) return;
    this.encounterService.list({ patientId, hospitalId }).subscribe({
      next: (list) => {
        if (request !== this.orderVisitsRequest) return;
        const open = [...(list ?? [])]
          .filter((e) => !isClosedEncounterStatus(e.status))
          .sort((a, b) => (b.encounterDate ?? '').localeCompare(a.encounterDate ?? ''));
        this.orderVisits.set(open);
        this.form.encounterId = open[0]?.id;
      },
      error: () => {
        if (request === this.orderVisitsRequest) this.orderVisits.set([]);
      },
    });
  }

  /** QA D3154: a visit's day, time and department, as every encounter picker shows it. */
  orderVisitLabel(e: EncounterResponse): string {
    return encounterOptionLabel(e);
  }

  openCreate(): void {
    this.dialogSession++;
    this.form = this.emptyForm();
    this.formError.set(null);
    this.clearDuplicateWarning();
    this.editing.set(false);
    this.editId = '';
    this.selectedPatient.set(null);
    this.orderVisitsRequest++;
    this.orderVisits.set([]);
    // Re-apply locked hospital after emptyForm() reset
    if (this.hospitalLocked) {
      const h = this.hospitals();
      if (h.length === 1) this.form.hospitalId = h[0].id;
    }
    this.showModal.set(true);
  }

  openEdit(o: ImagingOrderResponse): void {
    this.form = {
      patientId: o.patientId,
      hospitalId: o.hospitalId ?? '',
      modality: o.modality ?? 'XRAY',
      studyType: o.studyType ?? '',
      bodyRegion: o.bodyRegion ?? '',
      priority: o.priority ?? 'ROUTINE',
      laterality: (o.laterality as ImagingLaterality) || '',
      clinicalQuestion: o.clinicalQuestion ?? '',
      contrastRequired: !!o.contrastRequired,
      contrastType: o.contrastType ?? '',
      hasContrastAllergy: !!o.hasContrastAllergy,
      contrastAllergyDetails: o.contrastAllergyDetails ?? '',
      scheduledDate: o.scheduledDate ?? '',
      scheduledTime: o.scheduledTime ?? '',
      appointmentLocation: o.appointmentLocation ?? '',
      requiresAuthorization: !!o.requiresAuthorization,
      authorizationNumber: o.authorizationNumber ?? '',
    };
    this.dialogSession++;
    this.clearDuplicateWarning();
    this.selectedPatient.set({
      id: o.patientId,
      firstName: o.patientDisplayName?.split(' ')[0] ?? '',
      lastName: o.patientDisplayName?.split(' ').slice(1).join(' ') ?? '',
      email: '',
      mrn: o.patientMrn,
    } as PatientResponse);
    this.editId = o.id;
    this.formError.set(null);
    this.editing.set(true);
    // QA D3154: an edit keeps the order's visit (the server keeps it when none is sent).
    this.orderVisitsRequest++;
    this.orderVisits.set([]);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.dialogSession++;
    this.showModal.set(false);
    this.clearDuplicateWarning();
  }

  /** What a duplicate is judged on: this patient, this hospital, this modality and region. */
  private duplicateKey(): string {
    return [
      this.form.patientId,
      this.form.hospitalId,
      this.form.modality,
      (this.form.bodyRegion ?? '').trim().toLowerCase(),
    ].join('|');
  }

  private clearDuplicateWarning(): void {
    this.duplicateCheckSeq++;
    this.duplicateWarningKey = null;
    this.duplicateMatches.set([]);
    this.overrideReason.set(null);
    this.overrideNote.set('');
    this.overrideError.set(null);
    // Rex on #1169: a cancelled preview releases the form; a write in flight keeps it.
    if (this.previewSub) {
      this.previewSub.unsubscribe();
      this.previewSub = null;
    }
    if (!this.writeInFlight) this.saving.set(false);
  }

  /**
   * QA D1922: what the form refuses before any request: a time with no day,
   * and a new order for a day that has passed (the server refuses it too, QA D253).
   */
  private refuseScheduleFields(): boolean {
    const date = (this.form.scheduledDate ?? '').trim();
    const time = (this.form.scheduledTime ?? '').trim();
    const ids = [ORDER_FIELD_IDS['scheduledDate']];
    let reason: string | null = null;
    if (time && !date) reason = 'IMAGING.SCHEDULED_TIME_NEEDS_DATE';
    else if (date && date < this.today && !this.editing()) reason = 'IMAGING.SCHEDULED_DATE_PAST';
    if (!reason) {
      refuseFields(this.host.nativeElement, [], ids, '');
      return false;
    }
    const message = this.translate.instant(reason);
    refuseFields(this.host.nativeElement, ids, ids, message);
    this.formError.set(message);
    return true;
  }

  /**
   * QA D1921 (r53): a new order first asks whether the patient already has a
   * recent order of the same study here; if so the warning is shown and
   * nothing is sent until the clinician chooses "Order anyway". A failed
   * check never blocks the order (the server checks again when it is placed).
   */
  submitForm(): void {
    if (this.saving()) return;
    this.formError.set(null);
    if (this.refuseScheduleFields()) return;
    if (this.editing()) {
      this.saveOrder(false);
      return;
    }
    this.clearDuplicateWarning();
    const seq = this.duplicateCheckSeq;
    const key = this.duplicateKey();
    const current = (): boolean => {
      if (seq !== this.duplicateCheckSeq) return false;
      if (!this.showModal() || key !== this.duplicateKey()) {
        this.saving.set(false);
        return false;
      }
      return true;
    };
    this.saving.set(true);
    this.previewSub = this.imagingService
      .checkDuplicates(this.form.patientId, this.form.modality, this.form.bodyRegion)
      .subscribe({
        next: (matches) => {
          this.previewSub = null;
          if (!current()) return;
          if (Array.isArray(matches) && matches.length > 0) {
            this.duplicateWarningKey = key;
            this.duplicateMatches.set(matches);
            this.saving.set(false);
            return;
          }
          this.saveOrder(false);
        },
        error: () => {
          this.previewSub = null;
          if (current()) this.saveOrder(false);
        },
      });
  }

  /**
   * "Order anyway": asked again if the form changed since the warning, and
   * only with a reason (and a note for « Autre ») (provisional rule 2).
   */
  orderAnyway(): void {
    if (this.duplicateMatches().length === 0 || this.duplicateWarningKey !== this.duplicateKey()) {
      this.submitForm();
      return;
    }
    const reason = this.overrideReason();
    const reasonIds = this.overrideReasons.map((r) => `img-duplicate-reason-${r}`);
    const allIds = [...reasonIds, DUPLICATE_NOTE_ID];
    // A reason, and with « Autre » its note: every missing part said at once.
    const refused: { id: string; message: string }[] = [];
    if (!reason) {
      refused.push({
        id: reasonIds[0],
        message: this.translate.instant('IMAGING.DUPLICATE.REASON_REQUIRED'),
      });
    } else if (reason === 'OTHER' && !this.overrideNote().trim()) {
      refused.push({
        id: DUPLICATE_NOTE_ID,
        message: this.translate.instant('IMAGING.DUPLICATE.NOTE_REQUIRED'),
      });
    }
    const message = joinReasons(refused.map((r) => r.message));
    refuseFields(this.host.nativeElement, refused, allIds, message ?? '');
    if (message) {
      this.overrideError.set(message);
      return;
    }
    this.overrideError.set(null);
    this.saveOrder(true);
  }

  /** Back to the form without ordering. */
  dismissDuplicateWarning(): void {
    this.clearDuplicateWarning();
  }

  /** The request: free text trimmed; on an edit a cleared field is sent empty so it is cleared. */
  private orderPayload(): ImagingOrderRequest {
    const editing = this.editing();
    const text = (value: string | undefined): string | undefined => {
      const trimmed = (value ?? '').trim();
      return trimmed || (editing ? '' : undefined);
    };
    const payload: ImagingOrderRequest = {
      ...this.form,
      laterality: this.form.laterality || undefined,
      scheduledDate: (this.form.scheduledDate ?? '').trim() || undefined,
    };
    for (const field of ORDER_TEXT_FIELDS) payload[field] = text(this.form[field]);
    // A contrast type, an allergy detail or a number only stands with its box ticked.
    const cleared = editing ? '' : undefined;
    if (!payload.contrastRequired) payload.contrastType = cleared;
    if (!payload.hasContrastAllergy) payload.contrastAllergyDetails = cleared;
    if (!payload.requiresAuthorization) payload.authorizationNumber = cleared;
    return payload;
  }

  private saveOrder(acknowledgedDuplicate: boolean): void {
    if (this.writeInFlight) return;
    this.writeInFlight = true;
    this.saving.set(true);
    this.formError.set(null);
    const payload = this.orderPayload();
    if (acknowledgedDuplicate && this.overrideReason()) {
      payload.duplicateOverride = true;
      payload.duplicateOverrideReason = this.overrideReason() ?? undefined;
      payload.duplicateOverrideNote = this.overrideNote().trim() || undefined;
    }
    const wasEditing = this.editing();
    const session = this.dialogSession;
    const op = wasEditing
      ? this.imagingService.updateOrder(this.editId, payload)
      : this.imagingService.createOrder(payload);
    op.subscribe({
      next: (saved) => {
        this.writeInFlight = false;
        this.saving.set(false);
        this.load();
        // An answer that outlived its dialog refreshes the list only: it never
        // closes or toasts over a dialog opened since.
        if (session !== this.dialogSession) return;
        this.toast.success(
          this.translate.instant(wasEditing ? 'IMAGING.ORDER_UPDATED' : 'IMAGING.ORDER_CREATED'),
        );
        // QA D1921: a duplicate the server found that nobody was shown (an
        // order placed since the preview, or a preview that failed) is told.
        if (!wasEditing && !acknowledgedDuplicate && saved?.duplicateOfRecentOrder) {
          this.toast.warning(this.translate.instant('IMAGING.DUPLICATE.PLACED_ANYWAY'));
        }
        this.closeModal();
      },
      error: (err: unknown) => {
        this.writeInFlight = false;
        this.saving.set(false);
        if (session !== this.dialogSession) return;
        // QA D611: the server's reason stays in the open modal; nothing typed is lost.
        // QA D1156: a validation refusal names its fields by their labels and
        // marks them, never the bare "must not be null" text.
        const reason = labelledRefusalReason(err, this.translate, ORDER_FIELD_LABELS);
        const refused = refusedFieldReasons(err, ORDER_FIELD_IDS);
        if (refused.length > 0) {
          // Each field carries the server's own reason for it, not always "required".
          refuseFields(this.host.nativeElement, [], Object.values(ORDER_FIELD_IDS), '');
          // Reversed: each call focuses its field, so the first refused one ends up focused.
          for (const [id, text] of [...refused].reverse()) {
            refuseFields(this.host.nativeElement, [id], [id], text);
          }
        }
        this.formError.set(reason);
        this.toast.error(reason ?? this.translate.instant('IMAGING.ORDER_SAVE_ERROR'));
      },
    });
  }

  confirmCancel(o: ImagingOrderResponse): void {
    this.deletingItem.set(o);
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
      // QA D1098: the server refuses a cancel without a reason; the field says so first.
      const message = this.translate.instant(CANCEL_REASON_REQUIRED_KEY);
      refuseFields(this.host.nativeElement, [CANCEL_REASON_ID], [CANCEL_REASON_ID], message);
      return;
    }
    this.deleting.set(true);
    this.imagingService
      .updateOrderStatus(this.deletingItem()!.id, {
        status: 'CANCELLED',
        cancellationReason: reason,
        // Stored on the order and read back by everyone, so it stays canonical
        // English: translating it would bake the cancelling user's locale
        // into the record and give the same note three spellings.
        notes: 'Cancelled by admin',
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('IMAGING.ORDER_CANCELLED'));
          this.cancelDeleteAction();
          this.deleting.set(false);
          this.load();
        },
        error: (err: unknown) => {
          // QA D930 class: "only the ordering provider or a radiologist" is the reason.
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('IMAGING.ORDER_CANCEL_ERROR'),
          );
          this.deleting.set(false);
        },
      });
  }

  load(): void {
    this.loading.set(true);
    const hospitalId = this.canPickHospital ? this.hospitalFilter() : '';
    const orders$ = hospitalId
      ? this.imagingService.getOrdersByHospital(hospitalId)
      : // « Tous les hôpitaux »: without the chip's hospital, or the combined view is one hospital.
        this.imagingService.getAllOrders(undefined, { allHospitals: this.canPickHospital });
    orders$.subscribe({
      next: (list) => {
        this.orders.set(Array.isArray(list) ? list : []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('IMAGING.ORDERS_LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  setTab(tab: ImagingOrderTab): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  /** r44: search (patient name, MRN, study, region, modality) and the ordered-on range. */
  applyFilter(): void {
    let list = this.orders();
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (o) =>
          textMatches(o.patientDisplayName, term) ||
          (o.patientMrn ?? '').toLowerCase().includes(term) ||
          (o.studyType ?? '').toLowerCase().includes(term) ||
          (o.bodyRegion ?? '').toLowerCase().includes(term) ||
          (o.modality ?? '').toLowerCase().includes(term),
      );
    }
    if (this.dateFrom || this.dateTo) {
      list = list.filter((o) => {
        const day = serverLocalDate(o.orderedAt);
        return (
          !!day && (!this.dateFrom || day >= this.dateFrom) && (!this.dateTo || day <= this.dateTo)
        );
      });
    }
    this.matched.set(list);
    const tab = this.activeTab();
    this.filtered.set(tab === 'all' ? list : list.filter((o) => this.inTab(o, tab)));
  }

  clearDates(): void {
    this.dateFrom = '';
    this.dateTo = '';
    this.applyFilter();
  }

  private inTab(o: ImagingOrderResponse, tab: Exclude<ImagingOrderTab, 'all'>): boolean {
    return IMAGING_ORDER_TAB_STATUSES[tab].includes(o.status);
  }

  /**
   * r44: "Planifié" with its time when one was set. The date alone is a
   * calendar date (mediumDate); with a time it is a wall time (dateTime).
   */
  scheduledAt(o: ImagingOrderResponse): { scheduledAt: Date; withTime: boolean } | null {
    const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(o.scheduledDate ?? '');
    if (!day) return null;
    // A clinic wall time (LocalDate + typed time), never a server instant.
    const time = /^(\d{2}):(\d{2})/.exec((o.scheduledTime ?? '').trim());
    const [y, m, d] = [Number(day[1]), Number(day[2]) - 1, Number(day[3])];
    return time
      ? { scheduledAt: new Date(y, m, d, Number(time[1]), Number(time[2])), withTime: true }
      : { scheduledAt: new Date(y, m, d), withTime: false };
  }

  /** QA D1921: the earlier order a flagged one may duplicate, when it is in the list. */
  duplicateReference(o: ImagingOrderResponse): ImagingOrderResponse | null {
    if (!o.duplicateReferenceOrderId) return null;
    return this.orders().find((x) => x.id === o.duplicateReferenceOrderId) ?? null;
  }

  /**
   * Provisional rule 1: a doctor or a radiologist records the number of a study done without it
   * (PUT /imaging/orders/{id}/authorization). Review of #1243: its own action entry, since the
   * status endpoint now also admits the imaging technician, whom this endpoint refuses.
   */
  canRecordAuthorization(o: ImagingOrderResponse): boolean {
    return (
      this.roleContext.hasAnyActiveRole(actionRoles('imagingOrderAuthorization')) &&
      !!o.authorizationPending
    );
  }

  recordAuthorization(o: ImagingOrderResponse): void {
    const number = this.authorizationNumberDraft().trim();
    if (!number) {
      const message = this.translate.instant('IMAGING.PREAUTH_PENDING.NUMBER_REQUIRED');
      refuseFields(
        this.host.nativeElement,
        [RECORD_AUTH_NUMBER_ID],
        [RECORD_AUTH_NUMBER_ID],
        message,
      );
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], [RECORD_AUTH_NUMBER_ID], '');
    if (this.authorizationSaving()) return;
    this.authorizationSaving.set(true);
    this.imagingService.recordAuthorization(o.id, number).subscribe({
      next: (updated) => {
        this.authorizationSaving.set(false);
        this.authorizationNumberDraft.set('');
        this.toast.success(this.translate.instant('IMAGING.PREAUTH_PENDING.RECORDED'));
        if (this.selectedOrder()?.id === o.id) {
          this.selectedOrder.set(
            updated ?? {
              ...o,
              authorizationNumber: number,
              authorizationPending: false,
              authorizationPendingSince: null,
            },
          );
        }
        this.load();
      },
      error: (err: unknown) => {
        this.authorizationSaving.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('IMAGING.PREAUTH_PENDING.RECORD_ERROR'),
        );
      },
    });
  }

  /** QA D1922: a pre-authorisation required and not yet numbered (the study cannot start, D1925). */
  awaitsAuthorizationNumber(o: {
    requiresAuthorization?: boolean | null;
    authorizationNumber?: string | null;
  }): boolean {
    return !!o.requiresAuthorization && !(o.authorizationNumber ?? '').trim();
  }

  /** r44: a laterality the orderer marked not applicable is not shown at all. */
  lateralityApplies(o: ImagingOrderResponse): boolean {
    return o.laterality !== 'NOT_APPLICABLE';
  }

  /** r44: "Completed at" only applies once the study is done. */
  isResulted(o: ImagingOrderResponse): boolean {
    return IMAGING_ORDER_TAB_STATUSES.completed.includes(o.status);
  }

  viewDetail(o: ImagingOrderResponse): void {
    this.selectedOrder.set(o);
    this.authorizationNumberDraft.set('');
    this.selectedOrderReports.set([]);
    this.imagingService.getReportsForOrder(o.id).subscribe({
      next: (reports) => this.selectedOrderReports.set(reports ?? []),
      error: () => this.selectedOrderReports.set([]),
    });
  }
  closeDetail(): void {
    this.selectedOrder.set(null);
    this.selectedOrderReports.set([]);
  }

  openPacsViewer(url: string): void {
    // V317: a link the browser opens is http(s) only, never javascript: or data:.
    if (!url || !/^https?:\/\//i.test(url.trim())) return;
    window.open(url, '_blank', 'noopener,noreferrer');
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'ORDERED':
      case 'SCHEDULED':
        return 'status-ordered';
      case 'IN_PROGRESS':
        return 'status-progress';
      case 'COMPLETED':
      case 'RESULTS_AVAILABLE':
        return 'status-completed';
      case 'DRAFT':
      case 'PENDING_AUTHORIZATION':
        return 'status-preliminary';
      case 'CANCELLED':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  getPriorityClass(priority: string): string {
    switch (priority) {
      case 'STAT':
        return 'priority-stat';
      case 'URGENT':
        return 'priority-urgent';
      case 'ROUTINE':
        return 'priority-routine';
      default:
        return '';
    }
  }

  /** r44: each card counts exactly the rows its tab shows. */
  readonly activeOrderCount = computed(
    () => this.matched().filter((o) => this.inTab(o, 'ordered')).length,
  );
  readonly completedOrderCount = computed(
    () => this.matched().filter((o) => this.inTab(o, 'completed')).length,
  );
  readonly cancelledOrderCount = computed(
    () => this.matched().filter((o) => this.inTab(o, 'cancelled')).length,
  );

  /* ── Results ── */

  setView(view: 'orders' | 'results'): void {
    this.view.set(view);
    if (view === 'results' && this.reports().length === 0) {
      this.loadReports();
    }
    if (view === 'results') this.refreshToSignCount();
  }

  /** QA D2306 (r63): the « À signer » tab of the results list. */
  showToSign(): void {
    this.toSignOnly.set(true);
    this.loadReports();
  }

  private refreshToSignCount(): void {
    if (!this.canSeeToSign) return;
    this.readingService.toSign().subscribe({
      next: (list) => this.toSignCount.set(Array.isArray(list) ? list.length : 0),
      error: () => this.toSignCount.set(null),
    });
  }

  private resultsHospitalId(): string | null {
    if (this.canPickHospital) return this.hospitalFilter() || null;
    return this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
  }

  /** The super-admin picks « Tous les hôpitaux » ('') or one hospital; both views reload. */
  setHospitalFilter(hospitalId: string): void {
    this.hospitalFilter.set(hospitalId ?? '');
    this.load();
    if (this.view() === 'results') this.loadReports();
    else this.reports.set([]);
  }

  /** A row's hospital in the combined view (a report carries its id only). */
  hospitalNameOf(hospitalId: string | null | undefined, name?: string | null): string {
    if (name) return name;
    return this.hospitals().find((h) => h.id === hospitalId)?.name ?? '—';
  }

  loadReports(): void {
    this.reportsFailedHospitals.set([]);
    if (this.toSignOnly()) {
      this.loadToSign();
      return;
    }
    const hospitalId = this.resultsHospitalId();
    if (!hospitalId) {
      if (this.canPickHospital) this.loadReportsEverywhere();
      return;
    }
    this.reportsLoading.set(true);
    // Backend ignores `modality` whenever `status` is present, so fetch by
    // status only and apply the modality filter client-side (visibleReports).
    this.imagingService
      .getReportsByHospital(hospitalId, { status: this.reportStatusFilter() })
      .subscribe({
        next: (list) => {
          this.reports.set(Array.isArray(list) ? list : []);
          this.reportsLoading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('IMAGING.RESULTS_LOAD_ERROR'));
          this.reportsLoading.set(false);
        },
      });
  }

  /** QA D2306 (r63): the reads waiting for my signature, at the active hospital. */
  private loadToSign(): void {
    this.reportsLoading.set(true);
    this.readingService.toSign().subscribe({
      next: (list) => {
        const reports = Array.isArray(list) ? list : [];
        this.reports.set(reports);
        this.toSignCount.set(reports.length);
        this.reportsLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('IMAGING.RESULTS_LOAD_ERROR'));
        this.reportsLoading.set(false);
      },
    });
  }

  /**
   * « Tous les hôpitaux »: the reports are listed per hospital, so the
   * combined list is each hospital's, merged; a hospital that fails to answer
   * costs its own rows only.
   */
  private loadReportsEverywhere(): void {
    this.reportsFailedHospitals.set([]);
    if (!this.hospitalsLoaded()) {
      // The directory is still loading: it reloads the results when it lands.
      this.reportsLoading.set(true);
      return;
    }
    const hospitals = this.hospitals();
    if (hospitals.length === 0) {
      this.reports.set([]);
      this.reportsLoading.set(false);
      return;
    }
    this.reportsLoading.set(true);
    forkJoin(
      hospitals.map((h) =>
        this.imagingService.getReportsByHospital(h.id, { status: this.reportStatusFilter() }).pipe(
          map((list) => ({ hospital: h, list: Array.isArray(list) ? list : [], failed: false })),
          catchError(() => of({ hospital: h, list: [] as ImagingReportResponse[], failed: true })),
        ),
      ),
    ).subscribe((answers) => {
      this.reports.set(answers.flatMap((a) => a.list));
      // A hospital that did not answer is named, never shown as "no reports".
      const failed = answers.filter((a) => a.failed).map((a) => a.hospital.name);
      this.reportsFailedHospitals.set(failed);
      if (failed.length === answers.length) {
        this.toast.error(this.translate.instant('IMAGING.RESULTS_LOAD_ERROR'));
      }
      this.reportsLoading.set(false);
    });
  }

  setReportStatusFilter(status: ImagingReportStatus): void {
    this.toSignOnly.set(false);
    this.reportStatusFilter.set(status);
    this.loadReports();
  }

  onReportModalityChange(modality: string): void {
    this.reportModalityFilter.set(modality as ImagingModality | '');
  }

  toggleCriticalOnly(): void {
    this.criticalOnly.update((v) => !v);
  }

  readonly visibleReports = computed(() => {
    let list = this.reports();
    const modality = this.reportModalityFilter();
    if (modality) list = list.filter((r) => r.modality === modality);
    return this.criticalOnly() ? list.filter((r) => this.isCritical(r)) : list;
  });

  isCritical(report: ImagingReportResponse): boolean {
    return !!report.criticalResultFlaggedAt;
  }

  isCriticalUnacked(report: ImagingReportResponse): boolean {
    return !!report.criticalResultFlaggedAt && !report.criticalResultAcknowledgedAt;
  }

  openReport(report: ImagingReportResponse): void {
    this.selectedReport.set(report);
    this.loadVersions(report);
  }

  closeReport(): void {
    this.selectedReport.set(null);
    this.selectedReportVersions.set([]);
  }

  /** QA D2306 (r63): from the order drawer (or a prior study), open that report. */
  openReportById(reportId: string): void {
    this.reportLoading.set(true);
    this.imagingService.getReport(reportId).subscribe({
      next: (report) => {
        this.reportLoading.set(false);
        this.selectedOrder.set(null);
        this.openReport(report);
      },
      error: () => {
        this.reportLoading.set(false);
        this.toast.error(this.translate.instant('IMAGING.NO_REPORT_FOR_ORDER'));
      },
    });
  }

  /** QA D2307 (r63): the other versions of the report's study, read with it. */
  private loadVersions(report: ImagingReportResponse): void {
    this.selectedReportVersions.set([]);
    if (!report?.imagingOrderId) return;
    this.imagingService.getReportsForOrder(report.imagingOrderId)?.subscribe({
      next: (all) => {
        if (this.selectedReport()?.id !== report.id) return;
        this.selectedReportVersions.set(
          (all ?? [])
            .filter((r) => r.id !== report.id)
            .sort((a, b) => (a.reportVersion ?? 0) - (b.reportVersion ?? 0)),
        );
      },
      error: () => this.selectedReportVersions.set([]),
    });
  }

  /**
   * QA D2307 (r63): a signed report offered only « Fermer ». The addendum is a
   * new, separately signed version; the signed original is never edited.
   */
  canAddAddendum(report: ImagingReportResponse): boolean {
    return (
      this.canAuthorReports &&
      !!report.signed &&
      report.reportStatus !== 'CANCELLED' &&
      report.reportStatus !== 'ERROR' &&
      !this.reportOrderCancelled(report)
    );
  }

  openAddendum(report: ImagingReportResponse): void {
    this.addendumSource.set(report);
    this.writingAddendum.set(true);
    this.reportForm = {
      ...this.emptyReportForm(),
      reportTitle: report.reportTitle ?? '',
      modality: report.modality,
      bodyRegion: report.bodyRegion ?? '',
      accessionNumber: report.accessionNumber ?? '',
      studyInstanceUid: report.studyInstanceUid ?? '',
      reportStatus: 'ADDENDUM',
    };
    this.reportOrderId.set(report.imagingOrderId);
    this.reportEditId.set(null);
    this.reportPatient.set({
      name: report.patientDisplayName ?? null,
      mrn: report.patientMrn ?? null,
    });
    this.selectedReport.set(null);
    this.selectedReportVersions.set([]);
    this.showReportModal.set(true);
  }

  /** QA D2912 (r66): the study's exam record holds a contrast answer or a dose to show with the report. */
  reportHasExamRecord(report: ImagingReportResponse): boolean {
    const recorded = (value: unknown): boolean => value !== null && value !== undefined;
    return recorded(report.examContrastAdministered) || recorded(report.examRadiationDoseMgy);
  }

  /** QA D2642 (r65): when the study was done: the report's performed time, else the exam's start. */
  reportExamDate(report: ImagingReportResponse): string | null {
    return report.performedAt || report.examStartedAt || null;
  }

  /** r63: print the open report alone (the shared print isolation). */
  printReport(): void {
    const panel = this.host.nativeElement.querySelector<HTMLElement>(
      '[data-testid="imaging-report-panel"]',
    );
    if (panel) printOnly(panel);
  }

  /** Open the latest report for an order (from the orders table/detail). */
  viewReportForOrder(order: ImagingOrderResponse): void {
    this.reportLoading.set(true);
    this.imagingService.getLatestReportByOrder(order.id).subscribe({
      next: (report) => {
        this.reportLoading.set(false);
        this.selectedOrder.set(null);
        this.openReport(report);
      },
      error: () => {
        this.reportLoading.set(false);
        this.toast.error(this.translate.instant('IMAGING.NO_REPORT_FOR_ORDER'));
      },
    });
  }

  /** The server stamps the authenticated caller; no staff id travels. */
  acknowledgeCritical(report: ImagingReportResponse): void {
    this.acknowledging.set(true);
    this.imagingService.acknowledgeCriticalReport(report.id).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('IMAGING.CRITICAL_ACKED'));
        this.acknowledging.set(false);
        this.selectedReport.set(updated);
        this.loadReports();
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('IMAGING.CRITICAL_ACK_ERROR'),
        );
        this.acknowledging.set(false);
      },
    });
  }

  /* ── Authoring (Tier 2 item 26) ── */

  /**
   * Author a report against an order. A study that already carries a signed
   * read may only receive a revision, so the form opens pre-set to ADDENDUM
   * and the backend enforces the same rule.
   */
  openAuthorReport(order: ImagingOrderResponse, existingSigned = false): void {
    this.addendumSource.set(null);
    this.writingAddendum.set(existingSigned);
    this.reportForm = {
      reportTitle: order.studyType ?? '',
      modality: order.modality,
      bodyRegion: order.bodyRegion ?? '',
      accessionNumber: '',
      studyInstanceUid: '',
      technique: '',
      comparisonStudies: '',
      findings: '',
      impression: '',
      recommendations: '',
      // Policy rule 3 (provisional): the first read opens on the contrast the exam record says was
      // given, for its author to confirm or correct; the server stores only what the author submits.
      contrastAdministered: !existingSigned && order.examContrastAdministered === true,
      contrastDetails: existingSigned ? '' : (order.examContrastDetails ?? ''),
      criticalFinding: false,
      reportStatus: existingSigned ? 'ADDENDUM' : 'PRELIMINARY',
    };
    this.reportOrderId.set(order.id);
    this.reportEditId.set(null);
    this.reportPatient.set({
      name: order.patientDisplayName ?? null,
      mrn: order.patientMrn ?? null,
    });
    this.showReportModal.set(true);
  }

  /** Revise an unsigned draft. Signed reports are read-only by contract. */
  openEditReport(report: ImagingReportResponse): void {
    if (report.signed) {
      this.toast.error(this.translate.instant('IMAGING.REPORT_LOCKED'));
      return;
    }
    this.writingAddendum.set(false);
    this.addendumSource.set(null);
    this.reportForm = {
      reportTitle: report.reportTitle ?? '',
      modality: report.modality,
      bodyRegion: report.bodyRegion ?? '',
      accessionNumber: report.accessionNumber ?? '',
      studyInstanceUid: report.studyInstanceUid ?? '',
      technique: report.technique ?? '',
      comparisonStudies: report.comparisonStudies ?? '',
      findings: report.findings ?? '',
      impression: report.impression ?? '',
      recommendations: report.recommendations ?? '',
      contrastAdministered: !!report.contrastAdministered,
      // QA D1822 class: a revision sent the details blank and erased them.
      contrastDetails: report.contrastDetails ?? '',
      criticalFinding: report.criticalFinding,
      reportStatus: this.authorableStatusOf(report.reportStatus),
    };
    this.reportOrderId.set(report.imagingOrderId);
    this.reportEditId.set(report.id);
    this.reportPatient.set({
      name: report.patientDisplayName ?? null,
      mrn: report.patientMrn ?? null,
    });
    this.showReportModal.set(true);
  }

  closeReportModal(): void {
    this.addendumSource.set(null);
    this.writingAddendum.set(false);
    this.showReportModal.set(false);
    this.reportEditId.set(null);
    this.reportOrderId.set(null);
    this.reportPatient.set(null);
  }

  submitReport(): void {
    const orderId = this.reportOrderId();
    if (!orderId) return;
    const editId = this.reportEditId();
    const payload: ImagingReportAuthorRequest = {
      ...this.reportForm,
      bodyRegion: this.reportForm.bodyRegion.trim() || undefined,
      accessionNumber: this.reportForm.accessionNumber.trim() || undefined,
      studyInstanceUid: this.reportForm.studyInstanceUid.trim() || undefined,
      technique: this.reportForm.technique.trim() || undefined,
      comparisonStudies: this.reportForm.comparisonStudies.trim() || undefined,
      findings: this.reportForm.findings.trim() || undefined,
      impression: this.reportForm.impression.trim() || undefined,
      recommendations: this.reportForm.recommendations.trim() || undefined,
      contrastDetails: this.reportForm.contrastDetails.trim() || undefined,
      reportTitle: this.reportForm.reportTitle.trim() || undefined,
      imagingOrderId: orderId,
    };

    this.reportSubmitting.set(true);
    const request$ = editId
      ? this.imagingService.updateReport(editId, payload)
      : this.imagingService.createReport(payload);

    request$.subscribe({
      next: (saved) => {
        this.toast.success(
          this.translate.instant(editId ? 'IMAGING.REPORT_UPDATED' : 'IMAGING.REPORT_CREATED'),
        );
        this.reportSubmitting.set(false);
        this.closeReportModal();
        this.openReport(saved);
        this.loadReports();
        this.refreshToSignCount();
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('IMAGING.REPORT_SAVE_ERROR'));
        this.reportSubmitting.set(false);
      },
    });
  }

  /**
   * Sign the open report. Signing is irreversible and closes the report to
   * edits, so it asks first — the backend refuses a re-sign outright and the
   * only way back is a corrected version.
   */
  async signReport(report: ImagingReportResponse): Promise<void> {
    if (
      !(await this.confirmService.ask({
        titleKey: 'IMAGING.SIGN_CONFIRM',
        confirmKey: 'IMAGING.SIGN_REPORT',
      }))
    )
      return;
    this.signing.set(true);
    this.imagingService.signReport(report.id).subscribe({
      next: (signed) => {
        this.toast.success(this.translate.instant('IMAGING.REPORT_SIGNED'));
        this.signing.set(false);
        this.openReport(signed);
        this.loadReports();
        this.refreshToSignCount();
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('IMAGING.REPORT_SIGN_ERROR'));
        this.signing.set(false);
      },
    });
  }

  /**
   * The form only offers statuses an author may assert. A report already at
   * FINAL or a terminal state has no authorable equivalent, so it falls back
   * to PRELIMINARY rather than sending a value the backend will refuse.
   */
  private authorableStatusOf(status: ImagingReportStatus): AuthorableReportStatus {
    return (this.authorableStatuses as readonly string[]).includes(status)
      ? (status as AuthorableReportStatus)
      : 'PRELIMINARY';
  }

  /* ── Administrative void ── */

  openStatusUpdate(report: ImagingReportResponse): void {
    this.statusTarget.set(report);
    this.statusForm = { status: 'CANCELLED', statusReason: '' };
    this.showStatusModal.set(true);
  }

  closeStatusUpdate(): void {
    this.showStatusModal.set(false);
    this.statusTarget.set(null);
  }

  submitStatusUpdate(): void {
    const report = this.statusTarget();
    if (!report) return;
    const reason = this.statusForm.statusReason.trim();
    // Mirrors the backend rule rather than letting the server bounce it: a
    // voided radiology report with no account of why is a hole in the chart.
    if (!reason) {
      this.toast.error(this.translate.instant('IMAGING.VOID_REASON_REQUIRED'));
      return;
    }
    this.statusSubmitting.set(true);
    this.imagingService
      .updateReportStatus(report.id, {
        status: this.statusForm.status,
        statusReason: reason,
      })
      .subscribe({
        next: (updated) => {
          this.toast.success(this.translate.instant('IMAGING.STATUS_UPDATED'));
          this.statusSubmitting.set(false);
          this.closeStatusUpdate();
          if (this.selectedReport()?.id === updated.id) {
            this.selectedReport.set(updated);
          }
          this.loadReports();
        },
        error: (err: unknown) => {
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('IMAGING.STATUS_UPDATE_ERROR'),
          );
          this.statusSubmitting.set(false);
        },
      });
  }

  private emptyReportForm(): ReportForm {
    return {
      reportTitle: '',
      modality: 'XRAY',
      bodyRegion: '',
      accessionNumber: '',
      studyInstanceUid: '',
      technique: '',
      comparisonStudies: '',
      findings: '',
      impression: '',
      recommendations: '',
      contrastAdministered: false,
      contrastDetails: '',
      criticalFinding: false,
      reportStatus: 'PRELIMINARY',
    };
  }

  /**
   * QA D1098: a draft read on a cancelled order is neither revised nor signed
   * (the server refuses both); the order is looked up in the loaded list.
   */
  /**
   * V316: key images go on the study itself while it has no signed report, by
   * those who author its reports; after signing, they go on an addendum.
   */
  canAddStudyImages(order: ImagingOrderResponse): boolean {
    return (
      // Policy rule 3 (provisional): the imaging technician attaches the study's key images too.
      this.roleContext.hasAnyActiveRole(actionRoles('imagingImageUpload')) &&
      order.status !== 'CANCELLED' &&
      // QA D2914 (r66): a study with results has a signed report, whether or not this reader may
      // open it (a technician reads no report of an exam somebody else performed).
      order.status !== 'RESULTS_AVAILABLE' &&
      !this.selectedOrderReports().some((r) => r.signed)
    );
  }

  reportOrderCancelled(report: ImagingReportResponse): boolean {
    return this.orders().some((o) => o.id === report.imagingOrderId && o.status === 'CANCELLED');
  }

  /** An order can only be read once the study has actually been acquired. */
  canAuthorForOrder(order: ImagingOrderResponse): boolean {
    return (
      this.canAuthorReports &&
      (order.status === 'COMPLETED' ||
        order.status === 'RESULTS_AVAILABLE' ||
        order.status === 'IN_PROGRESS')
    );
  }

  getReportStatusClass(status: string): string {
    switch (status) {
      case 'FINAL':
      case 'ADDENDUM':
        return 'status-completed';
      case 'PRELIMINARY':
      case 'DRAFT':
        return 'status-preliminary';
      case 'CORRECTED':
      case 'AMENDED':
        return 'status-progress';
      case 'CANCELLED':
      case 'ERROR':
        return 'status-cancelled';
      default:
        return '';
    }
  }
}
