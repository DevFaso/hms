import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, TitleCasePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  MedicationRefill,
  PatientPortalService,
  PortalPrescription,
  RefillRequest,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-my-refills',
  standalone: true,
  imports: [CommonModule, FormsModule, TitleCasePipe],
  templateUrl: './my-refills.html',
  styleUrl: './my-refills.scss',
})
export class MyRefillsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  refills = signal<MedicationRefill[]>([]);
  prescriptions = signal<PortalPrescription[]>([]);

  showForm = signal(false);
  submitting = signal(false);

  form: RefillRequest = { prescriptionId: '', preferredPharmacy: '', notes: '' };

  ngOnInit(): void {
    this.portal.getMyRefills().subscribe({
      next: (r) => {
        this.refills.set(r);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });

    this.portal.getMyPrescriptions().subscribe({
      next: (p) => this.prescriptions.set(p.filter((rx) => rx.refillsRemaining > 0)),
    });
  }

  openForm(): void {
    this.form = { prescriptionId: '', preferredPharmacy: '', notes: '' };
    this.showForm.set(true);
  }

  cancelForm(): void {
    this.showForm.set(false);
  }

  submitRefill(): void {
    if (!this.form.prescriptionId || !this.form.preferredPharmacy) return;
    this.submitting.set(true);
    this.portal.requestRefill(this.form).subscribe({
      next: (created) => {
        this.refills.update((list) => [created, ...list]);
        this.showForm.set(false);
        this.submitting.set(false);
        this.toast.success('Refill request submitted');
      },
      error: () => {
        this.submitting.set(false);
        this.toast.error('Failed to submit refill request');
      },
    });
  }

  cancelRefill(id: string): void {
    this.portal.cancelRefill(id).subscribe({
      next: (updated) =>
        this.refills.update((list) => list.map((r) => (r.id === id ? updated : r))),
      error: () => this.toast.error('Failed to cancel refill request'),
    });
  }

  statusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'DISPENSED':
        return 'status-dispensed';
      case 'APPROVED':
        return 'status-approved';
      case 'PENDING':
        return 'status-pending';
      case 'CANCELLED':
        return 'status-cancelled';
      default:
        return 'status-pending';
    }
  }
}
