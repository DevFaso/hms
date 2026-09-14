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
 * declared in scripts/i18n-enum-domains.json, which maps it either to the
 * Java enum that defines its values, or to a written reason why no single
 * enum does. An undeclared domain fails: the point is that adding a new
 * `enumLabel:` call makes someone say where its values come from.
 *
 * Missing keys FAIL — that is English on a French screen. Keys the enum
 * cannot emit are reported but do not fail: some groups deliberately carry a
 * richer vocabulary than one enum (ENCOUNTER_TYPE names DENTAL and
 * VACCINATION, which EncounterType has never had). Dead keys are only a
 * problem when they are *aliases* of values that also exist in full form,
 * which is how DISCHARGE_DISPOSITION came to hold both SNF and
 * SKILLED_NURSING_FACILITY; that one is a review call, not a build failure.
 *
 * Pure Node, no dependencies — same shape as the sibling gates.
 *
 * Usage:
 *   node scripts/check-i18n-enum-coverage.mjs
 *   node scripts/check-i18n-enum-coverage.mjs --report-only   # never exit non-zero
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const PORTAL_DIR = resolve(SCRIPT_DIR, '..');
const REPO_DIR = resolve(PORTAL_DIR, '..');
const SRC = resolve(PORTAL_DIR, 'src', 'app');
const EN_PATH = resolve(PORTAL_DIR, 'src', 'assets', 'i18n', 'en.json');
const DOMAINS_PATH = resolve(SCRIPT_DIR, 'i18n-enum-domains.json');

const REPORT_ONLY = process.argv.includes('--report-only');

/** `| enumLabel: 'prescriptionStatus'`, with whatever whitespace. */
const PIPE_CALL = /enumLabel\s*:\s*'([A-Za-z_][A-Za-z0-9_]*)'/g;
/** The pipe upper-snakes the domain to reach its group. */
const groupOf = domain => domain.replace(/(?<!^)(?=[A-Z])/g, '_').toUpperCase();

function walk(dir, out = []) {
    for (const entry of readdirSync(dir)) {
        const full = resolve(dir, entry);
        if (statSync(full).isDirectory()) walk(full, out);
        else if (entry.endsWith('.html')) out.push(full);
    }
    return out;
}

/**
 * The constants of a Java enum: everything before the first `;` (which ends
 * the constant list when the enum has a body) that reads as a bare
 * UPPER_SNAKE token at the start of a line, with or without arguments.
 *
 * Comments are stripped BEFORE that split. PrescriptionStatus documents
 * PENDING_STOCK as "Medication not in stock; awaiting restock" — splitting
 * first ended the list at that semicolon and silently hid the seven partner-
 * pharmacy statuses, which is exactly the kind of quiet miss this gate exists
 * to prevent.
 */
export function javaEnumConstants(source, name) {
    const decl = new RegExp(`enum\\s+${name}\\s*(?:implements[^{]*)?\\{`).exec(source);
    if (!decl) return null;
    let depth = 0;
    let i = decl.index + decl[0].length - 1;
    for (; i < source.length; i += 1) {
        if (source[i] === '{') depth += 1;
        else if (source[i] === '}') {
            depth -= 1;
            if (depth === 0) break;
        }
    }
    const body = source
        .slice(decl.index + decl[0].length, i)
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/[^\n]*/g, '')
        .split(';')[0];
    return [...body.matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|$)/gm)].map(m => m[1]);
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

for (const domain of [...used.keys()].sort()) {
    const where = [...used.get(domain)].sort();
    const entry = declared[domain];
    if (!entry) {
        errors.push(
            `UNDECLARED DOMAIN ${domain} (${where[0]}${where.length > 1 ? ` +${where.length - 1}` : ''}) — ` +
            `add it to scripts/i18n-enum-domains.json with the Java enum that defines its values, ` +
            `or with a reason no single enum does.`
        );
        continue;
    }
    if (!entry.enum) {
        notes.push(`${domain}: not checked — ${entry.reason ?? 'no reason given'}`);
        continue;
    }
    const javaPath = resolve(REPO_DIR, entry.enum);
    if (!existsSync(javaPath)) {
        errors.push(`MISSING ENUM FILE ${domain} -> ${entry.enum} does not exist.`);
        continue;
    }
    const name = entry.enum.split('/').pop().replace(/\.java$/, '');
    const constants = javaEnumConstants(readFileSync(javaPath, 'utf8'), name);
    if (!constants || constants.length === 0) {
        errors.push(`UNPARSEABLE ENUM ${domain} -> ${entry.enum} (no constants found for ${name}).`);
        continue;
    }
    const group = entry.group ?? groupOf(domain);
    const keys = enumGroups[group] ?? {};
    const missing = constants.filter(value => !(value in keys));
    const extra = Object.keys(keys).filter(key => !constants.includes(key));
    checked += constants.length;
    covered += constants.length - missing.length;
    for (const value of missing) {
        errors.push(
            `UNKEYED ${group}.${value} — ${name} can send it and ${where[0]} pipes it, ` +
            `so it renders Title-Cased English.`
        );
    }
    if (extra.length) {
        notes.push(`${group}: ${extra.length} key(s) ${name} cannot emit — ${extra.join(', ')}`);
    }
}

console.log(
    `[i18n-enum] ${used.size} piped domain(s), ${covered}/${checked} backend enum values keyed`
);
for (const note of notes) console.log(`  note: ${note}`);
for (const error of errors) console.error(`  ${error}`);

if (errors.length && !REPORT_ONLY) process.exit(1);
}

// Only run the gate when invoked as a command; the parser is imported by
// scripts/check-i18n-enum-coverage.test.mjs.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
