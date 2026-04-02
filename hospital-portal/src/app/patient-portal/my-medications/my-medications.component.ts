import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  MedicationSummary,
  PortalPrescription,
} from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-medications',
  standalone: true,
  imports: [CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-medications.component.html',
  styleUrls: ['./my-medications.component.scss', '../patient-portal-pages.scss'],
})
export class MyMedicationsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  medications = signal<MedicationSummary[]>([]);
  prescriptions = signal<PortalPrescription[]>([]);
  loading = signal(true);
  expandedMedId = signal<string | null>(null);
  expandedRxId = signal<string | null>(null);

  ngOnInit() {
    let pending = 2;
    const done = () => {
      if (--pending <= 0) this.loading.set(false);
    };

    this.portal.getMyMedications().subscribe({
      next: (meds) => {
        this.medications.set(meds);
        done();
      },
      error: () => done(),
    });

    this.portal.getMyPrescriptions().subscribe({
      next: (rx) => {
        this.prescriptions.set(rx);
        done();
      },
      error: () => done(),
    });
  }

  toggleMed(id: string): void {
    this.expandedMedId.set(this.expandedMedId() === id ? null : id);
  }

  toggleRx(id: string): void {
    this.expandedRxId.set(this.expandedRxId() === id ? null : id);
  }
}
