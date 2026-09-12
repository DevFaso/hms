import { Component, inject, OnInit, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { PatientService, PatientResponse } from '../services/patient.service';
import { VitalSignService, VitalSignResponse } from '../services/vital-sign.service';
import { EncounterService, EncounterResponse } from '../services/encounter.service';
import { AppointmentService, AppointmentResponse } from '../services/appointment.service';
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { PatientChartComponent } from './patient-chart/patient-chart.component';
import {
  APPOINTMENT_VIEW_ROLES,
  CHART_REVIEW_VIEW_ROLES,
  CHART_VIEW_ROLES,
  ENCOUNTER_VIEW_ROLES,
  VITALS_VIEW_ROLES,
} from './patient-chart/chart-access';
import { AdvanceDirectivesTabComponent } from './advance-directives/advance-directives-tab.component';
import { DIRECTIVE_ROLES } from './advance-directives/directive-access';
import { GrowthChartTabComponent } from './growth-chart/growth-chart-tab.component';
import { FluidBalanceTabComponent } from './fluid-balance/fluid-balance-tab.component';
import { MicroTabComponent } from './micro/micro-tab.component';
import { PatientPhotoComponent } from './patient-photo/patient-photo.component';
import { PrintLabelService } from '../services/print-label.service';
import { CoverageTabComponent } from './coverage-tab/coverage-tab.component';
import { DocumentsTabComponent } from './documents-tab/documents-tab.component';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { MedicalHistoryTabComponent } from './medical-history-tab/medical-history-tab.component';
import { BpaPanelComponent } from './bpa-panel/bpa-panel.component';
import { StoryboardBannerComponent } from './storyboard-banner/storyboard-banner.component';
import { ChartReviewComponent } from './chart-review/chart-review.component';
import { BreakGlassBannerComponent } from './break-glass-banner/break-glass-banner.component';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

type TabKey =
  | 'overview'
  | 'medical'
  | 'chart'
  | 'chart-review'
  | 'coverage'
  | 'med-history'
  | 'vitals'
  | 'growth'
  | 'fluid-balance'
  | 'micro'
  | 'encounters'
  | 'appointments'
  | 'directives'
  | 'documents';

@Component({
  selector: 'app-patient-detail',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    PatientChartComponent,
    AdvanceDirectivesTabComponent,
    GrowthChartTabComponent,
    FluidBalanceTabComponent,
    MicroTabComponent,
    PatientPhotoComponent,
    CoverageTabComponent,
    DocumentsTabComponent,
    HospitalScopeChipComponent,
    MedicalHistoryTabComponent,
    BpaPanelComponent,
    StoryboardBannerComponent,
    ChartReviewComponent,
    BreakGlassBannerComponent,
    EnumLabelPipe,
  ],
  templateUrl: './patient-detail.html',
  styleUrl: './patient-detail.scss',
})
export class PatientDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly patientService = inject(PatientService);
  private readonly vitalService = inject(VitalSignService);
  private readonly encounterService = inject(EncounterService);
  private readonly appointmentService = inject(AppointmentService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  protected readonly permissions = inject(PermissionService);
  private readonly roleContext = inject(RoleContextService);
  private readonly printService = inject(PrintLabelService);

  patient = signal<PatientResponse | null>(null);
  loading = signal(true);
  activeTab = signal<TabKey>('overview');

  /* Sub-tab data */
  vitals = signal<VitalSignResponse[]>([]);
  vitalsLoading = signal(false);
  encounters = signal<EncounterResponse[]>([]);
  encountersLoading = signal(false);
  appointments = signal<AppointmentResponse[]>([]);
  appointmentsLoading = signal(false);

  private patientId = '';

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.router.navigate(['/patients']);
      return;
    }
    this.patientId = id;
    this.loadPatient(id);
  }

  /** Active hospital for the caller. Null when no scope is selected (e.g. SUPER_ADMIN unscoped view). */
  currentHospitalId(): string | null {
    return this.roleContext.activeHospitalId ?? null;
  }

  /** E9 #64 — the break-glass banner, so a restricted line can open its declaration. */
  private readonly breakGlass = viewChild(BreakGlassBannerComponent);

  /**
   * E9 #64 — bumped when a break-the-glass session is declared or ends. The
   * storyboard and the chart take it as an input and re-read on it: what
   * they withhold depends on the session.
   */
  readonly accessEpoch = signal(0);

  /** "Ouvrir avec motif" on any restricted line: one declaration, one reason. */
  openRestrictedRows(): void {
    this.breakGlass()?.openDeclare();
  }

  onBreakGlassSessionChanged(): void {
    this.accessEpoch.update((n) => n + 1);
  }

  /**
   * The hospital this page's requests are scoped to: the hospital the user
   * actually picked, not `activeHospitalId`, which is the JWT primary and stays
   * non-null even when the chip reads "All hospitals".
   *
   * Sending the primary here is what let the chart header and the storyboard
   * answer about one hospital while the FHIR export — which reads the
   * X-Hospital-Id the interceptor derives from this same signal — answered
   * about another, on one page, for one patient.
   */
  scopedHospitalId(): string | null {
    return this.roleContext.effectiveHospitalIdForRequest() ?? null;
  }

  loadPatient(id: string): void {
    this.loading.set(true);
    const hospitalId = this.scopedHospitalId() ?? undefined;
    this.patientService.getById(id, hospitalId).subscribe({
      next: (p) => {
        this.patient.set(p);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Patient not found');
        this.loading.set(false);
        this.router.navigate(['/patients']);
      },
    });
  }

  /** Mirrors PatientVitalSignController's READ list.
   *
   *  Was hasPermission('Update Vital Signs') — a WRITE permission gating a
   *  READ tab, which is exactly the trap canViewGrowth() below documents. It
   *  hid the vitals tab from every read-only role, including the consulting
   *  clinicians the backend admits since audit decision D7. */
  canViewVitals(): boolean {
    return this.roleContext.hasAnyActiveRole(VITALS_VIEW_ROLES);
  }

  /** Mirrors EncounterController's list READ gate — likewise not the
   *  'Create Encounters' write permission it used to check. */
  canViewEncounters(): boolean {
    return this.roleContext.hasAnyActiveRole(ENCOUNTER_VIEW_ROLES);
  }
  /** Mirrors AppointmentController's per-patient READ gate (E9 #69). */
  canViewAppointments(): boolean {
    return this.roleContext.hasAnyActiveRole(APPOINTMENT_VIEW_ROLES);
  }

  /** Chart Review is its own backend (ChartReviewController) with its own,
   *  broader list — the lab and pharmacy roles read the longitudinal record
   *  without being able to open encounters. Gating both tabs on one flag hid
   *  that difference. */
  canViewChartReview(): boolean {
    return this.roleContext.hasAnyActiveRole(CHART_REVIEW_VIEW_ROLES);
  }

  /** Mirrors GrowthChartController's @PreAuthorize list exactly — NOT the
   *  'Update Vital Signs' write permission the vitals tab uses, because a
   *  read-only chart gated on a write permission locks out read-only roles. */
  canViewGrowth(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_DOCTOR',
      'ROLE_SUPER_ADMIN',
    ]);
  }

  /** Mirrors IntakeOutputController's @PreAuthorize list exactly. */
  canViewFluidBalance(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_DOCTOR',
      'ROLE_SUPER_ADMIN',
    ]);
  }

  /* ── Address history (Tier 2 #38) ── */
  addressHistoryLoaded = signal(false);
  addressHistory = signal<import('../services/patient.service').PatientAddressHistoryEntry[]>([]);

  loadAddressHistory(): void {
    this.patientService.addressHistory(this.patientId).subscribe({
      next: (entries) => {
        this.addressHistory.set(entries);
        this.addressHistoryLoaded.set(true);
      },
      error: () => this.toast.error(this.translate.instant('PATIENTS.ADDRESS_HISTORY_FAILED')),
    });
  }

  /* ── FHIR record download (Tier 2 #44) ── */
  recordDownloadLoading = signal(false);

  /** Mirrors PatientRecordExportController.EXPORT_ROLES exactly — a full
   *  record export is a wider disclosure than a chart tab, so the
   *  receptionist chart roles do not carry over. */
  canDownloadRecord(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_DOCTOR',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_SUPER_ADMIN',
    ]);
  }

  downloadRecord(): void {
    if (this.recordDownloadLoading()) return;
    this.recordDownloadLoading.set(true);
    this.patientService.downloadFhirRecord(this.patientId).subscribe({
      next: (blob) => {
        this.recordDownloadLoading.set(false);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `patient-record-${this.patientId}.json`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: (err: HttpErrorResponse) => {
        this.recordDownloadLoading.set(false);
        this.reportDownloadFailure(err);
      },
    });
  }

  /**
   * The request is a `blob` download, so an error body arrives as a Blob and
   * never as parsed JSON — reading `err.error.message` yields undefined and
   * the operator is left with "could not be downloaded" for every cause.
   * The overwhelmingly common failure is a scope mismatch (the chart page
   * lets a super admin open any patient, while the export stays scoped to
   * the active hospital), and that one has a remedy the user can act on:
   * switch hospital with the scope picker.
   */
  private reportDownloadFailure(err: HttpErrorResponse): void {
    const fallback =
      err.status === 404
        ? 'PATIENTS.DOWNLOAD_RECORD_WRONG_HOSPITAL'
        : 'PATIENTS.DOWNLOAD_RECORD_FAILED';
    if (!(err.error instanceof Blob)) {
      this.toast.error(this.translate.instant(fallback));
      return;
    }
    err.error
      .text()
      .then((text) => {
        // Show the server's own wording when it sent one; it names the
        // remedy. Anything unparseable falls back to the translated key.
        let message = '';
        try {
          message = (JSON.parse(text) as { message?: string }).message ?? '';
        } catch {
          message = '';
        }
        this.toast.error(message || this.translate.instant(fallback));
      })
      .catch(() => this.toast.error(this.translate.instant(fallback)));
  }

  /* ── Wristband printing (P3 #23b) ── */
  wristbandLoading = signal(false);

  /** Mirrors PrintLabelController.WRISTBAND_ROLES exactly. */
  canPrintWristband(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_RECEPTIONIST',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_DOCTOR',
      'ROLE_SUPER_ADMIN',
    ]);
  }

  printWristband(): void {
    if (this.wristbandLoading()) return;
    this.wristbandLoading.set(true);
    this.printService.getWristbandPdf(this.patientId).subscribe({
      next: (blob) => {
        this.wristbandLoading.set(false);
        this.printService.openForPrint(blob);
      },
      error: () => {
        this.wristbandLoading.set(false);
        this.toast.error('Failed to generate the wristband');
      },
    });
  }

  /** Mirrors PatientPhotoController.WRITE_ROLES exactly (P3 #21). */
  canEditPhoto(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_RECEPTIONIST',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_DOCTOR',
      'ROLE_SUPER_ADMIN',
    ]);
  }

  /** Mirrors PatientMicroCultureController's @PreAuthorize list exactly —
   *  PHARMACIST included deliberately (susceptibilities drive stewardship). */
  canViewMicro(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_DOCTOR',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_SUPER_ADMIN',
      'ROLE_LAB_SCIENTIST',
      'ROLE_LAB_TECHNICIAN',
      'ROLE_LAB_MANAGER',
      'ROLE_LAB_DIRECTOR',
      'ROLE_QUALITY_MANAGER',
      'ROLE_PHARMACIST',
    ]);
  }

  /** Growth charts are a pediatric surface (WHO/CDC curves stop at 19); the
   *  tab hides for adults rather than rendering a lifetime weight log. */
  showGrowthTab(): boolean {
    if (!this.canViewGrowth()) return false;
    const dob = this.patient()?.dateOfBirth;
    if (!dob) return false;
    const ageMs = Date.now() - new Date(dob).getTime();
    return ageMs >= 0 && ageMs < 20 * 365.25 * 24 * 3600 * 1000;
  }

  /** Whether the current user can view the structured Chart tab (roles that can
   *  access at least one of allergies / diagnoses / chart updates). */
  canViewChart(): boolean {
    return this.roleContext.hasAnyActiveRole(CHART_VIEW_ROLES);
  }

  /** Insurance endpoints grant HOSPITAL_ADMIN/RECEPTIONIST/NURSE/DOCTOR only
   *  (no SUPER_ADMIN on the backend), so the Coverage tab mirrors that. */
  canViewCoverage(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_RECEPTIONIST',
      'ROLE_NURSE',
      'ROLE_DOCTOR',
    ]);
  }

  /**
   * The documents tab is hospital-pinned (the backend reads through the
   * hospital the patient is registered at). The chart has no scope selector
   * of its own, so the tab hosts the cross-tenant chip and forwards the
   * selection; a change re-fetches under the new X-Hospital-Id.
   */
  readonly documentsScope = signal<string | null>(null);

  onDocumentsScopeChange(hospitalId: string | null): void {
    this.documentsScope.set(hospitalId);
  }

  /** Mirrors PatientDocumentStaffController.READ_ROLES: the roles that read a
   *  chart, no lab roles, never ROLE_PATIENT (their reads stay on /me). */
  canViewDocuments(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_DOCTOR',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_PHARMACIST',
      'ROLE_RECEPTIONIST',
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_SUPER_ADMIN',
    ]);
  }

  /** Backend reads on /medical-history exclude admins (they may only delete). */
  canViewMedHistory(): boolean {
    return this.roleContext.hasAnyActiveRole([
      'ROLE_DOCTOR',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_LAB_SCIENTIST',
      'ROLE_PHARMACIST',
    ]);
  }

  /** Mirrors AdvanceDirectiveController.CLINICAL_ROLES via DIRECTIVE_ROLES. */
  canViewDirectives(): boolean {
    return this.roleContext.hasAnyActiveRole(DIRECTIVE_ROLES);
  }

  setTab(tab: TabKey): void {
    this.activeTab.set(tab);
    if (tab === 'vitals' && this.canViewVitals() && this.vitals().length === 0) this.loadVitals();
    if (tab === 'encounters' && this.canViewEncounters() && this.encounters().length === 0)
      this.loadEncounters();
    if (tab === 'appointments' && this.appointments().length === 0) this.loadAppointments();
  }

  private loadVitals(): void {
    this.vitalsLoading.set(true);
    this.vitalService.getRecent(this.patientId).subscribe({
      next: (v) => {
        this.vitals.set(v);
        this.vitalsLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load vitals');
        this.vitalsLoading.set(false);
      },
    });
  }

  private loadEncounters(): void {
    this.encountersLoading.set(true);
    this.encounterService.list({ patientId: this.patientId }).subscribe({
      next: (e) => {
        this.encounters.set(e);
        this.encountersLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load encounters');
        this.encountersLoading.set(false);
      },
    });
  }

  private loadAppointments(): void {
    this.appointmentsLoading.set(true);
    this.appointmentService.list({ patientId: this.patientId }).subscribe({
      next: (a) => {
        this.appointments.set(a);
        this.appointmentsLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load appointments');
        this.appointmentsLoading.set(false);
      },
    });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  getInitials(p: PatientResponse): string {
    return `${p.firstName?.charAt(0) ?? ''}${p.lastName?.charAt(0) ?? ''}`.toUpperCase();
  }

  getAge(dob?: string): string {
    if (!dob) return '—';
    const birth = new Date(dob);
    const now = new Date();
    let age = now.getFullYear() - birth.getFullYear();
    if (
      now.getMonth() < birth.getMonth() ||
      (now.getMonth() === birth.getMonth() && now.getDate() < birth.getDate())
    ) {
      age--;
    }
    return `${age} years`;
  }

  /** True for a real measurement, including 0 — but not null/undefined.
   *  (A missing JSON key parses to undefined, which `=== null` misses; that
   *  exact gap once hid this tab's wire mismatch behind "sparse data".) */
  hasValue(value: number | null | undefined): boolean {
    return value !== null && value !== undefined;
  }

  hasNoMetrics(v: VitalSignResponse): boolean {
    return [
      v.heartRateBpm,
      v.systolicBpMmHg,
      v.diastolicBpMmHg,
      v.temperatureCelsius,
      v.spo2Percent,
      v.respiratoryRateBpm,
      v.bloodGlucoseMgDl,
      v.weightKg,
      v.heightCm,
      v.headCircumferenceCm,
    ].every((value) => !this.hasValue(value));
  }

  getEncounterStatusClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
        return 'status-badge completed';
      case 'SCHEDULED':
      case 'ARRIVED':
      case 'IN_PROGRESS':
        return 'status-badge scheduled';
      case 'CANCELLED':
        return 'status-badge cancelled';
      default:
        return 'status-badge';
    }
  }

  getApptStatusClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
        return 'status-badge completed';
      case 'SCHEDULED':
      case 'CONFIRMED':
        return 'status-badge scheduled';
      case 'CANCELLED':
      case 'NO_SHOW':
        return 'status-badge cancelled';
      case 'IN_PROGRESS':
        return 'status-badge in-progress';
      default:
        return 'status-badge';
    }
  }
}
