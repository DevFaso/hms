import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { storedLang } from '../shared/i18n/app-locale';
import { DayMonthPipe } from '../shared/pipes/day-month.pipe';

/**
 * V356 (owner direction 2026-11-21): the provisional rule sets of the
 * platform and where ONE hospital stands on each. Every rule built so far is
 * « provisoire » until the hospital's named approver (the médecin-chef, or
 * the role the registry names) signs it off; the hospital admin records the
 * signature. Recording it changes no rule's behaviour: only the label.
 */

export type RuleSignoffStatus = 'PENDING' | 'SIGNED' | 'REVOKED';

/** A text in the portal's three languages. */
export interface RuleText {
  en: string;
  fr: string;
  es: string;
}

export interface RuleSignoff {
  id: string;
  ruleVersion: string;
  status: RuleSignoffStatus;
  signerName: string;
  signerTitle: string;
  signerUserId: string | null;
  signedOn: string;
  note: string | null;
  recordedByName: string | null;
  recordedAt: string;
  revokedAt: string | null;
  revokedByName: string | null;
  revokeReason: string | null;
}

export interface ClinicalRuleStatus {
  key: string;
  version: string;
  title: RuleText;
  description: RuleText;
  /** The published source (a citation as written) or the decision it rests on. */
  source: RuleText;
  approverRole: RuleText;
  enforcedIn: string[];
  status: RuleSignoffStatus;
  /** The sign-off of the current version (signed, or revoked); null while pending. */
  signoff: RuleSignoff | null;
  /** While pending: an earlier version this hospital had signed. */
  lastSignedVersion: string | null;
}

export interface ClinicalRulesOverview {
  hospitalId: string;
  canManage: boolean;
  signedCount: number;
  total: number;
  rules: ClinicalRuleStatus[];
}

export interface RuleSignoffRequest {
  ruleVersion: string;
  signerName: string;
  signerTitle: string;
  signerUserId: string | null;
  signedOn: string;
  note: string | null;
}

/** The text in the reader's language (the portal reloads on a language switch). */
export function ruleText(text: RuleText | null | undefined, lang: string = storedLang()): string {
  if (!text) return '';
  if (lang === 'fr') return text.fr;
  if (lang === 'es') return text.es;
  return text.en;
}

@Injectable({ providedIn: 'root' })
export class ClinicalRulesService {
  private readonly http = inject(HttpClient);

  /**
   * The last overview read, for the « provisoire » labels across the portal:
   * one request per hospital, refreshed by every write on the rules page.
   */
  private readonly cached = signal<ClinicalRulesOverview | null>(null);
  private loadingFor: string | null = null;
  /** A hospital whose list could not be read (a role without access): not asked again. */
  private failedFor: string | null = null;

  readonly overviewSignal = this.cached.asReadonly();

  overview(hospitalId: string): Observable<ClinicalRulesOverview> {
    return this.http
      .get<ClinicalRulesOverview>(`/hospitals/${hospitalId}/clinical-rules`)
      .pipe(tap((overview) => this.cached.set(overview)));
  }

  sign(
    hospitalId: string,
    ruleKey: string,
    body: RuleSignoffRequest,
  ): Observable<ClinicalRuleStatus> {
    return this.http
      .post<ClinicalRuleStatus>(
        `/hospitals/${hospitalId}/clinical-rules/${encodeURIComponent(ruleKey)}/signoffs`,
        body,
      )
      .pipe(tap((status) => this.replace(hospitalId, status)));
  }

  revoke(hospitalId: string, ruleKey: string, reason: string): Observable<ClinicalRuleStatus> {
    return this.http
      .post<ClinicalRuleStatus>(
        `/hospitals/${hospitalId}/clinical-rules/${encodeURIComponent(ruleKey)}/revocation`,
        { reason },
      )
      .pipe(tap((status) => this.replace(hospitalId, status)));
  }

  /** The French sign-off pack (PDF) the médecin-chef signs on paper. */
  signoffPack(hospitalId: string): Observable<Blob> {
    return this.http.get(`/hospitals/${hospitalId}/clinical-rules/signoff-pack`, {
      responseType: 'blob',
    });
  }

  /**
   * Loads the overview for the labels once per hospital. A role that may not
   * read it (403) or a failure leaves nothing cached: the labels then keep the
   * screen's own « provisoire » wording.
   */
  ensureLoaded(hospitalId: string | null): void {
    if (!hospitalId) return;
    if (
      this.cached()?.hospitalId === hospitalId ||
      this.loadingFor === hospitalId ||
      this.failedFor === hospitalId
    ) {
      return;
    }
    this.loadingFor = hospitalId;
    this.overview(hospitalId).subscribe({
      next: () => (this.loadingFor = null),
      error: () => {
        this.loadingFor = null;
        this.failedFor = hospitalId;
      },
    });
  }

  /** The cached status of one rule set at {@code hospitalId}, or null when none is known. */
  statusAt(hospitalId: string | null, ruleKey: string): ClinicalRuleStatus | null {
    const overview = this.cached();
    if (!overview || !hospitalId || overview.hospitalId !== hospitalId) return null;
    return overview.rules.find((rule) => rule.key === ruleKey) ?? null;
  }

  /**
   * The label of one rule set at {@code hospitalId} as one sentence, for text that is not a template (a toast):
   * « Provisoire — source : … » or « Validée le … par … », in the reader's language. Null while the hospital's
   * status is not known, so the caller keeps its own wording.
   */
  statusSentence(
    hospitalId: string | null,
    ruleKey: string,
    translate: { instant(key: string, params?: Record<string, unknown>): unknown },
  ): string | null {
    const rule = this.statusAt(hospitalId, ruleKey);
    if (!rule) return null;
    if (rule.status === 'SIGNED' && rule.signoff) {
      return String(
        translate.instant('CLINICAL_RULES.LABEL.SIGNED', {
          date: new DayMonthPipe().transform(rule.signoff.signedOn, undefined, true),
          title: rule.signoff.signerTitle,
        }),
      );
    }
    return String(
      translate.instant('CLINICAL_RULES.LABEL.PENDING', {
        source: ruleText(rule.source),
        approver: ruleText(rule.approverRole),
      }),
    );
  }

  private replace(hospitalId: string, status: ClinicalRuleStatus): void {
    const overview = this.cached();
    if (!overview || overview.hospitalId !== hospitalId) return;
    const rules = overview.rules.map((rule) => (rule.key === status.key ? status : rule));
    this.cached.set({
      ...overview,
      rules,
      signedCount: rules.filter((rule) => rule.status === 'SIGNED').length,
    });
  }
}
