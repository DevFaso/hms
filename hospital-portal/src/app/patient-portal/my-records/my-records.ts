import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import {
  PatientPortalService,
  HealthSummaryDTO,
  PortalEncounter,
  LabResultSummary,
  MedicationSummary,
  ImmunizationSummary,
} from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-records',
  standalone: true,
  imports: [CommonModule, DatePipe],
  styleUrl: './my-records.scss',
  templateUrl: './my-records.html',
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

  tabs = [
    { key: 'overview', label: 'Overview', icon: 'person' },
    { key: 'encounters', label: 'Visits', icon: 'stethoscope' },
    { key: 'labs', label: 'Lab Results', icon: 'science' },
    { key: 'medications', label: 'Medications', icon: 'medication' },
    { key: 'immunizations', label: 'Immunizations', icon: 'vaccines' },
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
}
