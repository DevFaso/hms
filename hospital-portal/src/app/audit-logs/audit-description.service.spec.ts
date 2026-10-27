import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';
import { AuditDescriptionService } from './audit-description.service';
import { AUDIT_DESCRIPTION_PATTERNS } from './audit-description-patterns';

/**
 * QA D894: the super-admin audit log in French still showed "Super-admin
 * global-view request…", "Super-admin cross-tenant read…" and "Login failed:
 * bad credentials". Every description the server writes now has a pattern
 * (AuditDescriptionCoverageTest checks that on the server side); this checks
 * the reader's side against the real locale files.
 */
describe('AuditDescriptionService (QA D894)', () => {
  let service: AuditDescriptionService;
  let translate: TranslateService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TranslateModule.forRoot()] });
    translate = TestBed.inject(TranslateService);
    translate.setTranslation('en', en);
    translate.setTranslation('fr', fr);
    translate.setTranslation('es', es);
    service = TestBed.inject(AuditDescriptionService);
  });

  const describeIn = (lang: string, eventDescription: string, entityType?: string) => {
    translate.use(lang);
    return service.describe({ eventDescription, entityType });
  };

  it('reads the three descriptions from the report in French', () => {
    expect(describeIn('fr', 'Super-admin global-view request: GET /audit-logs')).toBe(
      'Requête du super-administrateur sur tous les hôpitaux : GET /audit-logs',
    );
    expect(describeIn('fr', 'Super-admin cross-tenant read: recent-activity (12 rows)')).toBe(
      'Lecture du super-administrateur sur tous les hôpitaux : activité récente (12 lignes)',
    );
    expect(describeIn('fr', 'Login failed: bad credentials')).toBe(
      'Échec de connexion : identifiant ou mot de passe incorrect',
    );
  });

  it('reads them in Spanish too', () => {
    expect(describeIn('es', 'Login failed: bad credentials')).toBe(
      'Error de inicio de sesión: usuario o contraseña incorrectos',
    );
    expect(
      describeIn('es', 'Super-admin cross-tenant read: hospitals/search?q=Wend (2 rows)'),
    ).toBe(
      'Búsqueda de hospitales del superadministrador en todos los hospitales «Wend» (2 filas)',
    );
  });

  it('reads the performing-lab result read and the low-stock alert (#968)', () => {
    expect(describeIn('fr', 'Cross-hospital lab result read at the performing laboratory')).toBe(
      'Résultat d’analyses d’un autre hôpital consulté par le laboratoire exécutant',
    );
    expect(
      describeIn(
        'fr',
        'Low stock alert: Paracétamol 500 mg at Pharmacie centrale — 3 on hand, reorder threshold 10',
      ),
    ).toBe(
      'Alerte de stock bas : Paracétamol 500 mg à Pharmacie centrale — 3 en stock, seuil de réapprovisionnement 10',
    );
    expect(
      describeIn(
        'es',
        'Low stock alert: Paracétamol 500 mg at Pharmacie centrale — 3 on hand, reorder threshold 10',
      ),
    ).toBe(
      'Alerta de stock bajo: Paracétamol 500 mg en Pharmacie centrale — 3 disponibles, umbral de reposición 10',
    );
  });

  it('keeps the D774 and D869 readings', () => {
    expect(describeIn('fr', 'Hospital scope refused (NOT_PERMITTED)')).toContain(
      'Accès à l’hôpital refusé',
    );
    expect(describeIn('fr', 'Patient record viewed')).toBe('Dossier patient consulté');
  });

  it('reads a phrase the server writes inside a description', () => {
    expect(
      describeIn('fr', 'MFA step-up for emergency broadcast refused: mfa.stepup.notEnrolled'),
    ).toBe(
      'Confirmation à double facteur refusée pour une annonce d’urgence : aucune application d’authentification inscrite',
    );
    expect(describeIn('fr', 'Cross-hospital lab result read on the treatment relationship')).toBe(
      'Consultation d’un autre hôpital au titre de la relation de soins : résultat d’analyses',
    );
  });

  it('reads a code with no portal-wide label through AUDIT.CODE, and keeps an unknown one', () => {
    expect(describeIn('fr', 'CHW empanelment ended')).toBe(
      'Inscription agent de santé communautaire sur la patientèle terminée',
    );
    expect(describeIn('fr', 'Sensitivity category changed from HIV to ZZ_UNKNOWN')).toBe(
      'Catégorie de sensibilité changée de VIH à ZZ_UNKNOWN',
    );
  });

  it('keeps an optional part out when the row has none, and in when it has one', () => {
    expect(describeIn('fr', 'Purge cancelled')).toBe('Purge annulée');
    expect(describeIn('fr', 'Purge cancelled: erreur de saisie')).toBe(
      'Purge annulée : erreur de saisie',
    );
  });

  it('reads a lab approval step written in English with codes, and a legacy French row', () => {
    expect(
      describeIn(
        'es',
        'Lab test definition GLY: approval step SUBMIT_FOR_QA, new status PENDING_QA_REVIEW.',
      ),
    ).not.toContain('PENDING_QA_REVIEW');
    expect(
      describeIn(
        'en',
        "Examen de laboratoire GLY : étape d'approbation ACTIVATE, nouveau statut Actif.",
      ),
    ).toContain('Lab test definition GLY');
  });

  /**
   * QA D905 replay: HOSPITAL_ADMIN_A in ES, /audit-logs filtered on
   * "Configuración modificada", read "Prueba de laboratorio TDRP-R149285:
   * paso de aprobación Activación, nuevo estado Actif." The row predates
   * #968: French, with the status already a French label.
   */
  it('reads a stored French or Spanish status label as its code in every language (QA D905)', () => {
    const stored =
      "Examen de laboratoire TDRP-R149285 : étape d'approbation ACTIVATE, nouveau statut Actif.";
    const inSpanish = describeIn('es', stored);
    expect(inSpanish).toContain(`nuevo estado ${es.LAB.APPROVAL_STATUS.ACTIVE}.`);
    expect(inSpanish).not.toContain('Actif');
    const inEnglish = describeIn('en', stored);
    expect(inEnglish).toContain(`new status ${en.LAB.APPROVAL_STATUS.ACTIVE}.`);
    expect(inEnglish).not.toContain('Actif');

    const storedEs =
      'Prueba de laboratorio GLY: paso de aprobación SUBMIT_FOR_QA, nuevo estado Pendiente de revisión de calidad.';
    expect(describeIn('fr', storedEs)).toContain(
      `nouveau statut ${fr.LAB.APPROVAL_STATUS.PENDING_QA_REVIEW}.`,
    );
    expect(
      describeIn(
        'es',
        'Lab test definition GLY: approval step APPROVE, new status Awaiting director approval.',
      ),
    ).toContain(`nuevo estado ${es.LAB.APPROVAL_STATUS.PENDING_DIRECTOR_APPROVAL}.`);
  });

  it('keeps a stored single-director suffix out of the status, in French and Spanish rows (QA D905)', () => {
    const storedFr =
      "Examen de laboratoire GLY : étape d'approbation APPROVE, nouveau statut Approuvé (l'approbateur a aussi effectué la revue qualité).";
    const inSpanish = describeIn('es', storedFr);
    expect(inSpanish).toContain(`nuevo estado ${es.LAB.APPROVAL_STATUS.APPROVED}`);
    expect(inSpanish).toContain('(el aprobador también realizó la revisión de calidad)');
    expect(inSpanish).not.toContain('approbateur');

    const storedEs =
      'Prueba de laboratorio GLY: paso de aprobación APPROVE, nuevo estado Aprobado (el aprobador también realizó la revisión de calidad).';
    const inEnglish = describeIn('en', storedEs);
    expect(inEnglish).toMatch(/^Lab test definition GLY: approval step /);
    expect(inEnglish).toContain(
      `, new status ${en.LAB.APPROVAL_STATUS.APPROVED} (the approver also completed the QA review).`,
    );
  });

  it('reads the pre-D638 lab approval shape, and the low-stock rows written in French or Spanish (QA D905 siblings)', () => {
    const legacy = describeIn('fr', 'Lab test definition approval step ACTIVATE -> ACTIVE');
    expect(legacy).toContain('Examen de laboratoire : étape d’approbation');
    expect(legacy).toContain(`nouveau statut ${fr.LAB.APPROVAL_STATUS.ACTIVE}.`);
    expect(
      describeIn(
        'es',
        'Lab test definition approval step APPROVE -> APPROVED (the approver also completed the QA review)',
      ),
    ).toContain('(el aprobador también realizó la revisión de calidad)');

    expect(
      describeIn(
        'es',
        'Alerte de stock bas : Paracétamol 500 mg à Pharmacie centrale — 3 en stock, seuil de réapprovisionnement 10',
      ),
    ).toBe(
      'Alerta de stock bajo: Paracétamol 500 mg en Pharmacie centrale — 3 disponibles, umbral de reposición 10',
    );
    expect(
      describeIn(
        'en',
        'Alerta de stock bajo: Paracétamol 500 mg en Farmacia central — 3 disponibles, umbral de reposición 10',
      ),
    ).toBe(
      'Low stock alert: Paracétamol 500 mg at Farmacia central — 3 on hand, reorder threshold 10',
    );
  });

  /**
   * QA D894, the super-admin rows the round-15 re-test could not replay (the
   * dev super-admin had lost its role, D902): a failed login, a global-view
   * request and a cross-tenant read, in all three languages.
   */
  it('reads the super-admin rows of D894 in Spanish and English too', () => {
    expect(describeIn('es', 'Super-admin global-view request: GET /audit-logs')).toBe(
      es.AUDIT.DESC.GLOBAL_VIEW_REQUEST.replace('{{method}}', 'GET').replace(
        '{{path}}',
        '/audit-logs',
      ),
    );
    expect(describeIn('es', 'Super-admin global-view request: GET /audit-logs')).not.toContain(
      'Super-admin',
    );
    expect(
      describeIn('es', 'Super-admin cross-tenant read: recent-activity (12 rows)'),
    ).not.toContain('cross-tenant');
    expect(describeIn('es', 'Login failed: account not active')).not.toContain('Login failed');
    expect(describeIn('en', 'Login failed: bad credentials')).toBe(
      en.AUDIT.DESC.LOGIN_FAILED_BAD_CREDENTIALS,
    );
    expect(describeIn('en', 'Super-admin cross-tenant read: recent-activity (12 rows)')).toContain(
      '12',
    );
    expect(describeIn('fr', 'Login failed: account not active')).toBe(
      fr.AUDIT.DESC.LOGIN_FAILED_INACTIVE,
    );
  });

  it('every pattern has its text in all three locales, and every phrase group it uses exists', () => {
    const missing: string[] = [];
    for (const [lang, bundle] of Object.entries({ en, fr, es })) {
      const audit = (bundle as unknown as { AUDIT: Record<string, Record<string, string>> }).AUDIT;
      for (const pattern of AUDIT_DESCRIPTION_PATTERNS) {
        const leaf = pattern.key.replace('AUDIT.DESC.', '');
        if (typeof audit['DESC'][leaf] !== 'string') missing.push(`${lang}: ${pattern.key}`);
        for (const domain of Object.values(pattern.localise ?? {})) {
          if (domain.startsWith('phrase:') && !audit[domain.slice('phrase:'.length)]) {
            missing.push(`${lang}: AUDIT.${domain.slice('phrase:'.length)}`);
          }
        }
      }
    }
    expect(missing).toEqual([]);
  });

  it('every placeholder a text uses is a parameter its pattern captures', () => {
    const wrong: string[] = [];
    for (const pattern of AUDIT_DESCRIPTION_PATTERNS) {
      const leaf = pattern.key.replace('AUDIT.DESC.', '');
      for (const [lang, bundle] of Object.entries({ en, fr, es })) {
        const text =
          (bundle as unknown as { AUDIT: { DESC: Record<string, string> } }).AUDIT.DESC[leaf] ?? '';
        for (const [, name] of text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)) {
          if (!(pattern.params ?? []).includes(name))
            wrong.push(`${lang}: ${pattern.key} {{${name}}}`);
        }
      }
    }
    expect(wrong).toEqual([]);
  });

  it('describes the profile activity the same way', () => {
    expect(describeIn('fr', 'Refresh token revoked on logout (jti=abc)')).toBe(
      'Jeton de renouvellement révoqué à la déconnexion (jti=abc)',
    );
  });
});
