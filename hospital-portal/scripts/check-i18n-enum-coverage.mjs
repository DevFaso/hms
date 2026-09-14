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
 * enum can emit are reported but do not fail: a group may deliberately hold
 * more than one enum sends. (`status` is the shared pool, but it is
 * reason-exempt and returns before that comparison, so it never produces the
 * note.)
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
import { javaEnumConstants, groupOf, blankCommentsAndStrings } from './lib/java-enum.mjs';
import { validateDeclaration, enumNameOf } from './lib/enum-domains.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const PORTAL_DIR = resolve(SCRIPT_DIR, '..');
const REPO_DIR = resolve(PORTAL_DIR, '..');
const SRC = resolve(PORTAL_DIR, 'src', 'app');
const I18N_DIR = resolve(PORTAL_DIR, 'src', 'assets', 'i18n');
const BASELINE = 'en';
/** A collision is only a defect in the locale it happens in, so check all three. */
const LOCALES = [BASELINE, 'fr', 'es'];
const EN_PATH = resolve(I18N_DIR, `${BASELINE}.json`);
const DOMAINS_PATH = resolve(SCRIPT_DIR, 'i18n-enum-domains.json');

const REPORT_ONLY = process.argv.includes('--report-only');

/** `| enumLabel: 'prescriptionStatus'`, with whatever whitespace. */
const PIPE_CALL = /enumLabel\s*:\s*'([A-Za-z_][A-Za-z0-9_]*)'/g;

function main() {
  const en = JSON.parse(readFileSync(EN_PATH, 'utf8'));
  const enumGroups = en?.PORTAL?.ENUM ?? {};
  const declared = JSON.parse(readFileSync(DOMAINS_PATH, 'utf8'));

  /** domain -> the files that pipe it, so a failure names somewhere to go. */
  const used = new Map();
  // .ts as well as .html, for a component with an inline `template:` — but
  // comments are blanked first and .spec.ts is skipped, or the pipe's own
  // TSDoc examples and a TODO in a spec would be recorded as call sites and
  // any future doc example would be a build break. (The sibling gate
  // check-i18n-referenced-keys.mjs has excluded .spec.ts all along.)
  for (const file of walk(SRC, ['.html', '.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const raw = readFileSync(file, 'utf8');
    const text = file.endsWith('.ts') ? blankCommentsAndStrings(raw) : raw;
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
  /** Every group a piped domain resolves to, whether or not it is enum-backed. */
  const groupsInUse = new Set();

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

    groupsInUse.add(entry.group ?? groupOf(domain));
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
      const name = enumNameOf(path);
      // null and [] are different failures, and the lib's contract says so:
      // null means no enum of that name is in the file — almost always a
      // filename/type-name mismatch in the declaration, not a parser problem.
      const found = javaEnumConstants(readFileSync(javaPath, 'utf8'), name);
      if (found === null) {
        errors.push(
          `NO SUCH ENUM ${domain} -> ${path} contains no \`enum ${name}\`; ` +
            `check the declaration names the file whose type it means.`,
        );
        broken = true;
        continue;
      }
      if (found.length === 0) {
        errors.push(`UNPARSEABLE ENUM ${domain} -> ${path} (enum ${name} parsed to no constants).`);
        broken = true;
        continue;
      }
      for (const value of found) constants.add(value);
    }
    if (broken) continue;

    checkedDomains += 1;
    const group = entry.group ?? groupOf(domain);
    const keys = enumGroups[group] ?? {};
    const enumName = paths.length > 1 ? 'one of the declared enums' : enumNameOf(paths[0]);
    const missing = [...constants].filter((value) => !(value in keys));
    const extra = Object.keys(keys).filter((key) => !constants.has(key));
    checked += constants.size;
    covered += constants.size - missing.length;
    for (const value of missing) {
      errors.push(
        `UNKEYED ${group}.${value} — ${enumName} can send it and ${where[0]} ` +
          `pipes it, so it renders Title-Cased English.`,
      );
    }
    if (extra.length) {
      notes.push(
        `${group}: ${extra.length} key(s) no declared enum can emit — ${extra.join(', ')}`,
      );
    }
  }

  // Two distinct states that render as the same word are invisible to every
  // other gate: parity, referenced-key and untranslated are all green on a
  // pool where ACKNOWLEDGED and CONFIRMED both read "Confirmé". This runs over
  // every group a piped domain resolves to — including the reason-exempt ones,
  // which is where the shared pool lives and where the collisions actually
  // are. Reported, not failed: some pairs are genuine synonyms.
  for (const locale of LOCALES) {
    const groups =
      locale === BASELINE
        ? enumGroups
        : (JSON.parse(readFileSync(resolve(I18N_DIR, `${locale}.json`), 'utf8'))?.PORTAL?.ENUM ??
          {});
    for (const group of [...groupsInUse].sort()) {
      const byText = new Map();
      for (const [key, text] of Object.entries(groups[group] ?? {})) {
        if (!byText.has(text)) byText.set(text, []);
        byText.get(text).push(key);
      }
      for (const [text, ks] of byText) {
        if (ks.length > 1) {
          notes.push(
            `${group} [${locale}]: ${ks.join(' and ')} both render ${JSON.stringify(text)}`,
          );
        }
      }
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
