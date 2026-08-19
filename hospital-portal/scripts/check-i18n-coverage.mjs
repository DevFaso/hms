#!/usr/bin/env node
/**
 * i18n completeness gate (v1.0 / i18n / FR completeness gate, roadmap row 12).
 *
 * Recursively flattens every locale file under src/assets/i18n/<locale>.json
 * into a set of dot-paths (e.g. "ADMIN.QUICK_ACCESS",
 * "TENANT_LIFECYCLE.STATE.ACTIVE"), takes the EN locale as the baseline, and
 * for every other locale counts how many of EN's keys are also present and
 * carry a non-empty string value. Fails (exits non-zero) if any locale's
 * coverage falls below the threshold.
 *
 * Pure Node — no external dependencies, runs under the same Node 20 the
 * rest of the frontend pipeline uses (see .github/workflows/frontend-ci.yml).
 *
 * Usage:
 *   node scripts/check-i18n-coverage.mjs                  # default threshold 99
 *   I18N_COVERAGE_THRESHOLD=98 node scripts/check-i18n-coverage.mjs
 *   I18N_LOCALE_THRESHOLDS=fr:99,es:89 node scripts/check-i18n-coverage.mjs
 *   node scripts/check-i18n-coverage.mjs --report-only    # never exit non-zero
 *   node scripts/check-i18n-coverage.mjs --strict         # exact key parity
 *
 * The default 99% threshold matches the roadmap row 12 spec ("CI fails when
 * any locale is < 99% of EN"). Per-locale overrides via
 * I18N_LOCALE_THRESHOLDS let ES (currently mid-backfill) carry a lower floor
 * without softening FR, which is the row-12 deliverable. Do not lower a
 * threshold permanently to mask regression — the gate's value is the floor
 * it pins against backsliding.
 *
 * --strict (task 22, key-parity gate): now that every locale is backfilled
 * to 100%, thresholds alone would still let a locale drift one key at a
 * time and would never flag orphans. Strict mode fails on ANY missing key
 * and on ANY orphan key (present in a locale but absent from EN — usually
 * a leftover after an EN key was renamed). CI runs this mode; the
 * threshold machinery stays for local triage during a future backfill.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const I18N_DIR = resolve(SCRIPT_DIR, '..', 'src', 'assets', 'i18n');
const BASELINE_LOCALE = 'en';

const REPORT_ONLY = process.argv.includes('--report-only');
const STRICT = process.argv.includes('--strict');
const DEFAULT_THRESHOLD = Number(process.env.I18N_COVERAGE_THRESHOLD ?? '99');

if (Number.isNaN(DEFAULT_THRESHOLD) || DEFAULT_THRESHOLD < 0 || DEFAULT_THRESHOLD > 100) {
    console.error(`I18N_COVERAGE_THRESHOLD must be a number between 0 and 100, got: ${process.env.I18N_COVERAGE_THRESHOLD}`);
    process.exit(2);
}

/**
 * Parse I18N_LOCALE_THRESHOLDS in the form "fr:99,es:89" into a Map. Empty /
 * malformed entries are ignored with a warning so a typo doesn't silently
 * disable the gate.
 */
function parseLocaleThresholds(raw) {
    const out = new Map();
    if (!raw) return out;
    for (const entry of raw.split(',')) {
        const [locale, valueStr] = entry.split(':').map(s => s?.trim());
        if (!locale) continue;
        const value = Number(valueStr);
        if (Number.isNaN(value) || value < 0 || value > 100) {
            console.warn(`[i18n-coverage] ignoring malformed override "${entry}" — expected locale:0-100`);
            continue;
        }
        out.set(locale, value);
    }
    return out;
}

const LOCALE_THRESHOLDS = parseLocaleThresholds(process.env.I18N_LOCALE_THRESHOLDS);

function thresholdFor(locale) {
    return LOCALE_THRESHOLDS.get(locale) ?? DEFAULT_THRESHOLD;
}

/**
 * Recursively flatten an object into a Map of dot-path → leaf value.
 * Arrays are walked element-wise as `path.<index>`. Primitives at root
 * are returned as the empty path "".
 */
function flatten(obj, prefix = '', sink = new Map()) {
    if (obj === null || obj === undefined) {
        sink.set(prefix, obj);
        return sink;
    }
    if (typeof obj !== 'object') {
        sink.set(prefix, obj);
        return sink;
    }
    if (Array.isArray(obj)) {
        if (obj.length === 0) {
            sink.set(prefix, []);
            return sink;
        }
        obj.forEach((item, idx) => {
            const key = prefix ? `${prefix}.${idx}` : String(idx);
            flatten(item, key, sink);
        });
        return sink;
    }
    const keys = Object.keys(obj);
    if (keys.length === 0) {
        sink.set(prefix, {});
        return sink;
    }
    for (const k of keys) {
        const key = prefix ? `${prefix}.${k}` : k;
        flatten(obj[k], key, sink);
    }
    return sink;
}

