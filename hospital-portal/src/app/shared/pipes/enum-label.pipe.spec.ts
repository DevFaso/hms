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
    // ngx-translate v17 renamed `setDefaultLang` → `setFallbackLang`.
    translate.setFallbackLang('en');
    translate.use('en');
    pipe = TestBed.runInInjectionContext(() => new EnumLabelPipe());
  });

  // The pipe subscribes to TranslateService.onLangChange in its constructor.
  // Each spec creates a fresh instance via `new EnumLabelPipe()` (rather than
  // a host fixture that Angular would destroy automatically), so we must
  // tear that subscription down by hand — otherwise subscribers from prior
  // specs accumulate on the same TranslateService instance and we leak
  // observers / risk cross-test interference. (Copilot review on PR #261.)
  afterEach(() => {
    pipe.ngOnDestroy();
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

  /* ── Phase 2 groups (lab + admin + referrals + signatures) ─ */

  it('handles all Phase-2 groups via the English fallback', () => {
    expect(pipe.transform('RESULTED', 'labOrderStatus')).toBe('Resulted');
    expect(pipe.transform('ABNORMAL', 'abnormalFlag')).toBe('Abnormal');
    expect(pipe.transform('METHOD_COMPARISON', 'validationStudyType')).toBe('Method Comparison');
    expect(pipe.transform('AWAITING_DISCHARGE', 'admissionStatus')).toBe('Awaiting Discharge');
    expect(pipe.transform('OUT_OF_SERVICE', 'bedStatus')).toBe('Out of Service');
    expect(pipe.transform('PARTIAL', 'dispenseStatus')).toBe('Partial');
    expect(pipe.transform('PENDING_AUTHORIZATION', 'imagingOrderStatus')).toBe(
      'Pending Authorization',
    );
    expect(pipe.transform('PRELIMINARY', 'imagingReportStatus')).toBe('Preliminary');
    expect(pipe.transform('REVISIONS_REQUIRED', 'treatmentPlanStatus')).toBe('Revisions Required');
    // TreatmentPlanTaskStatus is a separate enum from TreatmentPlanStatus.
    // Copilot review on PR #262 caught this — PENDING / IN_PROGRESS aren't
    // valid TREATMENT_PLAN_STATUS values and would fall through to prettify.
    expect(pipe.transform('PENDING', 'treatmentPlanTaskStatus')).toBe('Pending');
    expect(pipe.transform('IN_PROGRESS', 'treatmentPlanTaskStatus')).toBe('In Progress');
    expect(pipe.transform('ACKNOWLEDGED', 'referralStatus')).toBe('Acknowledged');
    expect(pipe.transform('PRIORITY', 'referralUrgency')).toBe('Priority');
    expect(pipe.transform('SHARED_CARE', 'referralType')).toBe('Shared Care');
    expect(pipe.transform('REFERRED', 'mtmReviewStatus')).toBe('Referred');
    expect(pipe.transform('REVOKED', 'signatureStatus')).toBe('Revoked');
    expect(pipe.transform('PARTIALLY_PAID', 'invoiceStatus')).toBe('Partially Paid');
    expect(pipe.transform('ENTERED_IN_ERROR', 'allergyVerificationStatus')).toBe(
      'Entered in Error',
    );
    expect(pipe.transform('SNF', 'dischargeDisposition')).toBe('Skilled Nursing Facility');
  });

  /* ── Phase 3 groups (audit + internal) ───────────────────── */

  it('handles all Phase-3 groups via the English fallback', () => {
    expect(pipe.transform('SUCCESS', 'auditStatus')).toBe('Success');
    expect(pipe.transform('FAILURE', 'auditStatus')).toBe('Failure');
    expect(pipe.transform('SYSTEM', 'actorType')).toBe('System');
    expect(pipe.transform('BREAK_GLASS_ACCESS', 'auditEventType')).toBe('Break-Glass Access');
    expect(pipe.transform('PASSWORD_CHANGED', 'auditEventType')).toBe('Password Changed');
    // AuditEventType has hundreds of values; ensure unmapped ones still
    // never render as the raw key — they fall through to the prettifier.
    expect(pipe.transform('STOCK_REORDER_ALERT', 'auditEventType')).toBe('Stock Reorder Alert');
  });

  /* ── Migrated PR #256 keys: PORTAL.ENUM.* is now the only source ── */

  it('reads migrated PRESCRIPTION_STATUS keys via i18n (PR #256 migration)', () => {
    translate.setTranslation('en', {
      PORTAL: {
        ENUM: {
          PRESCRIPTION_STATUS: {
            DRAFT: 'Draft',
            SIGNED: 'Signed',
            DISCONTINUED: 'Discontinued',
          },
        },
      },
    });
    // Each value rendered through the canonical namespace — no fallback to
    // the deleted PRESCRIPTIONS.STATUS.* sub-tree.
    expect(pipe.transform('DRAFT', 'prescriptionStatus')).toBe('Draft');
    expect(pipe.transform('SIGNED', 'prescriptionStatus')).toBe('Signed');
    expect(pipe.transform('DISCONTINUED', 'prescriptionStatus')).toBe('Discontinued');
  });

  it('reads migrated LEAVE_STATUS keys via i18n (PR #256 SCHEDULING.* migration)', () => {
    translate.setTranslation('en', {
      PORTAL: {
        ENUM: {
          LEAVE_STATUS: { PENDING: 'Pending', APPROVED: 'Approved' },
          SHIFT_STATUS: { SCHEDULED: 'Scheduled' },
        },
      },
    });
    expect(pipe.transform('PENDING', 'leaveStatus')).toBe('Pending');
    expect(pipe.transform('APPROVED', 'leaveStatus')).toBe('Approved');
    expect(pipe.transform('SCHEDULED', 'shiftStatus')).toBe('Scheduled');
  });
});
