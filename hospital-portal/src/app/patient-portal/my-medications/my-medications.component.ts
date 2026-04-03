import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  MedicationSummary,
  MedicationRefill,
  PortalPrescription,
} from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-my-medications',
  standalone: true,
  imports: [CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-medications.component.html',
  styleUrls: ['./my-medications.component.scss', '../patient-portal-pages.scss'],
})
export class MyMedicationsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);

  medications = signal<MedicationSummary[]>([]);
  prescriptions = signal<PortalPrescription[]>([]);
  refills = signal<MedicationRefill[]>([]);
  loading = signal(true);
  expandedMedId = signal<string | null>(null);
  expandedRxId = signal<string | null>(null);
  requestingRefill = signal<string | null>(null);

  ngOnInit() {
    let pending = 3;
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

    this.portal.getMyRefills().subscribe({
      next: (r) => {
        this.refills.set(r);
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

  requestRefill(rx: PortalPrescription): void {
    this.requestingRefill.set(rx.id);
    this.portal
      .requestRefill({
        prescriptionId: rx.id,
        preferredPharmacy: '',
        notes: '',
      })
      .subscribe({
        next: (refill) => {
          this.refills.update((list) => [refill, ...list]);
          this.toast.success('PORTAL.MEDICATIONS.REFILL_REQUESTED');
          this.requestingRefill.set(null);
        },
        error: () => {
          this.toast.error('PORTAL.MEDICATIONS.REFILL_FAILED');
          this.requestingRefill.set(null);
        },
      });
  }

  hasActiveRefill(prescriptionId: string): boolean {
    return this.refills().some(
      (r) =>
        r.prescriptionId === prescriptionId && (r.status === 'PENDING' || r.status === 'REQUESTED'),
    );
  }
}
