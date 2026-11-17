import { ChangeDetectorRef, OnDestroy, Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Subscription, merge } from 'rxjs';

/** The code the platform seeds its fallback organisation under. */
export const DEFAULT_ORG_CODE = 'DEFAULT_ORG';

/**
 * The English text the seeder and V3 write for that organisation. Stored
 * data, so it cannot be translated where it lives; it is translated where it
 * is shown, and only while it is still the untouched seed text.
 */
const SEED_TEXT: Record<'name' | 'description', { text: string; key: string }> = {
  name: { text: 'Default Healthcare Organization', key: 'ORGANIZATIONS.DEFAULT_ORG_NAME' },
  description: {
    text: 'Default organization for hospitals without specific organization assignment',
    key: 'ORGANIZATIONS.DEFAULT_ORG_DESCRIPTION',
  },
};

/**
 * QA D822: the translation key for the default organisation's seed text, or
 * null for anything else. A code, when known, must be the default one; an
 * organisation an admin has renamed keeps its own words.
 */
export function seededOrgTextKey(
  value: string | null | undefined,
  code: string | null | undefined,
  field: 'name' | 'description' = 'name',
): string | null {
  if (code != null && code !== DEFAULT_ORG_CODE) return null;
  const seed = SEED_TEXT[field];
  return value?.trim() === seed.text ? seed.key : null;
}

/**
 * `{{ org.name | orgText: org.code }}` or
 * `{{ org.description | orgText: org.code : 'description' }}`: the stored
 * text, except the default organisation's English seed text, which is shown
 * in the reader's language (QA D822).
 *
 * Impure, like the role and enum label pipes, so a language switch repaints.
 */
@Pipe({ name: 'orgText', standalone: true, pure: false })
export class OrgTextPipe implements PipeTransform, OnDestroy {
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

  transform(
    value: string | null | undefined,
    code?: string | null,
    field: 'name' | 'description' = 'name',
  ): string {
    const key = seededOrgTextKey(value, code, field);
    return key ? this.translate.instant(key) : (value ?? '');
  }
}
