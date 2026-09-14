#!/usr/bin/env node
/**
 * The shared `status` pool is the one piped domain nothing verifies.
 *
 * check-i18n-enum-coverage.mjs reads the Java enum behind every declared
 * domain and fails on an unkeyed constant — but `status` is declared with a
 * `reason` (its call sites' DTOs genuinely disagree), which exempts it. That
 * was tolerable while the pool backed badges whose vocabularies overlapped.
 *
 * It stopped being tolerable when the chart-review TIMELINE was pointed at it.
 * That one pill carries all six ChartReviewServiceImpl sections, and 27 of the
 * values those sections emit had no key — so the same record read French in
 * the tab and Title-Cased English in the timeline directly above it. Nothing
 * caught that: parity, referenced-key and untranslated are all green on a key
 * that does not exist.
 *
 * So this asserts the one property the timeline depends on: every value the
 * six sections can emit has a key in PORTAL.ENUM.STATUS.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { javaEnumConstants } from './java-enum.mjs';

const PORTAL = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const REPO = resolve(PORTAL, '..');
const ENUMS = resolve(REPO, 'hospital-core/src/main/java/com/example/hms/enums');

const constantsOf = (file, name) =>
  javaEnumConstants(readFileSync(resolve(ENUMS, file), 'utf8'), name);

/**
 * What ChartReviewServiceImpl puts in `ChartReviewTimelineEvent.status`, one
 * entry per `.section(Section.X)` in its timeline mappers. Keep this in step
 * with that method — a section added there and not here is unguarded.
 */
const TIMELINE_SECTIONS = {
  ENCOUNTER: () => constantsOf('EncounterStatus.java', 'EncounterStatus'),
  // `n.isSigned() ? "SIGNED" : "DRAFT"` — a literal pair, not an enum.
  NOTE: () => ['SIGNED', 'DRAFT'],
  RESULT: () => constantsOf('AbnormalFlag.java', 'AbnormalFlag'),
  MEDICATION: () => constantsOf('PrescriptionStatus.java', 'PrescriptionStatus'),
  // `i.getReportStatus() != null ? i.getReportStatus() : i.getStatus()` — either.
  IMAGING: () => [
    ...constantsOf('ImagingReportStatus.java', 'ImagingReportStatus'),
    ...constantsOf('ImagingOrderStatus.java', 'ImagingOrderStatus'),
  ],
  PROCEDURE: () => constantsOf('ProcedureOrderStatus.java', 'ProcedureOrderStatus'),
};

/** Groups that own a value the timeline can render; the pool copies from them. */
const OWNING_GROUPS = [
  'ENCOUNTER_STATUS',
  'PRESCRIPTION_STATUS',
  'IMAGING_REPORT_STATUS',
  'IMAGING_ORDER_STATUS',
  'PROCEDURE_ORDER_STATUS',
];

const pool = () =>
  JSON.parse(readFileSync(resolve(PORTAL, 'src/assets/i18n/en.json'), 'utf8')).PORTAL.ENUM.STATUS;

test('every timeline section reads its own Java enum', () => {
  // A renamed or moved enum file would otherwise make the check below vacuous:
  // zero constants is zero failures, which reads as a pass.
  for (const [section, read] of Object.entries(TIMELINE_SECTIONS)) {
    assert.ok(read().length > 0, `${section} resolved no constants`);
  }
});

test('PORTAL.ENUM.STATUS keys every value the chart-review timeline can render', () => {
  const keys = pool();
  const missing = [];
  for (const [section, read] of Object.entries(TIMELINE_SECTIONS)) {
    for (const value of read()) {
      if (!(value in keys)) missing.push(`${section}.${value}`);
    }
  }
  assert.deepEqual(
    missing,
    [],
    `unkeyed in PORTAL.ENUM.STATUS, so the timeline pill renders Title-Cased ` +
      `English for them: ${missing.join(', ')}`,
  );
});

test('the pool holds a wording one of its owning groups actually uses', () => {
  // The pool duplicates these values by design. Copying rather than
  // re-translating is what keeps the tab and the timeline reading the same
  // words; a later edit to one side only is the drift this catches.
  //
  // It is membership, not equality: one value can be owned by several groups
  // that legitimately disagree on gender agreement — CANCELLED is "Annulé" for
  // an encounter and "Annulée" for a procedure order — and a single pooled
  // badge can only carry one of them.
  for (const locale of ['en', 'fr', 'es']) {
    const enums = JSON.parse(
      readFileSync(resolve(PORTAL, `src/assets/i18n/${locale}.json`), 'utf8'),
    ).PORTAL.ENUM;
    const owned = new Map();
    for (const group of OWNING_GROUPS) {
      for (const [value, text] of Object.entries(enums[group] ?? {})) {
        if (!owned.has(value)) owned.set(value, new Set());
        owned.get(value).add(text);
      }
    }
    const drift = [];
    for (const [value, texts] of owned) {
      if (value in enums.STATUS && !texts.has(enums.STATUS[value])) {
        drift.push(
          `${locale} ${value}: pool "${enums.STATUS[value]}" is not ` +
            `[${[...texts].join(' | ')}]`,
        );
      }
    }
    assert.deepEqual(drift, [], `pooled wording drifted from its source: ${drift.join('; ')}`);
  }
});
