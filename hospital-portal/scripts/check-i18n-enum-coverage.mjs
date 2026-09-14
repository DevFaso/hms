#!/usr/bin/env node
/**
 * i18n *enum* gate — the third companion to check-i18n-coverage.mjs and
 * check-i18n-untranslated.mjs.
 *
 * The parity gate asks "does every EN key exist in FR?". The translation gate
 * asks "is the FR value actually French?". Both passed at 100% while the
 * patient portal rendered "SKILLED_NURSING_FACILITY" as "Skilled Nursing
 * Facility" to a patient in Ouagadougou, because the key was never written at
 * all — and a key that does not exist cannot be missing a translation.
 *
 * That is the EnumLabelPipe's fallback chain doing its job too well:
 *
 *     PORTAL.ENUM.<GROUP>.<VALUE>  ->  English LABELS map  ->  Title Case
 *
 * The last step never fails, never warns, and renders English. So this gate
 * asks the question the other two structurally cannot: for every domain a
 * template pipes through `| enumLabel: '<domain>'`, can the API send a value
 * that has no key?
 *
 * Answering it means reading the backend enum, so every domain must be
 * declared in scripts/i18n-enum-domains.json with exactly one of:
 *
 *   "enum":   one Java enum file
 *   "enums":  several, checked as a union — one badge, several emitters
 *   "reason": why no enum backs it (a String column, a portal-side vocabulary,
 *             or a status a service *derives* rather than reads)
 *
 * An undeclared domain fails, and so does a hollow or misspelled entry: the
 * point is that adding an `enumLabel:` call makes someone say where its values
 * come from, and `{}` must not be the cheapest way to silence the question.
 *
 * Missing keys FAIL — that is English on a French screen. Keys no declared
 * enum can emit are reported but do not fail: `status` is a deliberate shared
 * pool across 13 unrelated call sites, so its group holds far more than any
 * one of them sends.
 *
 * WHAT THIS GATE CANNOT SEE, and neither can any other:
 *   - a *derived* status — `PatientMedicationServiceImpl.resolveStatus` invents
 *     ACTIVE / COMPLETED / DISCONTINUED / ON_HOLD from a PrescriptionStatus, so
 *     no enum holds that vocabulary. Those domains carry a `reason` naming the
 *     deriving method.
 *   - a field rendered RAW, with no pipe at all. See the standing-debt bullet
 *     in tasklist.md; this gate checks piped domains, not unpiped fields.
 *
 * Pure Node, no dependencies — same shape as the sibling gates.
 *
 * Usage:
 *   node scripts/check-i18n-enum-coverage.mjs
 *   node scripts/check-i18n-enum-coverage.mjs --report-only   # never exit non-zero
 */
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './lib/walk.mjs';
import { javaEnumConstants, groupOf } from './lib/java-enum.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const PORTAL_DIR = resolve(SCRIPT_DIR, '..');
const REPO_DIR = resolve(PORTAL_DIR, '..');
const SRC = resolve(PORTAL_DIR, 'src', 'app');
const EN_PATH = resolve(PORTAL_DIR, 'src', 'assets', 'i18n', 'en.json');
const DOMAINS_PATH = resolve(SCRIPT_DIR, 'i18n-enum-domains.json');

const REPORT_ONLY = process.argv.includes('--report-only');

/** `| enumLabel: 'prescriptionStatus'`, with whatever whitespace. */
const PIPE_CALL = /enumLabel\s*:\s*'([A-Za-z_][A-Za-z0-9_]*)'/g;
/** Only these may appear in a declaration; anything else is a typo. */
const DECLARATION_KEYS = ['enum', 'enums', 'group', 'reason'];

/** Fails on a hollow or misspelled entry rather than treating it as exempt. */
function validateDeclaration(domain, entry, errors) {
  if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
    errors.push(`BAD DECLARATION ${domain} — the value must be an object.`);
    return false;
  }
  const unknown = Object.keys(entry).filter((key) => !DECLARATION_KEYS.includes(key));
  if (unknown.length) {
    errors.push(
      `BAD DECLARATION ${domain} — unknown propert${unknown.length > 1 ? 'ies' : 'y'} ` +
        `${unknown.join(', ')}; expected one of ${DECLARATION_KEYS.join(', ')}.`,
    );
    return false;
  }
  const sources = ['enum', 'enums', 'reason'].filter((key) => key in entry);
  if (sources.length !== 1) {
    errors.push(
      `BAD DECLARATION ${domain} — declare exactly one of enum / enums / reason ` +
        `(found ${sources.length ? sources.join(' + ') : 'none'}).`,
    );
    return false;
  }
  // Counting the keys is not enough: `{"enums": []}` declared exactly one and
  // checked nothing, which made it cheaper than the `{}` this function exists
  // to reject, and `{"enum": null}` died on an unhandled TypeError instead of
  // producing the message below.
  const filled = (value) => typeof value === 'string' && value.trim() !== '';
  if ('enum' in entry && !filled(entry.enum)) {
    errors.push(`BAD DECLARATION ${domain} — enum must be a path to a .java file.`);
    return false;
  }
  if (
    'enums' in entry &&
    (!Array.isArray(entry.enums) || entry.enums.length === 0 || !entry.enums.every(filled))
  ) {
    errors.push(`BAD DECLARATION ${domain} — enums must be a non-empty array of .java paths.`);
    return false;
  }
  if ('group' in entry && !filled(entry.group)) {
    errors.push(`BAD DECLARATION ${domain} — group must be a non-empty string.`);
    return false;
  }
  if ('reason' in entry && !filled(entry.reason)) {
    errors.push(`BAD DECLARATION ${domain} — an exemption needs a reason someone can read.`);
    return false;
  }
  return true;
}

