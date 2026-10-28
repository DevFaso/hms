import {
  Component,
  OnInit,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientPortalService,
  MedicationSummary,
  MedicationRefill,
  PortalPrescription,
  RefillRequestStatus,
} from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { currentMedications } from '../current-medications';
import { ToastService } from '../../core/toast.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

@Component({
  selector: 'app-my-medications',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-medications.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./my-medications.component.scss', '../patient-portal-pages.scss'],
})
export class MyMedicationsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  medications = signal<MedicationSummary[]>([]);
  /**
   * QA D720: "Active medications" lists the courses still running; a
   * completed or discontinued one moves to "Past medications" (where its
   * refill button stays: a finished, dispensed course is refillable).
   */
  medicationGroups = computed(() => {
    const all = this.medications();
    const active = currentMedications(all);
    const past = all.filter((m) => !active.includes(m));
    return [
      { titleKey: 'PORTAL.MEDICATIONS.ACTIVE', meds: active },
      { titleKey: 'PORTAL.MEDICATIONS.PAST', meds: past },
    ].filter((g) => g.meds.length > 0);
  });
  prescriptions = signal<PortalPrescription[]>([]);
  refills = signal<MedicationRefill[]>([]);
  loading = signal(true);
  expandedMedId = signal<string | null>(null);
  expandedRxId = signal<string | null>(null);
  requestingRefill = signal<string | null>(null);

  /**
   * QA D966: a prescription printed for me offers its copy; the page said
   * "Imprimée pour le patient" with nothing to download.
   */
  openPrintedCopy(rxId: string, event?: Event): void {
    event?.stopPropagation();
    this.portal.getMyPrintedPrescription(rxId).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.toast.error(this.translate.instant('PORTAL.MEDICATIONS.PRINTED_COPY_FAILED')),
    });
  }

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

  requestRefill(med: MedicationSummary): void {
    this.requestingRefill.set(med.id);
    this.portal
      .requestRefill({
        prescriptionId: med.id,
        preferredPharmacy: '',
        notes: '',
      })
      .subscribe({
        next: (refill) => {
          this.refills.update((list) => [refill, ...list]);
          // Reflect the new request on the row immediately so the button
          // cannot be pressed twice while the list is refetching.
          this.medications.update((list) =>
            list.map((m) =>
              m.id === med.id
                ? { ...m, refillRequestStatus: 'REQUESTED' as const, refillRequestOpen: true }
                : m,
            ),
          );
          this.toast.success(this.translate.instant('PORTAL.MEDICATIONS.REFILL_REQUESTED'));
          this.requestingRefill.set(null);
        },
        error: (err: { error?: { message?: string } }) => {
          // The backend refuses a duplicate or an un-refillable prescription
          // with a message written for the patient — show it rather than a
          // generic failure.
          this.toast.error(
            err?.error?.message ?? this.translate.instant('PORTAL.MEDICATIONS.REFILL_FAILED'),
          );
          this.requestingRefill.set(null);
        },
      });
  }

  /**
   * Whether the row should offer a refill button. Driven entirely by what the
   * API says: `refillable` mirrors the server's own gate, and
   * `refillRequestOpen` covers REQUESTED and PAUSED alike.
   *
   * <p>The old gate lived in the template as `status === 'ACTIVE' || 'SIGNED'`,
   * which hid the button on every DISPENSED prescription — that is, on exactly
   * the prescriptions a patient needs a refill for, since you ask for a refill
   * after you have collected the medication.
   */
  canRequestRefill(med: MedicationSummary): boolean {
    return med.refillable !== false && !med.refillRequestOpen;
  }

  refillChipClass(status: RefillRequestStatus | undefined): string {
    switch (status) {
      case 'APPROVED':
        return 'refill-chip refill-chip--ok';
      case 'DENIED':
        return 'refill-chip refill-chip--danger';
      case 'PAUSED':
        return 'refill-chip refill-chip--warn';
      default:
        return 'refill-chip';
    }
  }
}
