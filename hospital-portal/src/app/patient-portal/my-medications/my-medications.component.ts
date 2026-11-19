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
import { saveResponse } from '../../shared/download-file';
import { refusalReason } from '../../shared/refusal-reason';
import { ShownRows } from '../shown-rows';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { PrescriptionDocumentComponent } from '../prescription-document/prescription-document.component';
import {
  isPrintablePrescription,
  prescriptionDocumentItems,
} from '../prescription-document/prescription-document';

@Component({
  selector: 'app-my-medications',
  standalone: true,
  imports: [
    ServerTimePipe,
    CommonModule,
    DatePipe,
    EnumLabelPipe,
    TranslateModule,
    PrescriptionDocumentComponent,
  ],
  templateUrl: './my-medications.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./my-medications.component.scss', '../patient-portal-pages.scss'],
})
export class MyMedicationsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);
  medications = signal<MedicationSummary[]>([]);
  /** QA D1399: the medicines could not be read: said so, never "no medicines". */
  medicationsFailed = signal(false);
  /** The server's reason, when it gave one. */
  medicationsFailureReason = signal<string | null>(null);
  private readonly pastMedications = computed(() => {
    const all = this.medications();
    const active = currentMedications(all);
    return all.filter((m) => !active.includes(m));
  });
  /** QA D1399: every past medicine is one click away, with the total. */
  readonly pastRows = new ShownRows(this.pastMedications);
  /**
   * QA D720: "Active medications" lists the courses still running; a
   * completed or discontinued one moves to "Past medications" (where its
   * refill button stays: a finished, dispensed course is refillable).
   */
  medicationGroups = computed(() => {
    const active = currentMedications(this.medications());
    return [
      { titleKey: 'PORTAL.MEDICATIONS.ACTIVE', meds: active, pager: null },
      { titleKey: 'PORTAL.MEDICATIONS.PAST', meds: this.pastRows.shown(), pager: this.pastRows },
    ].filter((g) => g.meds.length > 0);
  });
  prescriptions = signal<PortalPrescription[]>([]);
  /** QA D1442: every prescription one click away, with the total, as the past medicines. */
  readonly prescriptionRows = new ShownRows(this.prescriptions);
  refills = signal<MedicationRefill[]>([]);
  loading = signal(true);
  expandedMedId = signal<string | null>(null);
  expandedRxId = signal<string | null>(null);
  requestingRefill = signal<string | null>(null);
  /** QA D2354: the refill request being withdrawn. */
  cancellingRefill = signal<string | null>(null);
  /** QA D2353: the medicines of the prescription document open, if any. */
  documentItems = signal<PortalPrescription[] | null>(null);

  isPrintable(rx: PortalPrescription): boolean {
    return isPrintablePrescription(rx);
  }

  openDocument(rx: PortalPrescription, event?: Event): void {
    event?.stopPropagation();
    this.documentItems.set(prescriptionDocumentItems(rx, this.prescriptions()));
  }

  closeDocument(): void {
    this.documentItems.set(null);
  }

  /** QA D2354: a request the prescriber has not decided yet can still be withdrawn. */
  isPendingRefill(refill: MedicationRefill): boolean {
    return refill.status === 'REQUESTED' || refill.status === 'PAUSED';
  }

  /** The open request for a medicine's prescription, newest first. */
  openRefillFor(prescriptionId: string): MedicationRefill | null {
    return (
      this.refills().find((r) => r.prescriptionId === prescriptionId && this.isPendingRefill(r)) ??
      null
    );
  }

  /** QA D2354: withdraw a pending request, after a confirmation. */
  async cancelRefill(refill: MedicationRefill, event?: Event): Promise<void> {
    event?.stopPropagation();
    const confirmed = await this.confirm.ask({
      titleKey: 'PORTAL_REFILL.CANCEL_CONFIRM_TITLE',
      messageKey: 'PORTAL_REFILL.CANCEL_CONFIRM_MESSAGE',
      params: { name: refill.medicationName },
      confirmKey: 'PORTAL_REFILL.CANCEL',
      dismissKey: 'PORTAL_REFILL.KEEP',
    });
    if (!confirmed) return;
    this.cancellingRefill.set(refill.id);
    this.portal.cancelRefill(refill.id).subscribe({
      next: (cancelled) => {
        const status = cancelled?.status ?? 'CANCELLED';
        this.refills.update((list) =>
          list.map((r) => (r.id === refill.id ? { ...r, ...cancelled, status } : r)),
        );
        this.medications.update((list) =>
          list.map((m) =>
            m.id === refill.prescriptionId
              ? { ...m, refillRequestStatus: status, refillRequestOpen: false }
              : m,
          ),
        );
        this.toast.success(this.translate.instant('PORTAL_REFILL.CANCELLED'));
        this.cancellingRefill.set(null);
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PORTAL_REFILL.CANCEL_FAILED'),
        );
        this.cancellingRefill.set(null);
      },
    });
  }

  /**
   * QA D966: a prescription printed for me offers its copy; the page said
   * "Imprimée pour le patient" with nothing to download.
   */
  openPrintedCopy(rxId: string, event?: Event): void {
    event?.stopPropagation();
    this.portal.getMyPrintedPrescription(rxId).subscribe({
      // QA D1443: saved under the server's readable name; a viewer tab named it after a random id.
      next: (response) => saveResponse(response, 'ordonnance.pdf'),
      error: (err: unknown) =>
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PORTAL.MEDICATIONS.PRINTED_COPY_FAILED'),
        ),
    });
  }

  ngOnInit() {
    let pending = 3;
    const done = () => {
      if (--pending <= 0) this.loading.set(false);
    };

    this.portal.getAllMyMedications().subscribe({
      next: (meds) => {
        this.medications.set(meds);
        done();
      },
      error: (err: unknown) => {
        this.medicationsFailureReason.set(refusalReason(err));
        this.medicationsFailed.set(true);
        done();
      },
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

  /** QA D2354: a refill is sent only once the patient has confirmed it. */
  async requestRefill(med: MedicationSummary): Promise<void> {
    if (this.requestingRefill()) return;
    const confirmed = await this.confirm.ask({
      titleKey: 'PORTAL_REFILL.CONFIRM_TITLE',
      messageKey: 'PORTAL_REFILL.CONFIRM_MESSAGE',
      params: { name: med.medicationName },
      confirmKey: 'PORTAL_REFILL.CONFIRM_SEND',
    });
    if (!confirmed) return;
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
            refusalReason(err) ?? this.translate.instant('PORTAL.MEDICATIONS.REFILL_FAILED'),
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
