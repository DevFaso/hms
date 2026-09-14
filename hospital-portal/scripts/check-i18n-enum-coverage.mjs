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
 * pool across 24 unrelated call sites, so its group holds far more than any
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

/**
 * MUST stay identical to EnumLabelPipe.toUpperSnake (enum-label.pipe.ts), or
 * this gate verifies a group the pipe never reads. An underscore goes at a
 * lower/digit-to-upper boundary only: a domain like `patientMRN` is
 * PATIENT_MRN to the pipe, and splitting before every capital would have the
 * gate checking PATIENT_M_R_N and passing against a group nothing renders.
 */
export const groupOf = (domain) => domain.replaceAll(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();

/**
 * The constants of a Java enum: the tokens before the `;` that ends the
 * constant list (or before the closing brace when there is no body).
 *
 * Comments AND string literals are blanked before that split, and for the same
 * reason. The first version of this parser split on the raw text and ended
 * `PrescriptionStatus` at the semicolon inside "Medication not in stock;
 * awaiting restock", silently hiding seven partner-pharmacy statuses — it
 * reported them as values the enum "cannot emit" and exited 0. A `;` inside a
 * constant's own argument, as in `A("x;y")`, does exactly the same thing.
 *
 * Constants are then read as tokens rather than line-by-line, so an annotated
 * constant (`@Deprecated A`), several on one line (`A, B, C`), and one that
 * carries a class body (`A { void f() {} },`) are all found.
 */
export function javaEnumConstants(source, name) {
  const decl = new RegExp(`enum\\s+${name}\\s*(?:implements[^{]*)?\\{`).exec(source);
  if (!decl) return null;

  const open = decl.index + decl[0].length - 1;
  const blanked = blankCommentsAndStrings(source);

  // Walk to the enum's own closing brace over the blanked copy, so a brace
  // inside a comment or string cannot end it early.
  let depth = 0;
  let close = open;
  for (; close < blanked.length; close += 1) {
    if (blanked[close] === '{') depth += 1;
    else if (blanked[close] === '}') {
      depth -= 1;
      if (depth === 0) break;
    }
  }

  // The constant list ends at the first `;` at the enum's own brace depth —
  // one nested inside a constant's class body does not end it.
  const body = blanked.slice(open + 1, close);
  let end = body.length;
  depth = 0;
  for (let i = 0; i < body.length; i += 1) {
    if (body[i] === '{' || body[i] === '(') depth += 1;
    else if (body[i] === '}' || body[i] === ')') depth -= 1;
    else if (body[i] === ';' && depth === 0) {
      end = i;
      break;
    }
  }

  // In the constant list, a name is a bare token that is not part of an
  // annotation and not inside a constant's arguments or class body.
  const list = body.slice(0, end);
  const names = [];
  depth = 0;
  for (const match of list.matchAll(/@?[A-Za-z_$][\w$]*|[(){}]/g)) {
    const token = match[0];
    if ('({'.includes(token)) depth += 1;
    else if (')}'.includes(token)) depth -= 1;
    else if (depth === 0 && !token.startsWith('@') && /^[A-Z][A-Z0-9_]*$/.test(token)) {
      names.push(token);
    }
  }
  return names;
}

/** Replace every comment and string literal with spaces, preserving offsets. */
function blankCommentsAndStrings(source) {
  const out = [...source];
  let i = 0;
  const blank = (from, to) => {
    for (let k = from; k < to && k < out.length; k += 1) {
      if (out[k] !== '\n') out[k] = ' ';
    }
  };
  while (i < source.length) {
    const two = source.slice(i, i + 2);
    if (two === '/*') {
      const end = source.indexOf('*/', i + 2);
      const stop = end === -1 ? source.length : end + 2;
      blank(i, stop);
      i = stop;
    } else if (two === '//') {
      let end = source.indexOf('\n', i);
      if (end === -1) end = source.length;
      blank(i, end);
      i = end;
    } else if (source[i] === '"' || source[i] === "'") {
      const quote = source[i];
      let k = i + 1;
      while (k < source.length && source[k] !== quote) k += source[k] === '\\' ? 2 : 1;
      blank(i, k + 1);
      i = k + 1;
    } else {
      i += 1;
    }
  }
  return out.join('');
}

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
  if ('reason' in entry && (typeof entry.reason !== 'string' || entry.reason.trim() === '')) {
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
  for (const file of walk(SRC)) {
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

    const group = entry.group ?? groupOf(domain);
    const keys = enumGroups[group] ?? {};
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
      `across ${used.size - exempt} checked, ${exempt} exempt (not counted above)`,
  );
  for (const note of notes) console.log(`  note: ${note}`);
  for (const error of errors) console.error(`  ${error}`);

  if (errors.length && !REPORT_ONLY) process.exit(1);
}

// Only run the gate when invoked as a command; the parser and groupOf are
// imported by scripts/check-i18n-enum-coverage.test.mjs.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
