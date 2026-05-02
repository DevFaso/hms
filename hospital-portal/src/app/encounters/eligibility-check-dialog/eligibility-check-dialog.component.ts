import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  OnInit,
  Output,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  EligibilityCheckType,
  EligibilityResponse,
  EligibilityScheme,
  EligibilityService,
} from '../../services/eligibility.service';
import { ToastService } from '../../core/toast.service';

interface SchemeOption {
  value: EligibilityScheme;
  label: string;
}

/**
 * Real-time eligibility / prior-auth dialog (P1 #12 follow-up #4 — item 4).
 *
 * Picks the public-payer scheme, member id, and (for prior-auth) service code,
 * runs the synchronous round-trip, and renders the persisted response inline.
 * The encounter detail panel reuses the dialog by toggling its `open` flag.
 */
@Component({
  selector: 'app-eligibility-check-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './eligibility-check-dialog.component.html',
  styleUrl: './eligibility-check-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EligibilityCheckDialogComponent implements OnInit, OnChanges {
  private readonly eligibilityService = inject(EligibilityService);
  private readonly toast = inject(ToastService);

  @Input() open = false;
  @Input() patientId: string | null = null;
  @Input() hospitalId: string | null = null;
  @Output() readonly closed = new EventEmitter<void>();

  readonly schemes: SchemeOption[] = [
    { value: 'NHIS_GH', label: 'NHIS — Ghana' },
    { value: 'NHIA_NG', label: 'NHIA — Nigeria' },
    { value: 'CNAMGS_GA', label: 'CNAMGS — Gabon' },
    { value: 'MUTUELLE_RW', label: 'Mutuelle — Rwanda' },
    { value: 'MUTUELLE_BF', label: 'RAMU — Burkina Faso' },
    { value: 'GENERIC', label: 'Generic / private payer' },
  ];

  readonly types: EligibilityCheckType[] = ['COVERAGE', 'PRIOR_AUTH'];

  readonly scheme = signal<EligibilityScheme>('NHIS_GH');
  readonly checkType = signal<EligibilityCheckType>('COVERAGE');
  readonly memberId = signal<string>('');
  readonly serviceCode = signal<string>('');
  readonly running = signal(false);
  readonly latest = signal<EligibilityResponse | null>(null);

  ngOnInit(): void {
    this.refreshLatest();
  }

  ngOnChanges(): void {
    if (this.open && this.patientId) {
      this.refreshLatest();
    }
  }

  refreshLatest(): void {
    if (!this.patientId) return;
    this.eligibilityService
      .latestForPatient(this.patientId, this.scheme(), this.checkType())
      .subscribe({
        next: (resp) => this.latest.set(resp),
      });
  }

  onSchemeChange(value: EligibilityScheme): void {
    this.scheme.set(value);
    this.refreshLatest();
  }

  onTypeChange(value: EligibilityCheckType): void {
    this.checkType.set(value);
    this.refreshLatest();
  }

  run(): void {
    if (!this.patientId || !this.hospitalId) {
      this.toast.error('Patient or hospital context is missing.');
      return;
    }
    if (!this.memberId().trim()) {
      this.toast.error('Member id is required.');
      return;
    }
    if (this.checkType() === 'PRIOR_AUTH' && !this.serviceCode().trim()) {
      this.toast.error('Service code is required for prior-auth.');
      return;
    }
    this.running.set(true);
    const payload = {
      patientId: this.patientId,
      hospitalId: this.hospitalId,
      scheme: this.scheme(),
      checkType: this.checkType(),
      memberId: this.memberId().trim(),
      serviceCode: this.serviceCode().trim() || undefined,
    };
    const op =
      this.checkType() === 'PRIOR_AUTH'
        ? this.eligibilityService.requestPriorAuth(payload)
        : this.eligibilityService.checkCoverage(payload);
    op.subscribe({
      next: (resp) => {
        this.latest.set(resp);
        this.running.set(false);
        this.toast.success(
          resp.status === 'ELIGIBLE'
            ? 'Coverage active.'
            : `Result: ${resp.status.replace('_', ' ').toLowerCase()}.`,
        );
      },
      error: () => {
        this.running.set(false);
        this.toast.error('Eligibility check failed.');
      },
    });
  }

  statusClass(status: EligibilityResponse['status']): string {
    return `status status--${status.toLowerCase()}`;
  }

  close(): void {
    this.closed.emit();
  }
}
