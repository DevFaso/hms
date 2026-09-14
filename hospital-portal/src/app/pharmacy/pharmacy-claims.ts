import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateService } from '@ngx-translate/core';
import {
  PharmacyClaimResponse,
  PharmacyClaimStatus,
  PharmacyService,
} from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';

interface StatusOption {
  value: PharmacyClaimStatus;
  labelFr: string;
}

/**
 * T-50: Claims management UI.
 * - Lists claims for the active hospital with French status filter.
 * - Lifecycle actions: submit / accept / reject / pay.
 * - CSV + FHIR export (T-48) surfaced as download buttons.
 */
@Component({
  selector: 'app-pharmacy-claims',
  standalone: true,
  imports: [CommonModule, FormsModule, DatePipe, DecimalPipe],
  templateUrl: './pharmacy-claims.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './pharmacy-claims.scss',
})
export class PharmacyClaimsComponent implements OnInit {
  private readonly pharmacy = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly loading = signal(false);
  readonly claims = signal<PharmacyClaimResponse[]>([]);
  readonly filter = signal<PharmacyClaimStatus | 'ALL'>('ALL');

  readonly statusOptions: StatusOption[] = [
    { value: 'DRAFT', labelFr: 'Brouillon' },
    { value: 'SUBMITTED', labelFr: 'Soumis' },
    { value: 'ACCEPTED', labelFr: 'Accepté' },
    { value: 'REJECTED', labelFr: 'Rejeté' },
    { value: 'PAID', labelFr: 'Payé' },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.pharmacy.listClaimsByHospital(0, 100).subscribe({
      next: (res) => {
        this.claims.set(res.data?.content ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PHARMACY.CLAIMS_LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  filtered = computed<PharmacyClaimResponse[]>(() => {
    const f = this.filter();
    const all = this.claims();
    return f === 'ALL' ? all : all.filter((c) => c.claimStatus === f);
  });

  statusLabel(s: PharmacyClaimStatus): string {
    return this.statusOptions.find((o) => o.value === s)?.labelFr ?? s;
  }

  submit(c: PharmacyClaimResponse): void {
    this.pharmacy.submitClaim(c.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.CLAIM_SUBMITTED'));
        this.load();
      },
      error: () => this.toast.error(this.translate.instant('PHARMACY.CLAIM_SUBMIT_FAILED')),
    });
  }

  accept(c: PharmacyClaimResponse): void {
    this.pharmacy.acceptClaim(c.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.CLAIM_ACCEPTED'));
        this.load();
      },
      error: () => this.toast.error(this.translate.instant('PHARMACY.CLAIM_ACCEPT_FAILED')),
    });
  }

  reject(c: PharmacyClaimResponse): void {
    const reason = window.prompt(this.translate.instant('PHARMACY.CLAIM_REJECT_REASON_PROMPT'));
    if (!reason || !reason.trim()) {
      return;
    }
    this.pharmacy.rejectClaim(c.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.CLAIM_REJECTED'));
        this.load();
      },
      error: () => this.toast.error(this.translate.instant('PHARMACY.CLAIM_REJECT_FAILED')),
    });
  }

  pay(c: PharmacyClaimResponse): void {
    this.pharmacy.payClaim(c.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.PAYMENT_RECORDED'));
        this.load();
      },
      error: () => this.toast.error(this.translate.instant('PHARMACY.CLAIM_PAY_FAILED')),
    });
  }

  exportCsv(): void {
    this.pharmacy.exportClaimsCsv(['SUBMITTED', 'ACCEPTED']).subscribe({
      next: (blob) => this.download(blob, 'pharmacy-claims.csv'),
      error: () => this.toast.error(this.translate.instant('PHARMACY.CLAIM_EXPORT_CSV_FAILED')),
    });
  }

  exportFhir(): void {
    this.pharmacy.exportClaimsFhir(['SUBMITTED', 'ACCEPTED']).subscribe({
      next: (blob) => this.download(blob, 'pharmacy-claims.fhir.json'),
      error: () => this.toast.error(this.translate.instant('PHARMACY.CLAIM_EXPORT_FHIR_FAILED')),
    });
  }

  private download(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    // Defer revoke so Safari/WebKit has time to start reading the blob.
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }
}
