// QA round 16 guards on the clinical workflow screens (encounters, tracker,
// triage and intake forms, checkout, discharge, referrals, admissions,
// maternity, medical history, the doctor's patient flow). Each test fails if
// a class of defect fixed in round 16 comes back on these screens:
//   D999  an icon-only button with no accessible name, or a Material icon
//         inside a button that a screen reader announces by its ligature
//         ("edit", "delete");
//   D1004 a hand-built modal box with no dialog role or no label;
//   D992  a record shown by a slice of its UUID ("Consultation: 42a0451a");
//   D991  a shift handoff toast that says "Transfert" in French.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const portal = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const appDir = join(portal, 'src', 'app');
const FOLDERS = [
  'encounters',
  'patient-tracker',
  'nurse-station/triage-form',
  'nurse-station/nursing-intake-form',
  'checkout',
  'discharge',
  'referrals',
  'admissions',
  'maternity',
  'patients/medical-history-tab',
  'dashboard/doctor-patient-flow',
];

function files(dir, ext) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...files(p, ext));
    else if (name.endsWith(ext) && !name.endsWith('.spec.ts')) out.push(p);
  }
  return out;
}

const templates = FOLDERS.flatMap((f) => files(join(appDir, f), '.html'));
const sources = FOLDERS.flatMap((f) => files(join(appDir, f), '.ts'));
const where = (p, text, index) =>
  `${relative(appDir, p)}:${text.slice(0, index).split('\n').length}`;

const ICON = /<span\b[^>]*material-symbols[^>]*>[\s\S]*?<\/span>/g;
const ICON_TAG = /<span\b([^>]*material-symbols[^>]*)>/g;

test('the scan finds the clinical workflow templates', () => {
  assert.ok(templates.length >= 15, `only ${templates.length} templates found`);
});

test('D999: every icon-only button has an accessible name, and its icon is aria-hidden', () => {
  const unnamed = [];
  const announced = [];
  for (const p of templates) {
    const html = readFileSync(p, 'utf8');
    for (const m of html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/g)) {
      const [, attrs, inner] = m;
      const text = inner
        .replace(ICON, '')
        .replace(/<[^>]+>/g, '')
        .replace(/@if[^{]*\{|@else|[{}]/g, '')
        .trim();
      if (!text && !attrs.includes('aria-label')) unnamed.push(where(p, html, m.index));
      for (const t of inner.matchAll(ICON_TAG)) {
        if (!t[1].includes('aria-hidden')) {
          announced.push(where(p, html, m.index));
          break;
        }
      }
    }
  }
  assert.deepEqual(unnamed, [], 'icon-only buttons need aria-label');
  assert.deepEqual(announced, [], 'icons inside buttons need aria-hidden="true"');
});

test('D1004: every modal box is a labelled dialog', () => {
  const box = /^(modal|modal-content|modal-box|dialog|dialog-box|[\w-]+-dialog|[\w-]+-modal)$/;
  const bad = [];
  for (const p of templates) {
    const html = readFileSync(p, 'utf8');
    for (const m of html.matchAll(/<(div|section|aside)\b([^>]*\bclass="([^"]*)"[^>]*)>/g)) {
      if (!m[3].split(/\s+/).some((c) => box.test(c))) continue;
      const attrs = m[2];
      const isDialog =
        attrs.includes('role="dialog"') ||
        attrs.includes('role="alertdialog"') ||
        attrs.includes('appModalDialog');
      if (!isDialog || !attrs.includes('aria-label')) bad.push(where(p, html, m.index));
    }
  }
  assert.deepEqual(bad, []);
});

test('D992: no record is shown by a slice of its id', () => {
  const bad = [];
  const slice = /\b\w*(?:id|Id)\b\s*(?:\|\s*slice\s*:|\.slice\(\s*0|\.substring\(\s*0)/g;
  for (const p of [...templates, ...sources]) {
    const text = readFileSync(p, 'utf8');
    for (const m of text.matchAll(slice)) bad.push(where(p, text, m.index));
  }
  assert.deepEqual(bad, []);
});

test('D991: French shift handoff toasts say Transmission, never Transfert', () => {
  const fr = JSON.parse(readFileSync(join(portal, 'src', 'assets', 'i18n', 'fr.json'), 'utf8'));
  const bad = Object.entries(fr.NURSE.TOAST)
    .filter(([key, value]) => key.includes('HANDOFF') && /transfert/i.test(value))
    .map(([key]) => key);
  assert.deepEqual(bad, []);
});