function main() {
  const en = JSON.parse(readFileSync(EN_PATH, 'utf8'));
  const enumGroups = en?.PORTAL?.ENUM ?? {};
  const declared = JSON.parse(readFileSync(DOMAINS_PATH, 'utf8'));

  /** domain -> the templates that pipe it, so a failure names somewhere to go. */
  const used = new Map();
  // .ts as well as .html: components with an inline `template:` were invisible
  // to both the UNDECLARED check and the coverage check.
  for (const file of walk(SRC, ['.html', '.ts'])) {
    const text = readFileSync(file, 'utf8');
    for (const [, domain] of text.matchAll(PIPE_CALL)) {
      if (!used.has(domain)) used.set(domain, new Set());
      used.get(domain).add(relative(PORTAL_DIR, file).replaceAll('\\', '/'));
    }
  }

  const errors = [];
  const notes = [];
  let checked = 0;
  let covered = 0;
  let exempt = 0;
  // Only the domains whose constants were actually compared: an undeclared,
  // unparseable or bad-declaration domain contributes nothing and must not
  // inflate the ratio a reader scans first.
  let checkedDomains = 0;

  for (const domain of [...used.keys()].sort()) {
    const where = [...used.get(domain)].sort();
    const at = `${where[0]}${where.length > 1 ? ` +${where.length - 1}` : ''}`;
    if (!(domain in declared)) {
      errors.push(
        `UNDECLARED DOMAIN ${domain} (${at}) — add it to ` +
          `scripts/i18n-enum-domains.json with the Java enum(s) that define its ` +
          `values, or with a reason no enum does.`,
      );
      continue;
    }
    const entry = declared[domain];
    if (!validateDeclaration(domain, entry, errors)) continue;

    if (entry.reason) {
      exempt += 1;
      notes.push(`${domain}: not checked — ${entry.reason}`);
      continue;
    }

    const paths = entry.enums ?? [entry.enum];
    const constants = new Set();
    let broken = false;
    for (const path of paths) {
      const javaPath = resolve(REPO_DIR, path);
      if (!existsSync(javaPath)) {
        errors.push(`MISSING ENUM FILE ${domain} -> ${path} does not exist.`);
        broken = true;
        continue;
      }
      const name = path
        .split('/')
        .pop()
        .replace(/\.java$/, '');
      const found = javaEnumConstants(readFileSync(javaPath, 'utf8'), name);
      if (!found || found.length === 0) {
        errors.push(`UNPARSEABLE ENUM ${domain} -> ${path} (no constants found for ${name}).`);
        broken = true;
        continue;
      }
      for (const value of found) constants.add(value);
    }
    if (broken) continue;

    checkedDomains += 1;
    const group = entry.group ?? groupOf(domain);
    const keys = enumGroups[group] ?? {};
    const enumName =
      paths.length > 1
        ? 'one of the declared enums'
        : paths[0]
            .split('/')
            .pop()
            .replace(/\.java$/, '');
    const missing = [...constants].filter((value) => !(value in keys));
    const extra = Object.keys(keys).filter((key) => !constants.has(key));
    checked += constants.size;
    covered += constants.size - missing.length;
    for (const value of missing) {
      errors.push(
        `UNKEYED ${group}.${value} — ${
          paths.length > 1
            ? 'one of the declared enums'
            : paths[0]
                .split('/')
                .pop()
                .replace(/\.java$/, '')
        } ` + `can send it and ${where[0]} pipes it, so it renders Title-Cased English.`,
      );
    }
    if (extra.length) {
      notes.push(
        `${group}: ${extra.length} key(s) no declared enum can emit — ${extra.join(', ')}`,
      );
    }
  }

  console.log(
    `[i18n-enum] ${used.size} piped domain(s): ${covered}/${checked} enum values keyed ` +
      `across ${checkedDomains} checked, ${exempt} exempt (not counted above)`,
  );
  for (const note of notes) console.log(`  note: ${note}`);
  for (const error of errors) console.error(`  ${error}`);

  if (errors.length && !REPORT_ONLY) process.exit(1);
}

// Unconditional, like every sibling gate. The `import.meta.url ===
// process.argv[1]` guard this used to carry is false under a symlinked
// directory or a drive-letter case difference — and a gate that prints nothing
// and exits 0 is worse than no gate at all.
main();
