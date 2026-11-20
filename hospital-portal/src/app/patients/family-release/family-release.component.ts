import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
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
import { InlineDiscardAskComponent } from '../../shared/a11y/inline-discard-ask.component';
import { InlineSubFormDirective } from '../../shared/a11y/inline-sub-form.directive';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { sensitivityLabelKey } from '../../shared/sensitivity/sensitivity-category';

/** The shortest reason the server accepts (MinorResultReleaseRequestDTO). */
export const RELEASE_REASON_MIN = 10;
export const RELEASE_REASON_MAX = 1000;
/** The request's fields, by the id of the control that holds each. */
const RELEASE_FIELD_IDS: Readonly<Record<string, string>> = {
  grantId: 'family-release-guardian',
  reason: 'family-release-reason',
};

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
  imports: [
    FormsModule,
    TranslateModule,
    DateTimePipe,
    ServerTimePipe,
    InlineSubFormDirective,
    InlineDiscardAskComponent,
    SubmitGuardDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './family-release.component.html',
  styleUrl: './family-release.component.scss',
})
export class FamilyReleaseComponent {
  private readonly policy = inject(SensitivePolicyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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
  /**
   * Why the release cannot be sent yet, in words, or null when it can. A
   * blank parent or reason is named by the submit guard from the fields
   * themselves; this says what a filled form still lacks.
   */
  readonly blockedReason = computed<string | null>(() => {
    const typed = this.reason().trim().length;
    if (typed > 0 && typed < RELEASE_REASON_MIN) {
      return this.translate.instant('SENSITIVE_POLICY.RELEASE.REASON_TOO_SHORT', {
        min: RELEASE_REASON_MIN,
      });
    }
    if (this.needsAttestation() && !this.attested()) {
      return this.translate.instant('SENSITIVE_POLICY.RELEASE.ATTEST_REQUIRED');
    }
    return null;
  });

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
    // HIV only (owner decision 2026-11-21): nothing else opens the form; the server refuses it too.
    if (!result.releasable) return;
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
    // The submit guard has already stopped, and explained, an incomplete form.
    const complete = !!this.chosenGuardian() && this.reason().trim().length >= RELEASE_REASON_MIN;
    if (!result || this.busy() || !complete || this.blockedReason()) return;
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
          this.markRefused(err);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('SENSITIVE_POLICY.RELEASE.RELEASE_FAILED'),
          );
        },
      });
  }

  /** The server's own reason at the field it names (MinorResultReleaseRequestDTO). */
  private markRefused(err: unknown): void {
    const refused = refusedFieldReasons(err, RELEASE_FIELD_IDS);
    if (!refused.length) return;
    refuseFields(
      this.host.nativeElement,
      refused.map(([id, message]) => ({ id, message })),
      Object.values(RELEASE_FIELD_IDS),
    );
  }
}
