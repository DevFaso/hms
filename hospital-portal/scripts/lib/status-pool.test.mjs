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

/*
 * There is deliberately NO test that the pool matches the group a value comes
 * from. That invariant is false, and enforcing it caused a regression: the
 * pool is a COMPROMISE vocabulary whose subject differs from any single
 * owning group. DISCONTINUED is « Interrompu » in the pool because
 * my-medications renders it against « le médicament », and « Interrompue » in
 * PRESCRIPTION_STATUS because that group's subject is « une ordonnance ».
 * Copying one over the other broke grammar on three patient-facing screens.
 *
 * What matters is coverage, above: a value with NO key renders Title-Cased
 * English, which is never right. Which French word is right is a judgement,
 * not an invariant.
 */
