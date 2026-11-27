#!/usr/bin/env node
/**
 * QA D4315 (r81): counted texts read « 1 membres du personnel », « 0 jours »,
 * « 3 désactivé(s) ». Two rules, checked over the whole portal:
 *
 * 1. A counted text goes through the plural pipe (`'KEY' | plural: n`) or
 *    `pluralKey('KEY', n)`, and then `KEY_ONE` and `KEY_OTHER` exist in the
 *    three locales.
 * 2. No NEW French text carries a parameter beside an optional plural
 *    (« {{count}} ligne(s) »). The ones that exist are pinned in
 *    scripts/i18n-plurals-baseline.json; the list may only shrink.
 *
 * `--write-baseline` rewrites the pins (read the diff).
 */
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const i18nDir = join(root, 'src', 'assets', 'i18n');
const baselinePath = join(root, 'scripts', 'i18n-plurals-baseline.json');
const LOCALES = ['fr', 'en', 'es'];

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else if (/\.(html|ts)$/.test(name) && !/\.spec\.ts$/.test(name)) out.push(path);
  }
  return out;
}

function flatten(node, prefix, out) {
  for (const [key, value] of Object.entries(node)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object') flatten(value, path, out);
    else out[path] = String(value);
  }
  return out;
}

const locales = Object.fromEntries(
  LOCALES.map((lang) => [
    lang,
    flatten(JSON.parse(readFileSync(join(i18nDir, `${lang}.json`), 'utf8')), '', {}),
  ]),
);

const failures = [];

// Rule 1: every key handed to the plural pipe or pluralKey() has both forms everywhere.
const KEY = String.raw`([A-Z][A-Z0-9_]*(?:\.[A-Za-z0-9_-]+)+)`;
const QUOTE = '[\'"\x60]';
const USES = [
  new RegExp(QUOTE + KEY + QUOTE + String.raw`\s*\|\s*plural`, 'g'),
  new RegExp(String.raw`pluralKey\(\s*` + QUOTE + KEY + QUOTE, 'g'),
];
let uses = 0;
for (const file of walk(join(root, 'src', 'app'))) {
  const text = readFileSync(file, 'utf8');
  for (const pattern of USES) {
    for (const match of text.matchAll(pattern)) {
      uses++;
      for (const lang of LOCALES) {
        for (const suffix of ['_ONE', '_OTHER']) {
          if (!(match[1] + suffix in locales[lang])) {
            failures.push(`${lang}: missing ${match[1]}${suffix} (${file.slice(root.length + 1)})`);
          }
        }
      }
    }
  }
}

// Rule 2: optional plurals beside a parameter, pinned.
const OPTIONAL_PLURAL = /\{\{[^}]+\}\}[^.;:!?]*\((?:s|es|e\)s|x)\)/;
const found = Object.entries(locales.fr)
  .filter(([, text]) => OPTIONAL_PLURAL.test(text))
  .map(([key]) => key)
  .sort();

if (process.argv.includes('--write-baseline')) {
  writeFileSync(baselinePath, `${JSON.stringify(found, null, 2)}\n`);
  console.log(`i18n plurals: baseline written (${found.length} pinned).`);
  process.exit(0);
}

const baseline = new Set(JSON.parse(readFileSync(baselinePath, 'utf8')));
for (const key of found) {
  if (!baseline.has(key)) {
    failures.push(
      `fr: ${key} = « ${locales.fr[key]} » writes an optional plural; use KEY_ONE / KEY_OTHER and the plural pipe`,
    );
  }
}
for (const key of baseline) {
  if (!found.includes(key)) {
    failures.push(
      `baseline: ${key} is fixed or gone; remove it from scripts/i18n-plurals-baseline.json`,
    );
  }
}

if (failures.length > 0) {
  console.error(`i18n plurals: ${failures.length} problem(s)\n` + failures.join('\n'));
  process.exit(1);
}
console.log(`i18n plurals: OK (${uses} counted texts, ${found.length} pinned optional plurals).`);
