import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  HealthSummaryDTO,
  PortalEncounter,
  LabResultSummary,
  MedicationSummary,
  ImmunizationSummary,
} from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-records',
  standalone: true,
  imports: [CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-records.component.html',
  styleUrls: ['./my-records.component.scss', '../patient-portal-pages.scss'],
})
export class MyRecordsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  loading = signal(true);
  summary = signal<HealthSummaryDTO | null>(null);
  encounters = signal<PortalEncounter[]>([]);
  labs = signal<LabResultSummary[]>([]);
  medications = signal<MedicationSummary[]>([]);
  immunizations = signal<ImmunizationSummary[]>([]);
  activeTab = signal<string>('overview');
  expandedId = signal<string | null>(null);

  tabs = [
    { key: 'overview', labelKey: 'PORTAL.RECORDS.OVERVIEW', icon: 'person' },
    { key: 'encounters', labelKey: 'PORTAL.RECORDS.VISITS_TAB', icon: 'stethoscope' },
    { key: 'labs', labelKey: 'PORTAL.RECORDS.LABS_TAB', icon: 'science' },
    { key: 'medications', labelKey: 'PORTAL.RECORDS.MEDICATIONS_TAB', icon: 'medication' },
    { key: 'immunizations', labelKey: 'PORTAL.RECORDS.IMMUNIZATIONS_TAB', icon: 'vaccines' },
  ];

  ngOnInit(): void {
    this.portal.getHealthSummary().subscribe({
      next: (s) => this.summary.set(s),
      error: () => void 0,
    });
    this.portal.getMyEncounters().subscribe({
      next: (e) => this.encounters.set(e),
      error: () => void 0,
    });
    this.portal.getMyLabResults(50).subscribe({
      next: (l) => this.labs.set(l),
      error: () => void 0,
    });
    this.portal.getMyMedications().subscribe({
      next: (m) => this.medications.set(m),
      error: () => void 0,
    });
    this.portal.getMyImmunizations().subscribe({
      next: (im) => {
        this.immunizations.set(im);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  printRecords(): void {
    window.print();
  }

  toggleExpand(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }
}
