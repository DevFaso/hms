import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnChanges,
  SimpleChanges,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ReceptionService, FrontDeskPatientSnapshot } from '../reception.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-patient-snapshot-drawer',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './patient-snapshot-drawer.component.html',
  styleUrl: './patient-snapshot-drawer.component.scss',
})
export class PatientSnapshotDrawerComponent implements OnChanges {
  @Input() patientId: string | null = null;
  @Output() panelClosed = new EventEmitter<void>();

  private readonly receptionService = inject(ReceptionService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  snapshot = signal<FrontDeskPatientSnapshot | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);

  // ── MVP 11: Eligibility attestation ──────────────────────────────────────
  showAttestModal = signal(false);
  attestNotes = signal('');
  attestSaving = signal(false);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['patientId'] && this.patientId) {
      this.loadSnapshot(this.patientId);
    }
  }

  private loadSnapshot(patientId: string): void {
    this.loading.set(true);
    this.error.set(null);
    this.snapshot.set(null);
    this.receptionService.getPatientSnapshot(patientId).subscribe({
      next: (s) => {
        this.snapshot.set(s);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(this.translate.instant('RECEPTION.LOAD_SNAPSHOT_FAILED'));
        this.loading.set(false);
      },
    });
  }

  openAttestModal(): void {
    this.attestNotes.set('');
    this.showAttestModal.set(true);
  }

  submitAttestation(): void {
    const insuranceId = this.snapshot()?.insurance?.insuranceId;
    if (!insuranceId) {
      this.toast.error(this.translate.instant('RECEPTION.NO_INSURANCE_TO_ATTEST'));
      return;
    }
    this.attestSaving.set(true);
    this.receptionService.attestEligibility(insuranceId, this.attestNotes()).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.ELIGIBILITY_VERIFIED_SUCCESS'));
        this.showAttestModal.set(false);
        this.attestSaving.set(false);
        if (this.patientId) this.loadSnapshot(this.patientId);
      },
      error: () => {
        this.toast.error(this.translate.instant('RECEPTION.ATTEST_FAILED'));
        this.attestSaving.set(false);
      },
    });
  }
}
