// r44 guard: one word per lab grade.
//
// The result drawer read "Sévérité : Élevé" in its header and "Haut" on the
// position badge beside it: the badge had its own keys (LAB_RANGE.LOW / NORMAL
// / HIGH) with their own words. Every surface that names a lab grade now uses
// the labSeverityFlag enum labels; the copies that remain (the release queue's
// LAB_RELEASE.FLAG_*, the visit summary's VISIT_SUMMARY.FLAG_*) must say the
// same word in every locale, and no lab template may name a grade through a
// key of its own.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const locales = ['en', 'fr', 'es'];
const read = (lang) =>
  JSON.parse(readFileSync(join(root, 'src', 'assets', 'i18n', `${lang}.json`), 'utf8'));

const GRADES = ['CRITICAL', 'HIGH', 'LOW', 'NORMAL', 'ABNORMAL'];

test('r44: every copy of a lab grade word matches the labSeverityFlag label', () => {
  for (const lang of locales) {
    const d = read(lang);
    const canonical = d.PORTAL.ENUM.LAB_SEVERITY_FLAG;
    for (const grade of GRADES) {
      assert.ok(canonical[grade], `${lang}: PORTAL.ENUM.LAB_SEVERITY_FLAG.${grade} is missing`);
      for (const [section, key] of [
        ['LAB_RELEASE', `FLAG_${grade}`],
        ['VISIT_SUMMARY', `FLAG_${grade}`],
      ]) {
        const copy = d[section]?.[key];
        if (copy === undefined) continue;
        assert.equal(copy, canonical[grade], `${lang}: ${section}.${key} says "${copy}"`);
      }
    }
  }
});

test('r44: the result-position card has no grade words of its own', () => {
  for (const lang of locales) {
    const range = read(lang).LAB_RANGE ?? {};
    for (const grade of ['LOW', 'NORMAL', 'HIGH']) {
      assert.equal(range[grade], undefined, `${lang}: LAB_RANGE.${grade} is back`);
    }
  }
  const files = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) walk(path);
      else if (/\.(ts|html)$/.test(name) && !name.endsWith('.spec.ts')) files.push(path);
    }
  };
  walk(join(root, 'src', 'app', 'lab'));
  const offenders = files.filter((f) =>
    /LAB_RANGE\.(LOW|NORMAL|HIGH)\b/.test(readFileSync(f, 'utf8')),
  );
  assert.deepEqual(
    offenders.map((f) => relative(root, f)),
    [],
    'name a grade with the labSeverityFlag enum label',
  );
});
