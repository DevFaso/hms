import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { RecordSharingService, PatientRecord } from '../../services/record-sharing.service';

type RecordTab =
  | 'encounters'
  | 'treatments'
  | 'prescriptions'
  | 'labOrders'
  | 'labResults'
  | 'allergies'
  | 'problems'
  | 'surgicalHistory'
  | 'advanceDirectives'
  | 'vitalSigns'
  | 'immunizations'
  | 'insurances'
  | 'encounterHistory';

interface TabDef {
  key: RecordTab;
  icon: string;
  labelKey: string;
}

const TABS: TabDef[] = [
  { key: 'encounters', icon: 'local_hospital', labelKey: 'SHARED_RECORDS.TABS.ENCOUNTERS' },
  { key: 'treatments', icon: 'healing', labelKey: 'SHARED_RECORDS.TABS.TREATMENTS' },
  { key: 'prescriptions', icon: 'medication', labelKey: 'SHARED_RECORDS.TABS.PRESCRIPTIONS' },
  { key: 'labOrders', icon: 'biotech', labelKey: 'SHARED_RECORDS.TABS.LAB_ORDERS' },
  { key: 'labResults', icon: 'science', labelKey: 'SHARED_RECORDS.TABS.LAB_RESULTS' },
  { key: 'vitalSigns', icon: 'monitor_heart', labelKey: 'SHARED_RECORDS.TABS.VITAL_SIGNS' },
  { key: 'immunizations', icon: 'vaccines', labelKey: 'SHARED_RECORDS.TABS.IMMUNIZATIONS' },
  { key: 'allergies', icon: 'warning', labelKey: 'SHARED_RECORDS.TABS.ALLERGIES' },
  { key: 'problems', icon: 'health_and_safety', labelKey: 'SHARED_RECORDS.TABS.PROBLEMS' },
  { key: 'surgicalHistory', icon: 'surgical', labelKey: 'SHARED_RECORDS.TABS.SURGICAL_HISTORY' },
  {
    key: 'advanceDirectives',
    icon: 'description',
    labelKey: 'SHARED_RECORDS.TABS.ADVANCE_DIRECTIVES',
  },
  { key: 'insurances', icon: 'shield', labelKey: 'SHARED_RECORDS.TABS.INSURANCES' },
  {
    key: 'encounterHistory',
    icon: 'history',
    labelKey: 'SHARED_RECORDS.TABS.ENCOUNTER_HISTORY',
  },
];

@Component({
  selector: 'app-shared-records-viewer',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './shared-records-viewer.component.html',
  styleUrl: './shared-records-viewer.component.scss',
})
export class SharedRecordsViewerComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly sharingService = inject(RecordSharingService);

  readonly tabs = TABS;
  activeTab = signal<RecordTab>('encounters');
  record = signal<PatientRecord | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);

  patientName = computed(() => {
    const r = this.record();
    if (!r) return '';
    return [r.firstName, r.middleName, r.lastName].filter(Boolean).join(' ');
  });

  patientInitials = computed(() => {
    const r = this.record();
    if (!r) return '?';
    return ((r.firstName?.[0] ?? '') + (r.lastName?.[0] ?? '')).toUpperCase() || '?';
  });

  tabCounts = computed(() => {
    const r = this.record();
    if (!r) return {} as Record<RecordTab, number>;
    return {
      encounters: r.encounters?.length ?? 0,
      treatments: r.treatments?.length ?? 0,
      prescriptions: r.prescriptions?.length ?? 0,
      labOrders: r.labOrders?.length ?? 0,
      labResults: r.labResults?.length ?? 0,
      allergies: (r.allergiesDetailed?.length ?? 0) || (r.allergies ? 1 : 0),
      problems: r.problems?.length ?? 0,
      surgicalHistory: r.surgicalHistory?.length ?? 0,
      advanceDirectives: r.advanceDirectives?.length ?? 0,
      vitalSigns: r.vitalSigns?.length ?? 0,
      immunizations: r.immunizations?.length ?? 0,
      insurances: r.insurances?.length ?? 0,
      encounterHistory: r.encounterHistory?.length ?? 0,
    } as Record<RecordTab, number>;
  });

  ngOnInit(): void {
    const patientId = this.route.snapshot.queryParamMap.get('patientId');
    const toHospitalId = this.route.snapshot.queryParamMap.get('toHospitalId');

    if (!patientId || !toHospitalId) {
      this.error.set('Missing required parameters');
      this.loading.set(false);
      return;
    }

    this.sharingService.getAggregatedRecord(patientId, toHospitalId).subscribe({
      next: (data) => {
        this.record.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('SHARED_RECORDS.ERRORS.LOAD_FAILED');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: RecordTab): void {
    this.activeTab.set(tab);
  }

  goBack(): void {
    this.router.navigate(['/consent-management']);
  }

  severityClass(severity: string | undefined): string {
    if (!severity) return '';
    switch (severity.toUpperCase()) {
      case 'SEVERE':
      case 'HIGH':
      case 'CRITICAL':
        return 'severity-high';
      case 'MODERATE':
      case 'MEDIUM':
        return 'severity-medium';
      case 'MILD':
      case 'LOW':
        return 'severity-low';
      default:
        return '';
    }
  }

  statusClass(status: string | undefined): string {
    if (!status) return '';
    switch (status.toUpperCase()) {
      case 'COMPLETED':
      case 'ACTIVE':
      case 'RELEASED':
      case 'VERIFIED':
        return 'status-success';
      case 'IN_PROGRESS':
      case 'PENDING':
      case 'ORDERED':
        return 'status-pending';
      case 'CANCELLED':
      case 'RESOLVED':
      case 'INACTIVE':
        return 'status-neutral';
      default:
        return '';
    }
  }
}
