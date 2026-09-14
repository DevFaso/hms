#!/usr/bin/env node
/**
 * i18n *translation* gate — the companion to check-i18n-coverage.mjs.
 *
 * The coverage gate asks "does every EN key exist in FR and ES?". It has
 * passed at 100% for months while 1041 French values were still the English
 * string, because a key that was copied from EN and never translated is
 * present, non-empty, and therefore invisible to a presence check. On a
 * French-first product that is the difference between a passing build and a
 * nurse reading "Fall Risk" at the bedside.
 *
 * So this gate asks the other question: for every key, is the locale's value
 * BYTE-IDENTICAL to the English one? An identical value is either
 *   (a) a string French genuinely shares with English ("Description",
 *       "Instagram", "SMS", "Format"), which belongs in the allowlist, or
 *   (b) an untranslated string, which is a defect.
 *
 * The allowlist pins the shared TEXT, not just the key:
 *
 *     { "fr": { "COMMON.ACTION": "Action" } }
 *
 * If the English text later changes, the pin no longer matches and the gate
 * fires again — so an allowlist entry cannot silently outlive the wording it
 * was granted for. Adding a key here is a claim that a French speaker looked
 * at it; it is not a way to make the build green.
 *
 * FR fails the build. ES is reported against a ratchet (ES_MAX_UNTRANSLATED,
 * seeded at the 1377 it stands at today)
 * rather than failing, because the ES backfill has not been done and pinning
 * ~1000 untranslated Spanish strings into an allowlist would be a lie about
 * their state. The ratchet still stops ES getting worse.
 *
 * Pure Node, no dependencies — same shape as the sibling gate.
 *
 * Usage:
 *   node scripts/check-i18n-untranslated.mjs
 *   node scripts/check-i18n-untranslated.mjs --report-only   # never exit non-zero
 *   node scripts/check-i18n-untranslated.mjs --write-allowlist
 *       Regenerates the allowlist from the CURRENT state. Only ever run this
 *       after a human has read the resulting diff — it will happily pin real
 *       untranslated English if you let it.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const I18N_DIR = resolve(SCRIPT_DIR, '..', 'src', 'assets', 'i18n');
const ALLOWLIST_PATH = resolve(SCRIPT_DIR, 'i18n-untranslated-allowlist.json');

const BASELINE_LOCALE = 'en';
/** Fails the build. */
const ENFORCED_LOCALES = ['fr'];
/** Reported against a ceiling, not allowlisted — see the header. */
const RATCHET_LOCALES = { es: Number(process.env.ES_MAX_UNTRANSLATED ?? '1377') };

const REPORT_ONLY = process.argv.includes('--report-only');
const WRITE_ALLOWLIST = process.argv.includes('--write-allowlist');

function flatten(node, prefix = '', out = new Map()) {
    for (const [key, value] of Object.entries(node)) {
        const path = prefix ? `${prefix}.${key}` : key;
        if (value && typeof value === 'object' && !Array.isArray(value)) {
            flatten(value, path, out);
        } else if (typeof value === 'string') {
            out.set(path, value);
        }
    }
    return out;
}

function readLocale(locale) {
    return flatten(JSON.parse(readFileSync(resolve(I18N_DIR, `${locale}.json`), 'utf8')));
}

/**
 * Values that carry no translatable words at all: pure punctuation, digits,
 * a bare interpolation, or a lone placeholder. These are skipped rather than
 * allowlisted — listing "{{count}}" as a French word would be noise.
 */
function hasTranslatableText(value) {
    const stripped = value.replace(/\{\{[^}]*\}\}/g, '').replace(/\{\d+\}/g, '');
    return /[A-Za-zÀ-ÿ]{2,}/.test(stripped);
}

function identicalKeys(baseline, locale) {
    const out = [];
    for (const [key, value] of locale) {
        if (!baseline.has(key)) continue;
        if (baseline.get(key) !== value) continue;
        if (!hasTranslatableText(value)) continue;
        out.push(key);
    }
    return out.sort();
}

const baseline = readLocale(BASELINE_LOCALE);
const allowlist = JSON.parse(readFileSync(ALLOWLIST_PATH, 'utf8'));

if (WRITE_ALLOWLIST) {
    const next = {};
    for (const locale of ENFORCED_LOCALES) {
        next[locale] = {};
        for (const key of identicalKeys(baseline, readLocale(locale))) {
            next[locale][key] = baseline.get(key);
        }
    }
    writeFileSync(ALLOWLIST_PATH, `${JSON.stringify(next, null, 2)}\n`, 'utf8');
    console.log(`[i18n-untranslated] allowlist rewritten — READ THE DIFF before committing it.`);
    process.exit(0);
}

let failed = false;

for (const locale of ENFORCED_LOCALES) {
    const pinned = allowlist[locale] ?? {};
    const identical = identicalKeys(baseline, readLocale(locale));
    const offenders = [];
    const stale = [];

    for (const key of identical) {
        const text = baseline.get(key);
        if (!(key in pinned)) offenders.push({ key, text });
        else if (pinned[key] !== text) stale.push({ key, was: pinned[key], now: text });
    }
    // An allowlist entry whose key is no longer identical has served its
    // purpose — the string got translated. Report it so the list stays honest.
    const obsolete = Object.keys(pinned).filter(key => !identical.includes(key));

    console.log(
        `[i18n-untranslated] ${locale.toUpperCase()}: ${identical.length} identical to EN ` +
        `(${Object.keys(pinned).length} allowlisted, ${offenders.length} not)`
    );

    for (const { key, text } of offenders) {
        console.error(`  UNTRANSLATED ${locale}: ${key} = ${JSON.stringify(text)}`);
    }
    for (const { key, was, now } of stale) {
        console.error(
            `  STALE ALLOWLIST ${locale}: ${key} was pinned as ${JSON.stringify(was)}, ` +
            `English is now ${JSON.stringify(now)} — re-check the translation.`
        );
    }
    for (const key of obsolete) {
        console.error(`  OBSOLETE ALLOWLIST ${locale}: ${key} is translated now; drop the entry.`);
    }

    if (offenders.length || stale.length || obsolete.length) failed = true;
}

for (const [locale, ceiling] of Object.entries(RATCHET_LOCALES)) {
    const count = identicalKeys(baseline, readLocale(locale)).length;
    console.log(`[i18n-untranslated] ${locale.toUpperCase()}: ${count} identical to EN (ceiling ${ceiling})`);
    if (count > ceiling) {
        console.error(
            `  ${locale.toUpperCase()} went backwards: ${count} > ${ceiling}. Translate the new keys, ` +
            `or lower nothing — the ceiling only ever comes down.`
        );
        failed = true;
    }
}

if (failed && !REPORT_ONLY) process.exit(1);