/** A leaf is "covered" when it is a non-empty string after trimming. */
function isCovered(value) {
    return typeof value === 'string' && value.trim().length > 0;
}

function loadLocale(localeFile) {
    const path = join(I18N_DIR, localeFile);
    const raw = readFileSync(path, 'utf8');
    try {
        return JSON.parse(raw);
    } catch (err) {
        throw new Error(`failed to parse ${path}: ${err.message}`);
    }
}

function main() {
    const files = readdirSync(I18N_DIR).filter(f => f.endsWith('.json')).sort();
    if (files.length === 0) {
        console.error(`no locale files found under ${I18N_DIR}`);
        process.exit(2);
    }

    const baselineFile = `${BASELINE_LOCALE}.json`;
    if (!files.includes(baselineFile)) {
        console.error(`baseline locale ${baselineFile} not found under ${I18N_DIR}`);
        process.exit(2);
    }

    const baseline = flatten(loadLocale(baselineFile));
    const baselineKeys = [...baseline.keys()].filter(k => isCovered(baseline.get(k)));
    const baselineKeyCount = baselineKeys.length;

    if (baselineKeyCount === 0) {
        console.error(`baseline locale ${baselineFile} has zero translatable keys`);
        process.exit(2);
    }

    console.log(`Baseline (${BASELINE_LOCALE}): ${baselineKeyCount} translatable keys`);
    console.log(
        STRICT
            ? `Mode: strict key parity (any missing or orphan key fails) ${REPORT_ONLY ? '(report-only)' : ''}`
            : `Default threshold: ${DEFAULT_THRESHOLD}% ${REPORT_ONLY ? '(report-only)' : ''}`
    );
    if (LOCALE_THRESHOLDS.size > 0) {
        const overrides = [...LOCALE_THRESHOLDS.entries()].map(([k, v]) => `${k}=${v}%`).join(', ');
        console.log(`Per-locale overrides: ${overrides}`);
    }
    console.log();

    const otherLocales = files
        .filter(f => f !== baselineFile)
        .map(f => f.replace(/\.json$/, ''));

    let failed = false;
    const rows = [['Locale', 'Covered', 'Missing', 'Extra', 'Coverage %', 'Threshold %', 'Status']];

    for (const locale of otherLocales) {
        const flat = flatten(loadLocale(`${locale}.json`));
        let covered = 0;
        const missing = [];
        for (const key of baselineKeys) {
            if (isCovered(flat.get(key))) {
                covered += 1;
            } else {
                missing.push(key);
            }
        }
        // "Extra" keys: present in this locale, not in baseline — usually
        // an orphaned translation left after an EN key was renamed.
        const extras = [];
        for (const key of flat.keys()) {
            if (!isCovered(flat.get(key))) continue;
            if (!baseline.has(key)) extras.push(key);
        }
        const coveragePct = (covered / baselineKeyCount) * 100;
        const localeThreshold = thresholdFor(locale);
        // Strict parity: any missing or orphan key fails, regardless of
        // percentage thresholds.
        const passes = STRICT
            ? missing.length === 0 && extras.length === 0
            : coveragePct >= localeThreshold;
        if (!passes) failed = true;
        rows.push([
            locale,
            String(covered),
            String(missing.length),
            String(extras.length),
            coveragePct.toFixed(2),
            STRICT ? 'strict' : `${localeThreshold}`,
            passes ? '✓' : STRICT ? '✗ PARITY BROKEN' : '✗ BELOW THRESHOLD'
        ]);

        if (missing.length > 0) {
            console.log(`Missing in ${locale} (${missing.length}):`);
            missing.slice(0, 25).forEach(k => console.log(`  - ${k}`));
            if (missing.length > 25) console.log(`  … and ${missing.length - 25} more`);
            console.log();
        }
        if (extras.length > 0) {
            console.log(`Orphan keys in ${locale} (${extras.length}):`);
            extras.slice(0, 10).forEach(k => console.log(`  - ${k}`));
            if (extras.length > 10) console.log(`  … and ${extras.length - 10} more`);
            console.log();
        }
    }

    // Pretty-print the summary table.
    const widths = rows[0].map((_, c) => Math.max(...rows.map(r => r[c].length)));
    const fmt = (r) => r.map((cell, i) => cell.padEnd(widths[i])).join('  ');
    console.log(fmt(rows[0]));
    console.log(widths.map(w => '-'.repeat(w)).join('  '));
    rows.slice(1).forEach(r => console.log(fmt(r)));

    if (failed) {
        const verdict = REPORT_ONLY ? 'WARN (report-only)' : 'FAIL';
        console.error(`\n${verdict}: at least one locale falls below its threshold.`);
        if (!REPORT_ONLY) process.exit(1);
        return;
    }
    console.log(`\nOK: every locale meets its threshold.`);
}

main();
