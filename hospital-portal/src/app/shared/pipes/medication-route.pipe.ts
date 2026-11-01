import { ChangeDetectorRef, OnDestroy, Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

/**
 * QA D950: the route codes the prescribing form offers, each with a
 * `PRESCRIPTIONS.ROUTES.<code>` label in every locale.
 */
export const MEDICATION_ROUTE_CODES = [
  'PO',
  'IV',
  'IM',
  'SC',
  'SL',
  'PR',
  'TOP',
  'INH',
  'NEB',
] as const;

/**
 * The usual English, French and Spanish names and abbreviations of each route,
 * folded (upper case, no accents, letters only). Mirrors
 * `com.example.hms.service.emar.MedicationRoutes` on the server, which the
 * bedside five-rights check uses; `MedicationRoutesPortalMirrorTest` keeps the
 * two lists equal.
 */
export const MEDICATION_ROUTE_SYNONYMS: Readonly<Record<string, string>> = {
  PO: 'PO',
  ORAL: 'PO',
  ORALE: 'PO',
  VO: 'PO',
  PEROS: 'PO',
  VOIEORALE: 'PO',
  VIAORAL: 'PO',
  BYMOUTH: 'PO',
  IV: 'IV',
  IVD: 'IV',
  INTRAVENOUS: 'IV',
  INTRAVEINEUSE: 'IV',
  INTRAVENEUX: 'IV',
  INTRAVENOSA: 'IV',
  INTRAVENOSO: 'IV',
  IM: 'IM',
  INTRAMUSCULAR: 'IM',
  INTRAMUSCULAIRE: 'IM',
  SC: 'SC',
  SQ: 'SC',
  SUBCUT: 'SC',
  SUBCUTANEOUS: 'SC',
  SOUSCUTANEE: 'SC',
  SOUSCUTANE: 'SC',
  SUBCUTANEA: 'SC',
  SUBCUTANEO: 'SC',
  SL: 'SL',
  SUBLINGUAL: 'SL',
  SUBLINGUALE: 'SL',
  PR: 'PR',
  RECTAL: 'PR',
  RECTALE: 'PR',
  INTRARECTAL: 'PR',
  TOP: 'TOP',
  TOPICAL: 'TOP',
  TOPIQUE: 'TOP',
  TOPICA: 'TOP',
  TOPICO: 'TOP',
  CUTANEE: 'TOP',
  CUTANEOUS: 'TOP',
  INH: 'INH',
  INHALATION: 'INH',
  INHALED: 'INH',
  INHALEE: 'INH',
  INHALADA: 'INH',
  INHALADO: 'INH',
  INHALACION: 'INH',
  NEB: 'NEB',
  NEBULISATION: 'NEB',
  NEBULIZATION: 'NEB',
  NEBULISED: 'NEB',
  NEBULIZED: 'NEB',
  NEBULIZACION: 'NEB',
};

/**
 * The route code for a stored or typed route ("ORAL", "Intraveineuse (IV)",
 * "sous-cutanée"), or `null` when it is not a known route.
 */
export function canonicalRoute(text: string | null | undefined): string | null {
  if (!text || !text.trim()) return null;
  const label = /\(([A-Za-z]{2,4})\)\s*$/.exec(text.trim());
  if (label) {
    const code = MEDICATION_ROUTE_SYNONYMS[label[1].toUpperCase()];
    if (code) return code;
  }
  const folded = text
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toUpperCase()
    .replace(/[^A-Z]/g, '');
  return MEDICATION_ROUTE_SYNONYMS[folded] ?? null;
}

/**
 * QA D950: a medication route in the reader's language. A known route reads
 * as its `PRESCRIPTIONS.ROUTES.<code>` label ("Intraveineuse (IV)"); any other
 * text is shown as written; empty reads as empty.
 *
 * Usage: `{{ task.route | medicationRoute }}`
 */
@Pipe({ name: 'medicationRoute', standalone: true, pure: false })
export class MedicationRoutePipe implements PipeTransform, OnDestroy {
  private readonly translate = inject(TranslateService);
  private readonly cdr = inject(ChangeDetectorRef, { optional: true });
  private readonly langSub: Subscription;

  constructor() {
    this.langSub = this.translate.onLangChange.subscribe(() => this.cdr?.markForCheck());
  }

  transform(route: string | null | undefined): string {
    if (!route) return '';
    const code = canonicalRoute(route);
    return code ? this.translate.instant(`PRESCRIPTIONS.ROUTES.${code}`) : route;
  }

  ngOnDestroy(): void {
    this.langSub.unsubscribe();
  }
}
