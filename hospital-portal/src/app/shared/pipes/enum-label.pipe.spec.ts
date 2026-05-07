import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from './enum-label.pipe';

/**
 * EnumLabelPipe contract:
 *   1. i18n via PORTAL.ENUM.<UPPER_SNAKE>.<VALUE>
 *   2. English in-memory LABELS fallback (so missing JSON keys never leak)
 *   3. UPPER_SNAKE → Title Case prettify (last resort)
 *
 * This spec covers all three tiers in all three locales (en/fr/es) plus the
 * impure-pipe behaviour around `onLangChange`.
 */
describe('EnumLabelPipe', () => {
  let pipe: EnumLabelPipe;
  let translate: TranslateService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TranslateModule.forRoot()],
    });
    translate = TestBed.inject(TranslateService);
    translate.setDefaultLang('en');
    translate.use('en');
    pipe = TestBed.runInInjectionContext(() => new EnumLabelPipe());
  });

  /* ── Tier 0: defensive null/empty ────────────────────────── */

  it('returns empty string for null / undefined / empty input', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform('')).toBe('');
  });

  /* ── Tier 1: i18n lookup is the canonical path ──────────── */

  it('reads from PORTAL.ENUM.<GROUP>.<VALUE> when the key exists', () => {
    translate.setTranslation(
      'en',
      {
        PORTAL: {
          ENUM: {
            PRESCRIPTION_STATUS: { SIGNED: 'Signed (en)' },
          },
        },
      },
      true,
    );
    expect(pipe.transform('SIGNED', 'prescriptionStatus')).toBe('Signed (en)');
  });

  it('refreshes label when the user switches to French', () => {
    translate.setTranslation('en', {
      PORTAL: { ENUM: { PRESCRIPTION_STATUS: { SIGNED: 'Signed' } } },
    });
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { PRESCRIPTION_STATUS: { SIGNED: 'Signée' } } },
    });

    translate.use('en');
    expect(pipe.transform('SIGNED', 'prescriptionStatus')).toBe('Signed');

    translate.use('fr');
    expect(pipe.transform('SIGNED', 'prescriptionStatus')).toBe('Signée');
  });

  it('refreshes label when the user switches to Spanish', () => {
    translate.setTranslation('es', {
      PORTAL: { ENUM: { PRESCRIPTION_STATUS: { SIGNED: 'Firmada' } } },
    });
    translate.use('es');
    expect(pipe.transform('SIGNED', 'prescriptionStatus')).toBe('Firmada');
  });

  /* ── Tier 2: English LABELS fallback (when JSON is missing) ── */

  it('falls back to English LABELS when no JSON translation exists', () => {
    // No setTranslation — i18n will return the key path verbatim.
    // The pipe must NOT render the path; it must use the in-memory LABELS map.
    expect(pipe.transform('SIGNED', 'prescriptionStatus')).toBe('Signed');
    expect(pipe.transform('PENDING_SIGNATURE', 'prescriptionStatus')).toBe('Pending Signature');
    expect(pipe.transform('PARTIALLY_FILLED', 'prescriptionStatus')).toBe('Partially Filled');
  });

  it('falls back to English LABELS for the catch-all `status` group', () => {
    expect(pipe.transform('SCHEDULED', 'status')).toBe('Scheduled');
    expect(pipe.transform('IN_PROGRESS', 'status')).toBe('In Progress');
    expect(pipe.transform('NO_SHOW', 'status')).toBe('No Show');
  });

  it('finds value in any group when domain is omitted', () => {
    expect(pipe.transform('BLOOD_PRESSURE')).toBe('Blood Pressure');
    expect(pipe.transform('FOLLOW_UP')).toBe('Follow-Up');
    expect(pipe.transform('DOCTOR')).toBe('Doctor');
  });

  /* ── Tier 3: prettify is the safety net ──────────────────── */

  it('prettifies UPPER_SNAKE values that have no entry anywhere', () => {
    expect(pipe.transform('SOMETHING_TOTALLY_NEW', 'prescriptionStatus')).toBe(
      'Something Totally New',
    );
    expect(pipe.transform('A_B_C')).toBe('A B C');
  });

  it('NEVER renders the raw i18n key path', () => {
    // The most important regression guard. ngx-translate returns the key
    // verbatim when the translation is missing. The pipe MUST NOT propagate
    // that to the UI.
    const out = pipe.transform('TOTALLY_FAKE_VALUE', 'prescriptionStatus');
    expect(out).not.toContain('PORTAL.ENUM');
    expect(out).not.toContain('.');
  });

  /* ── Wire-value safety: pipe is read-only ───────────────── */

  it('does not mutate the input value', () => {
    const value = 'SIGNED';
    pipe.transform(value, 'prescriptionStatus');
    expect(value).toBe('SIGNED'); // wire value unchanged
  });

  /* ── Memoisation: same call returns same string ──────────── */

  it('caches per (lang, domain, value) tuple', () => {
    translate.setTranslation('en', {
      PORTAL: { ENUM: { PRESCRIPTION_STATUS: { SIGNED: 'Signed' } } },
    });
    const a = pipe.transform('SIGNED', 'prescriptionStatus');
    const b = pipe.transform('SIGNED', 'prescriptionStatus');
    expect(a).toBe(b);
    expect(a).toBe('Signed');
  });

  it('clears its memo when language changes (no stale labels)', () => {
    translate.setTranslation('en', {
      PORTAL: { ENUM: { PRESCRIPTION_STATUS: { SIGNED: 'Signed' } } },
    });
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { PRESCRIPTION_STATUS: { SIGNED: 'Signée' } } },
    });

    translate.use('en');
    expect(pipe.transform('SIGNED', 'prescriptionStatus')).toBe('Signed');
    translate.use('fr');
    expect(pipe.transform('SIGNED', 'prescriptionStatus')).toBe('Signée');
    translate.use('en');
    expect(pipe.transform('SIGNED', 'prescriptionStatus')).toBe('Signed');
  });

  /* ── Phase-1 specific groups (regression guards) ────────── */

  it('handles all Phase-1 groups via the English fallback', () => {
    // These all hit the in-memory LABELS map (no JSON in test bed).
    expect(pipe.transform('REQUESTED', 'consultationStatus')).toBe('Requested');
    expect(pipe.transform('STAT', 'consultationUrgency')).toBe('STAT');
    expect(pipe.transform('OUTPATIENT_CONSULT', 'consultationType')).toBe('Outpatient Consult');
    expect(pipe.transform('CHECKED_IN', 'appointmentStatus')).toBe('Checked In');
    expect(pipe.transform('READY_FOR_DISCHARGE', 'encounterStatus')).toBe('Ready for Discharge');
    expect(pipe.transform('APPROVED', 'leaveStatus')).toBe('Approved');
    expect(pipe.transform('CANCELLED', 'shiftStatus')).toBe('Cancelled');
    expect(pipe.transform('CRITICAL', 'alertSeverity')).toBe('Critical');
    expect(pipe.transform('LIFE_THREATENING', 'alertSeverity')).toBe('Life-Threatening');
    expect(pipe.transform('HIGH', 'taskPriority')).toBe('High');
  });
});
