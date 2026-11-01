import { LabValuePipe, LabRangePipe } from '../../../shared/pipes/lab-value.pipe';
import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { CfaPipe } from '../../../shared/pipes/cfa.pipe';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LabCorrectionBadgeComponent } from '../../../lab/shared/lab-correction-badge.component';
import {
  PatientPortalService,
  PortalAppointment,
  MedicationSummary,
  PortalInvoice,
  HealthSummaryDTO,
  ProxyResponse,
} from '../../../services/patient-portal.service';
import { EnumLabelPipe } from '../../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../../shared/pipes/server-time.pipe';
import { refusalReason } from '../../../shared/refusal-reason';
import { LabResultPager } from '../../lab-result-pager';
import { ClockTimePipe } from '../../../shared/pipes/clock-time.pipe';

export type ProxyTabKey = 'appointments' | 'medications' | 'labResults' | 'billing' | 'records';

interface ProxyTab {
  key: ProxyTabKey;
  scope: string;
  icon: string;
  labelKey: string;
}

/** Legacy rows may still carry the un-prefixed scope vocabulary. */
const LEGACY_SCOPE_ALIASES: Record<string, string> = {
  APPOINTMENTS: 'VIEW_APPOINTMENTS',
  MEDICATIONS: 'VIEW_MEDICATIONS',
  LAB_RESULTS: 'VIEW_LAB_RESULTS',
  BILLING: 'VIEW_BILLING',
  RECORDS: 'VIEW_RECORDS',
};

function normalizeScopes(csv: string | null | undefined): Set<string> {
  if (!csv) return new Set();
  return new Set(
    csv
      .toUpperCase()
      .split(',')
      .map((t) => t.trim())
      .filter((t) => t.length > 0)
      .map((t) => LEGACY_SCOPE_ALIASES[t] ?? t),
  );
}

