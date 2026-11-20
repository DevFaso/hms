import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import {
  FamilyHiddenResult,
  FamilyReleaseGuardian,
  FamilyReleaseOverview,
  SensitivePolicyService,
} from '../../services/sensitive-policy.service';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { refusalReason } from '../../shared/refusal-reason';
import { sensitivityLabelKey } from '../../shared/sensitivity/sensitivity-category';

/** The shortest reason the server accepts (MinorResultReleaseRequestDTO). */
export const RELEASE_REASON_MIN = 10;
export const RELEASE_REASON_MAX = 1000;

/**
 * Policy 2026-11-20 (provisional): the release of a minor's sensitive result
 * to a parent or guardian, in the chart's lab section.
 *
 * Burkina's Loi 030-2008 (art. 17 and 18) gives the parents of a tested minor
 * a right to the HIV result. The result stays hidden in the family's portal;
 * a doctor or a midwife who has care of the patient releases it here to one
 * verified parent or guardian, with a reason, and the release is audited.
 * Nothing is shown for an adult, or to a reader the server refuses.
 */
@Component({
  selector: 'app-family-release',
  standalone: true,
  imports: [FormsModule, TranslateModule, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './family-release.component.html',
  styleUrl: './family-release.component.scss',
})
export class FamilyReleaseComponent {
  private readonly policy = inject(SensitivePolicyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly patientId = input.required<string>();

  readonly overview = signal<FamilyReleaseOverview | null>(null);
  readonly busy = signal(false);

  /** The result being released, and the form for it. */
  readonly releasing = signal<FamilyHiddenResult | null>(null);
  readonly grantId = signal('');
  readonly reason = signal('');
  readonly attested = signal(false);

  readonly reasonMin = RELEASE_REASON_MIN;
  readonly reasonMax = RELEASE_REASON_MAX;
  readonly labelOf = sensitivityLabelKey;

  readonly chosenGuardian = computed<FamilyReleaseGuardian | null>(
    () => this.overview()?.guardians.find((g) => g.grantId === this.grantId()) ?? null,
  );
  /** An unverified grant needs the clinician's attestation in the same act. */
  readonly needsAttestation = computed(() => {
    const guardian = this.chosenGuardian();
    return !!guardian && !guardian.verified;
  });
  readonly canSubmit = computed(
    () =>
      !this.busy() &&
      !!this.chosenGuardian() &&
      this.reason().trim().length >= RELEASE_REASON_MIN &&
      (!this.needsAttestation() || this.attested()),
  );

  constructor() {
    effect(() => {
      const id = this.patientId();
      this.overview.set(null);
      this.releasing.set(null);
      if (id) this.load(id);
    });
  }

  private load(patientId: string): void {
    this.policy.familyReleases(patientId).subscribe({
      next: (overview) => {
        if (patientId === this.patientId()) this.overview.set(overview);
      },
      // Refused (no care relationship, not a doctor or a midwife) or failed:
      // the panel is not shown; the chart's own sections say what they must.
      error: () => this.overview.set(null),
    });
  }

  relationshipKey(guardian: FamilyReleaseGuardian): string {
    return `SENSITIVE_POLICY.RELEASE.RELATIONSHIP_${guardian.relationship}`;
  }

  /** The guardians this result has not been released to yet. */
  remaining(result: FamilyHiddenResult): FamilyReleaseGuardian[] {
    const released = new Set(result.releases.map((r) => r.grantId));
    return (this.overview()?.guardians ?? []).filter((g) => !released.has(g.grantId));
  }

  startRelease(result: FamilyHiddenResult): void {
    const candidates = this.remaining(result);
    this.releasing.set(result);
    this.grantId.set(candidates.length === 1 ? candidates[0].grantId : '');
    this.reason.set('');
    this.attested.set(false);
  }

  cancelRelease(): void {
    if (this.busy()) return;
    this.releasing.set(null);
  }

  pickGuardian(grantId: string): void {
    this.grantId.set(grantId);
    this.attested.set(false);
  }

  verify(guardian: FamilyReleaseGuardian): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.policy.verifyGuardian(this.patientId(), guardian.grantId).subscribe({
      next: (overview) => {
        this.overview.set(overview);
        this.busy.set(false);
        this.toast.success(this.translate.instant('SENSITIVE_POLICY.RELEASE.VERIFIED_TOAST'));
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('SENSITIVE_POLICY.RELEASE.VERIFY_FAILED'),
        );
      },
    });
  }

  submitRelease(): void {
    const result = this.releasing();
    if (!result || !this.canSubmit()) return;
    this.busy.set(true);
    this.policy
      .releaseResult(this.patientId(), result.labOrderId, {
        grantId: this.grantId(),
        reason: this.reason().trim(),
        relationshipVerified: this.attested(),
      })
      .subscribe({
        next: (overview) => {
          this.overview.set(overview);
          this.busy.set(false);
          this.releasing.set(null);
          this.toast.success(this.translate.instant('SENSITIVE_POLICY.RELEASE.RELEASED_TOAST'));
        },
        error: (err: unknown) => {
          this.busy.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('SENSITIVE_POLICY.RELEASE.RELEASE_FAILED'),
          );
        },
      });
  }
}
