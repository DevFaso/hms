#!/usr/bin/env node
/**
 * QA D1357 / D1358 (round 30): a "required fields" refusal worded by a FIXED
 * sentence instead of the fields that are blank. The demographics dialog
 * said "Le prénom, le nom, le sexe et le téléphone sont obligatoires" when
 * only the date of birth was blank; the advance directive repeated "Ce champ
 * est obligatoire pour une directive." under every field and named none. The
 * round-30 sweep moved 26 such sites to `missingRequiredMessage`
 * (shared/required-fields.ts), which names exactly the blank fields.
 *
 * This test reads every component and template for a translation key shaped
 * like a fixed required-fields sentence (…REQUIRED_FIELDS, …FIELDS_REQUIRED,
 * …REQUIRED_MISSING, …MISSING_FIELDS). Only the naming key itself and the
 * sentences pinned below (neutral: they name no field, the fields are marked
 * one by one) may appear. A new one fails; a pin the tree no longer uses fails.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { relative } from 'node:path';

import { walk } from './walk.mjs';
import { PORTAL_DIR, SRC_DIR } from './template-gate.mjs';

const FIXED =
  /'([A-Z0-9_]+(?:\.[A-Z0-9_]+)*\.[A-Z0-9_]*(?:REQUIRED_FIELDS|FIELDS_REQUIRED|REQUIRED_MISSING|MISSING_FIELDS)[A-Z0-9_]*)'/g;

/** The key that names the blank fields: the fix, not the class. */
const NAMING = new Set(['COMMON.MISSING_REQUIRED_FIELDS']);

/** Sentences that name no field (each field is marked under itself), with why. */
const NEUTRAL = {
  'APPOINTMENTS.REQUIRED_FIELDS':
    'neutral "fill in every required field"; the reschedule date and start are marked one by one (refuseFields)',
  'LOGIN.ALL_FIELDS_REQUIRED': 'neutral; the sign-in form has two fields, both marked',
  'PORTAL.APPOINTMENTS.BOOKING.MISSING_FIELDS':
    'neutral "fill in every required field"; the booking steps mark their own fields',
  'PATIENTS.REQUIRED_FIELDS':
    'neutral "fill in every required field"; the registration form marks and focuses each blank field',
  'PROVIDER.REGISTER.REQUIRED_FIELDS':
    'neutral "fill in every field"; every field of the form is required',
  'PROVIDER.FORM.REQUIRED_FIELDS': 'neutral "fill in every required field before sending"',
  'USERS.TOAST.REQUIRED_FIELDS': 'neutral; the blank fields are marked one by one (refusal.ids)',
  'REGISTRIES.ENROLL_FIELDS_REQUIRED':
    'the cadence RANGE rule (1-365 days), shown only for a cadence given out of range; blanks are named (D1358)',
};

function sites() {
  const out = [];
  for (const file of walk(SRC_DIR, ['.ts', '.html'])) {
    if (file.endsWith('.spec.ts')) continue;
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(FIXED)) {
      out.push({ key: m[1], file: relative(PORTAL_DIR, file).replaceAll('\\', '/') });
    }
  }
  return out;
}

test('D1357/D1358: no required-fields refusal is a fixed sentence', () => {
  const fresh = sites()
    .filter((s) => !NAMING.has(s.key) && !(s.key in NEUTRAL))
    .map((s) => `${s.file}: '${s.key}' — name the blank fields with missingRequiredMessage`);
  assert.deepEqual(fresh, []);
});

test('every neutral pin is still used (a fixed sentence removed is unpinned too)', () => {
  const used = new Set(sites().map((s) => s.key));
  assert.deepEqual(
    Object.keys(NEUTRAL).filter((k) => !used.has(k)),
    [],
  );
});
