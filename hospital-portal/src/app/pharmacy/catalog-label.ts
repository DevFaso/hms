import { Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

/** The dosage forms the catalogue offers, each with a `PHARMACY.FORM_OPTION.<code>` label in every locale. */
export const CATALOG_FORM_CODES: readonly string[] = [
  'TABLET',
  'CAPSULE',
  'SYRUP',
  'INJECTION',
  'CREAM',
  'OINTMENT',
  'DROPS',
  'SUSPENSION',
  'POWDER',
  'INHALER',
  'SUPPOSITORY',
];

/** The routes the catalogue offers, each with a `PHARMACY.ROUTE_OPTION.<code>` label in every locale. */
export const CATALOG_ROUTE_CODES: readonly string[] = [
  'ORAL',
  'INTRAVENOUS',
  'INTRAMUSCULAR',
  'SUBCUTANEOUS',
  'TOPICAL',
  'INHALATION',
  'RECTAL',
  'OPHTHALMIC',
  'OTIC',
  'NASAL',
];

/** « Autre », offered by both lists under the shared word. */
const OTHER = 'OTHER';

/** A catalogue label as the server writes it: the name and strength, then the dosage form's code. */
const FORM_CODE_SUFFIX = /\s*\(([A-Z][A-Z_]*)\)\s*$/;

/**
 * QA D4187 (r81): the approval panel read « R81 Sirop Paracétamol (SYRUP) », « Forme TABLET → CAPSULE »,
 * « Voie d'administration ORAL » in a French interface, while the catalogue table beside it said
 * « Sirop », « Comprimé », « Orale ». The approver judges a change of form on these words.
 *
 * One place gives a dosage form and a route their words, through the keys the catalogue table uses
 * (`PHARMACY.FORM_OPTION.*`, `PHARMACY.ROUTE_OPTION.*`); every stock list that names the form of a
 * product (QA D4184) goes through it too. A form or a route typed as free text before the lists
 * existed is shown as it was stored.
 */
export function catalogFormLabel(
  translate: TranslateService,
  form: string | null | undefined,
): string {
  const code = (form ?? '').trim();
  if (!code) return '—';
  if (code === OTHER) return translate.instant('COMMON.OTHER');
  return CATALOG_FORM_CODES.includes(code)
    ? translate.instant(`PHARMACY.FORM_OPTION.${code}`)
    : code;
}

/** The route of administration of a catalogue entry in the reader's words; a dash for none. */
export function catalogRouteLabel(
  translate: TranslateService,
  route: string | null | undefined,
): string {
  const code = (route ?? '').trim();
  if (!code) return '—';
  if (code === OTHER) return translate.instant('COMMON.OTHER');
  return CATALOG_ROUTE_CODES.includes(code)
    ? translate.instant(`PHARMACY.ROUTE_OPTION.${code}`)
    : code;
}

/**
 * The server names an entry « name strength (FORM_CODE) »: the same label with the form in the
 * reader's words.
 */
export function catalogLabel(
  translate: TranslateService,
  label: string | null | undefined,
): string {
  const text = (label ?? '').trim();
  const code = FORM_CODE_SUFFIX.exec(text);
  if (!code) return text;
  return `${text.slice(0, code.index).trim()} (${catalogFormLabel(translate, code[1])})`;
}

/** `{{ lot.form | catalogForm }}`: a dosage form in the reader's words. */
@Pipe({ name: 'catalogForm', standalone: true })
export class CatalogFormPipe implements PipeTransform {
  private readonly translate = inject(TranslateService);

  transform(form: string | null | undefined): string {
    return catalogFormLabel(this.translate, form);
  }
}
