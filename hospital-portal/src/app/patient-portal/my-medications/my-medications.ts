import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import {
  PatientPortalService,
  MedicationSummary,
  PortalPrescription,
} from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-medications',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-medications.html',
  styleUrl: './my-medications.scss',
})
export class MyMedicationsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  medications = signal<MedicationSummary[]>([]);
  prescriptions = signal<PortalPrescription[]>([]);
  loading = signal(true);

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
}
