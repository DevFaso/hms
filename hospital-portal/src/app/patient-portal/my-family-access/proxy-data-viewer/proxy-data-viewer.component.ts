import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  PortalAppointment,
  MedicationSummary,
  LabResultSummary,
  PortalInvoice,
  HealthSummaryDTO,
  ProxyResponse,
} from '../../../services/patient-portal.service';
import { EnumLabelPipe } from '../../../shared/pipes/enum-label.pipe';

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
  imports: [CommonModule, DatePipe, RouterLink, EnumLabelPipe, TranslateModule],
  templateUrl: './proxy-data-viewer.component.html',
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
  tabs = signal<ProxyTab[]>([]);
  activeTab = signal<ProxyTabKey | null>(null);

  tabLoading = signal(false);
  tabError = signal(false);

  appointments = signal<PortalAppointment[]>([]);
  medications = signal<MedicationSummary[]>([]);
  labResults = signal<LabResultSummary[]>([]);
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
      error: () => {
        this.grantLoading.set(false);
        this.grantMissing.set(true);
      },
    });
  }

  selectTab(key: ProxyTabKey): void {
    this.activeTab.set(key);
    this.tabError.set(false);
    if (this.loadedTabs.has(key)) return;
    this.tabLoading.set(true);

    const done = <T>(setter: (v: T) => void) => ({
      next: (v: T) => {
        setter(v);
        this.loadedTabs.add(key);
        this.tabLoading.set(false);
      },
      error: () => {
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
        this.portal
          .getProxyLabResults(this.patientId)
          .subscribe(done((v) => this.labResults.set(v)));
        break;
      case 'billing':
        this.portal.getProxyBilling(this.patientId).subscribe(done((v) => this.invoices.set(v)));
        break;
      case 'records':
        this.portal.getProxyRecords(this.patientId).subscribe(done((v) => this.records.set(v)));
        break;
    }
  }

  retryActiveTab(): void {
    const key = this.activeTab();
    if (key) this.selectTab(key);
  }
}
