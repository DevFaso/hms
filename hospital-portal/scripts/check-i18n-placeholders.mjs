#!/usr/bin/env node
/**
 * i18n placeholder gate (QA D837 / D867).
 *
 * Fails when a template writes an example placeholder as a literal instead of
 * a translated key, and when a locale value carries a US phone format, a Java
 * type name, the English "e.g." in French or Spanish, or an English example
 * word kept as is. The rules, and why the other i18n gates cannot see these,
 * are in scripts/lib/placeholder-scan.mjs.
 *
 * A value that is right as it is (a supplier's real name in capitals) is
 * pinned below with its exact text, so the pin lapses if the text changes.
 *
 * Usage: node scripts/check-i18n-placeholders.mjs
 */
import { readFileSync } from 'node:fs';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { templatesIn } from './lib/inline-templates.mjs';
import { literalPlaceholders, localeProblems } from './lib/placeholder-scan.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const APP_DIR = resolve(ROOT, 'src', 'app');
const I18N_DIR = resolve(ROOT, 'src', 'assets', 'i18n');

/** Values checked by a person and right as they are, pinned to their exact text. */
const PINNED = {
  fr: {
    // CAMEG is the national medical-supply agency's own name, in capitals.
    'PHARMACY.SUPPLIER_PLACEHOLDER': 'ex. : CAMEG',
    // SARL is a French legal form, not an English word.
    'PROVIDER.EVIDENCE.LEGAL_STRUCTURE_HELP': 'Par exemple SARL',
  },
  es: {
    'PHARMACY.SUPPLIER_PLACEHOLDER': 'p. ej., CAMEG',
    'PROVIDER.EVIDENCE.LEGAL_STRUCTURE_HELP': 'Por ejemplo SARL',
  },
  en: {},
};

function flatten(node, prefix = '', out = new Map()) {
  for (const [key, value] of Object.entries(node)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) flatten(value, path, out);
    else if (typeof value === 'string') out.set(path, value);
  }
  return out;
}

const readLocale = (locale) =>
  flatten(JSON.parse(readFileSync(resolve(I18N_DIR, `${locale}.json`), 'utf8')));

let failures = 0;

for (const { file, text, lineOffset } of templatesIn(APP_DIR)) {
  for (const hit of literalPlaceholders(text)) {
    failures++;
    console.error(
      `${relative(ROOT, file)}:${hit.line + lineOffset}: placeholder="${hit.value}" is ${hit.reason} ` +
        `written in the template — use [placeholder]="'KEY' | translate" with EN/FR/ES values`,
    );
  }
}

const english = readLocale('en');
for (const locale of ['en', 'fr', 'es']) {
  const values = locale === 'en' ? english : readLocale(locale);
  for (const hit of localeProblems(locale, values, english, PINNED[locale])) {
    failures++;
    console.error(`${locale}.json ${hit.key} = "${hit.value}": ${hit.reason}`);
  }
}

if (failures) {
  console.error(`\ni18n:placeholders — ${failures} problem(s).`);
  process.exit(1);
}
console.log('i18n:placeholders — templates and locales are clean.');
