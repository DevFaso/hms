import { ChangeDetectorRef, OnDestroy, Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Subscription, merge } from 'rxjs';

import { EnumLabelService } from '../../core/enum-label.service';
import { bareRole } from '../../core/role-token';

/**
 * A role token — in either spelling the system produces — as a locale-aware
 * label.
 *
 * `{{ role.name | roleLabel }}` rather than
 * `{{ role.name | enumLabel: 'role' }}` because the wire carries `ROLE_DOCTOR`
 * while `PORTAL.ENUM.ROLE` keys `DOCTOR`, and the `ROLE_` prefix is not
 * something a generic enum pipe should know about. {@link bareRole} does the
 * normalising; everything after it is the ordinary three-tier lookup.
 *
 * This exists because the labels were being produced three other ways, all of
 * them English whatever the locale: `AuthService.formatRole` (deleted with
 * this pipe), `formatJobTitle` in the staff screens, and a bare
 * `{{ role.name }}` in every role picker — which is what a French admin
 * registering a user actually saw:
 *
 *     ROLE_LAB_SCIENTIST   ROLE_MIDWIFE   ROLE_PHARMACY_VERIFIER   …
 *
 * **Only the display.** The wire value stays raw: `[value]="role.name"` and
 * `selectedRoles.includes(role.code)` compare the untranslated token, and must
 * keep doing so.
 *
 * Impure, like {@link EnumLabelPipe}, so a language switch or a bundle merged
 * after first paint repaints the label.
 */
@Pipe({ name: 'roleLabel', standalone: true, pure: false })
export class RoleLabelPipe implements PipeTransform, OnDestroy {
  private readonly labels = inject(EnumLabelService);
  private readonly translate = inject(TranslateService);
  private readonly cdr = inject(ChangeDetectorRef, { optional: true });
  private readonly sub: Subscription;

  constructor() {
    this.sub = merge(this.translate.onLangChange, this.translate.onTranslationChange).subscribe(
      () => this.cdr?.markForCheck(),
    );
  }

  ngOnDestroy(): void {
    this.sub.unsubscribe();
  }

  /** `''` for a role that resolves to nothing, so `|| '—'` works. */
  transform(value: string | null | undefined): string {
    return this.labels.transform(bareRole(value), 'role');
  }
}