@Component({
  selector: 'app-proxy-data-viewer',
  standalone: true,
  imports: [
    LabValuePipe,
    LabRangePipe,
    LabCorrectionBadgeComponent,
    ClockTimePipe,
    ServerTimePipe,
    CommonModule,
    DatePipe,
    RouterLink,
    EnumLabelPipe,
    TranslateModule,
    CfaPipe,
  ],
  templateUrl: './proxy-data-viewer.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./proxy-data-viewer.component.scss', '../../patient-portal-pages.scss'],
})
export class ProxyDataViewerComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly route = inject(ActivatedRoute);

  readonly allTabs: ProxyTab[] = [
    {
      key: 'appointments',
      scope: 'VIEW_APPOINTMENTS',
      icon: 'calendar_month',
      labelKey: 'PORTAL.FAMILY.VIEWER.APPOINTMENTS_TAB',
    },
    {
      key: 'medications',
      scope: 'VIEW_MEDICATIONS',
      icon: 'medication',
      labelKey: 'PORTAL.FAMILY.VIEWER.MEDICATIONS_TAB',
    },
    {
      key: 'labResults',
      scope: 'VIEW_LAB_RESULTS',
      icon: 'labs',
      labelKey: 'PORTAL.FAMILY.VIEWER.LAB_RESULTS_TAB',
    },
    {
      key: 'billing',
      scope: 'VIEW_BILLING',
      icon: 'receipt_long',
      labelKey: 'PORTAL.FAMILY.VIEWER.BILLING_TAB',
    },
    {
      key: 'records',
      scope: 'VIEW_RECORDS',
      icon: 'clinical_notes',
      labelKey: 'PORTAL.FAMILY.VIEWER.RECORDS_TAB',
    },
  ];

  patientId = '';
  grant = signal<ProxyResponse | null>(null);
  grantLoading = signal(true);
  grantMissing = signal(false);
  /**
   * QA D1212 (round 23): the grants could not be read. Shown as a failure
   * with the server's reason, not as "no access" (which it is not).
   */
  grantFailed = signal(false);
  tabs = signal<ProxyTab[]>([]);
  activeTab = signal<ProxyTabKey | null>(null);

  tabLoading = signal(false);
  tabError = signal(false);
  /**
   * QA D1212 (round 23): the server's reason a tab could not be read (a 4xx:
   * the grant expired, the record is not reachable). A proxy saw only
   * "Impossible de charger cette section" for a 404 that said why.
   */
  failureReason = signal<string | null>(null);

  appointments = signal<PortalAppointment[]>([]);
  medications = signal<MedicationSummary[]>([]);
  /** QA D1213 (sibling): the grantor's lab results, page by page, as on /my-lab-results. */
  readonly labPager = new LabResultPager((offset) =>
    this.portal.getProxyLabResultsPage(this.patientId, offset),
  );
  readonly labResults = this.labPager.results;
  /** The server's reason a later Labs page failed, when it gave one. */
  readonly labMoreFailureReason = this.labPager.failureReason;
  invoices = signal<PortalInvoice[]>([]);
  records = signal<HealthSummaryDTO | null>(null);

  private readonly loadedTabs = new Set<ProxyTabKey>();

  ngOnInit(): void {
    this.patientId = this.route.snapshot.paramMap.get('patientId') ?? '';
    this.portal.getMyProxyAccess().subscribe({
      next: (grants) => {
        const grant = grants.find((g) => g.grantorPatientId === this.patientId) ?? null;
        this.grant.set(grant);
        this.grantLoading.set(false);
        if (!grant) {
          this.grantMissing.set(true);
          return;
        }
        const scopes = normalizeScopes(grant.permissions);
        const visible = scopes.has('ALL')
          ? this.allTabs
          : this.allTabs.filter((t) => scopes.has(t.scope));
        this.tabs.set(visible);
        if (visible.length > 0) {
          this.selectTab(visible[0].key);
        }
      },
      error: (err: unknown) => {
        this.grantLoading.set(false);
        this.failureReason.set(refusalReason(err));
        this.grantFailed.set(true);
      },
    });
  }

  selectTab(key: ProxyTabKey): void {
    this.activeTab.set(key);
    this.tabError.set(false);
    this.failureReason.set(null);
    if (this.loadedTabs.has(key)) return;
    this.tabLoading.set(true);

    const done = <T>(setter: (v: T) => void) => ({
      next: (v: T) => {
        setter(v);
        this.loadedTabs.add(key);
        this.tabLoading.set(false);
      },
      error: (err: unknown) => {
        this.failureReason.set(refusalReason(err));
        this.tabLoading.set(false);
        this.tabError.set(true);
      },
    });

    switch (key) {
      case 'appointments':
        this.portal
          .getProxyAppointments(this.patientId)
          .subscribe(done((v) => this.appointments.set(v)));
        break;
      case 'medications':
        this.portal
          .getProxyMedications(this.patientId)
          .subscribe(done((v) => this.medications.set(v)));
        break;
      case 'labResults':
        this.loadLabs();
        break;
      case 'billing':
        this.portal.getProxyBilling(this.patientId).subscribe(done((v) => this.invoices.set(v)));
        break;
      case 'records':
        this.portal.getProxyRecords(this.patientId).subscribe(done((v) => this.records.set(v)));
        break;
    }
  }

  /**
   * The Labs tab reads through its pager; its first page decides the tab's
   * loading and error state like the other tabs.
   */
  private loadLabs(): void {
    this.labPager.reload(
      () => {
        this.loadedTabs.add('labResults');
        this.tabLoading.set(false);
      },
      () => {
        this.failureReason.set(this.labPager.failureReason());
        this.tabLoading.set(false);
        this.tabError.set(true);
      },
    );
  }

  /** Shows the grantor's older results. */
  loadMoreLabs(): void {
    this.labPager.loadMore();
  }

  retryActiveTab(): void {
    const key = this.activeTab();
    if (key) this.selectTab(key);
  }
}
