import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import {
  PatientPortalService,
  MedicationSummary,
  PortalPrescription,
} from '../services/patient-portal.service';

@Component({
  selector: 'app-my-medications',
  standalone: true,
  imports: [CommonModule, DatePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">medication</span>
          My Medications
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading medications...</p>
        </div>
      } @else if (medications().length === 0 && prescriptions().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">medication</span>
          <h3>No medications found</h3>
          <p>You don't have any active medications.</p>
        </div>
      } @else {
        @if (medications().length > 0) {
          <section class="portal-section">
            <h2 class="portal-section-title">Active Medications</h2>
            <div class="portal-list">
              @for (med of medications(); track med.id) {
                <div class="portal-list-item">
                  <div class="pli-icon" style="background:#ccfbf1;color:#0d9488">
                    <span class="material-symbols-outlined">pill</span>
                  </div>
                  <div class="pli-body">
                    <span class="pli-title">{{ med.name }}</span>
                    <span class="pli-sub">{{ med.dosage }} · {{ med.frequency }}</span>
                    <span class="pli-meta"
                      >Prescribed by {{ med.prescribedBy || 'Provider' }} · Started
                      {{ med.startDate | date: 'MMM d, yyyy' }}</span
                    >
                  </div>
                </div>
              }
            </div>
          </section>
        }

        @if (prescriptions().length > 0) {
          <section class="portal-section">
            <h2 class="portal-section-title">Prescriptions</h2>
            <div class="portal-list">
              @for (rx of prescriptions(); track rx.id) {
                <div class="portal-list-item">
                  <div class="pli-icon" style="background:#ede9fe;color:#7c3aed">
                    <span class="material-symbols-outlined">local_pharmacy</span>
                  </div>
                  <div class="pli-body">
                    <span class="pli-title">{{ rx.medicationName }}</span>
                    <span class="pli-sub">{{ rx.dosage }} · {{ rx.frequency }}</span>
                    <span class="pli-meta">
                      By {{ rx.prescribedBy || 'Provider' }}
                      @if (rx.refillsRemaining !== undefined) {
                        · {{ rx.refillsRemaining }} refills remaining
                      }
                    </span>
                  </div>
                </div>
              }
            </div>
          </section>
        }
      }
    </div>
  `,
  styleUrl: './patient-portal-pages.scss',
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
