import { ChangeDetectorRef, OnDestroy, Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import { EnumLabelService } from '../../core/enum-label.service';

/**
 * Converts raw UPPER_SNAKE_CASE enum **display values** (status badges, urgency
 * pills, severity icons, role tags, etc.) into human-readable, locale-aware
 * labels.
 *
 * The vocabulary and the three-tier lookup live in {@link EnumLabelService};
 * this pipe is the template-facing half and adds only what a template needs —
 * impurity, and a `markForCheck` so `OnPush` hosts re-render on a language
 * switch. Lookup order, for reference (full detail on the service):
 *
 * 1. **i18n** — `PORTAL.ENUM.<UPPER_SNAKE_GROUP>.<VALUE>`, the canonical
 *    source of truth.
 * 2. **In-memory English `LABELS`** — a resilient fallback for keys not yet
 *    ported into the locale JSON.
 * 3. **Prettify** — UPPER_SNAKE_CASE → Title Case. This step never fails and
 *    always renders English, which is why
 *    `scripts/check-i18n-enum-coverage.mjs` exists.
 *
 * **The pipe only translates the *display* — never the wire value.** Callers
 * must still compare the raw enum (`if (rx.status === 'SIGNED')`) against the
 * untranslated value.
 *
 * Usage:
 * ```html
 * {{ 'BLOOD_PRESSURE' | enumLabel }}              <!-- "Blood Pressure" -->
 * {{ rx.status | enumLabel: 'prescriptionStatus' }}<!-- "Signée" in French -->
 * {{ enc.status | enumLabel: 'encounterStatus' }}
 * {{ alert.severity | enumLabel: 'alertSeverity' }}
 * ```
 *
 * Needing a label from TypeScript rather than a template — to filter or sort
 * on what the user can see — is what {@link EnumLabelService} is for. Do not
 * put this pipe in a component's `providers` to get at `transform()`.
 */
@Pipe({ name: 'enumLabel', standalone: true, pure: false })
export class EnumLabelPipe implements PipeTransform, OnDestroy {
  private readonly labels = inject(EnumLabelService);
  private readonly translate = inject(TranslateService);
  /**
   * Host CDR — required so `OnPush` components (e.g. the storyboard banner)
   * re-render their badges when the user switches language at runtime.
   * `{ optional: true }` keeps unit-test wiring simple where the pipe is
   * constructed outside an Angular component (`new EnumLabelPipe()` in a
   * `runInInjectionContext`).
   */
  private readonly cdr = inject(ChangeDetectorRef, { optional: true });
  private readonly langSub: Subscription;
  private readonly translationSub: Subscription;

  constructor() {
    // Both halves listen to both events. The service drops its memo; this one
    // asks the host to re-run its template, matching ngx-translate's own pipe.
    // Clearing the memo without marking the host dirty is a half-fix: an
    // OnPush component keeps the label it already rendered until something
    // unrelated triggers change detection.
    this.langSub = this.translate.onLangChange.subscribe(() => this.cdr?.markForCheck());
    this.translationSub = this.translate.onTranslationChange.subscribe(() =>
      this.cdr?.markForCheck(),
    );
  }

  ngOnDestroy(): void {
    this.langSub.unsubscribe();
    this.translationSub.unsubscribe();
  }

  transform(value: string | null | undefined, domain?: string): string {
    return this.labels.transform(value, domain);
  }
}
