import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import {
  PROVIDER_FACILITY_TYPES,
  ProviderEvidence,
  ProviderFacilityType,
  ProviderResponse,
  emptyEvidence,
  evidenceOf,
  isEvidenceComplete,
  isProviderFacilityType,
  normaliseEvidence,
  serverMessage,
} from '../../../services/provider.model';
import { SuperAdminProviderService } from '../../../services/super-admin-provider.service';
import { EnumLabelPipe } from '../../../shared/pipes/enum-label.pipe';
import { ProviderEvidenceFormComponent } from '../provider-evidence-form/provider-evidence-form';

/**
 * Create a provider facility (`/super-admin/providers/new`, AC-1), or submit
 * new evidence after a rejection or a revocation
 * (`/super-admin/providers/:id/resubmit`, AC-2). Both carry the two evidence
 * layers; only creation picks the type, the code and the contact email.
 */
@Component({
  selector: 'app-provider-form',
  standalone: true,
  imports: [FormsModule, RouterLink, TranslateModule, EnumLabelPipe, ProviderEvidenceFormComponent],
  templateUrl: './provider-form.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './provider-form.scss',
})
export class ProviderFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(SuperAdminProviderService);

  readonly facilityTypes = PROVIDER_FACILITY_TYPES;

  /** Set when resubmitting: the provider whose evidence is replaced. */
  readonly providerId = signal<string | null>(null);
  readonly provider = signal<ProviderResponse | null>(null);
  readonly loading = signal(false);
  readonly notFound = signal(false);
  readonly loadFailed = signal(false);

  readonly facilityType = signal<ProviderFacilityType>('PHARMACY');
  readonly code = signal('');
  readonly email = signal('');
  readonly evidence = signal<ProviderEvidence>(emptyEvidence());

  readonly saving = signal(false);
  readonly errorKey = signal<string | null>(null);
  readonly errorText = signal<string | null>(null);

  readonly resubmitting = computed(() => this.providerId() !== null);

  readonly canSubmit = computed(
    () =>
      !this.saving() &&
      isEvidenceComplete(this.evidence()) &&
      (this.resubmitting() || this.code().trim() !== ''),
  );

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) return;
    this.providerId.set(id);
    this.loading.set(true);
    this.service.get(id).subscribe({
      next: (provider) => {
        this.provider.set(provider);
        if (isProviderFacilityType(provider.facilityType)) {
          this.facilityType.set(provider.facilityType);
        }
        this.evidence.set(evidenceOf(provider));
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if ((err as { status?: number } | null)?.status === 404) {
          this.notFound.set(true);
        } else {
          this.loadFailed.set(true);
        }
        this.loading.set(false);
      },
    });
  }

  submit(): void {
    if (!this.canSubmit()) {
      this.errorKey.set('PROVIDER.FORM.REQUIRED_FIELDS');
      this.errorText.set(null);
      return;
    }
    this.saving.set(true);
    this.errorKey.set(null);
    this.errorText.set(null);
    const evidence = normaliseEvidence(this.evidence());
    const id = this.providerId();
    const request = id
      ? this.service.resubmit(id, evidence)
      : this.service.create({
          facilityType: this.facilityType(),
          code: this.code().trim(),
          email: this.email().trim() || null,
          ...evidence,
        });
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        void this.router.navigate(['/super-admin/providers', saved.id]);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.errorText.set(serverMessage(err));
        this.errorKey.set('PROVIDER.FORM.SAVE_FAILED');
      },
    });
  }
}
