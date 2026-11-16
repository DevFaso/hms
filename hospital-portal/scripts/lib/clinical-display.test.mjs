import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';
import { templatesIn } from './inline-templates.mjs';
import { walk } from './walk.mjs';
import {
  templateDisplayProblems,
  ageUnitProblems,
  generatedTextProblems,
} from './clinical-display-scan.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const app = join(root, 'hospital-portal/src/app');

test('X1 guard detects each bad class and accepts translated or authored text', () => {
  assert.equal(templateDisplayProblems('({{ row.daysSupply }}d)').length, 1);
  assert.equal(
    templateDisplayProblems('@if (t.polypharmacyDetected) {} @for (w of t.warnings) {}').length,
    1,
  );
  assert.deepEqual(
    templateDisplayProblems(
      "{{ 'MED_TIMELINE.SUPPLY_DAYS' | translate: { count: row.daysSupply } }}",
    ),
    [],
  );
  assert.equal(templateDisplayProblems('“{{ row.value }}”').length, 1);
  assert.equal(templateDisplayProblems("“{{ 'LABEL' | translate }}”").length, 1);
  assert.equal(templateDisplayProblems("{{ 'COMMON.COLON' | translate }}{{ row.plan }}").length, 1);
  assert.deepEqual(
    templateDisplayProblems("{{ 'LABEL_VALUE' | translate: { value: row.plan } }}"),
    [],
  );
  assert.deepEqual(templateDisplayProblems("{{ 'COMMON.COLON' | translate }} {{ row.plan }}"), []);
  assert.equal(
    ageUnitProblems("function format(r) { const unit = r.ageUnit; return tr('AGE_UNIT_MONTHS'); }")
      .length,
    1,
  );
  assert.deepEqual(
    ageUnitProblems(
      "function format(r) { const unit = r.ageUnit; return tr('AGE_UNIT_MONTHS') + tr('AGE_UNIT_YEARS'); }",
    ),
    [],
  );
  assert.equal(generatedTextProblems('.source("Internal Prescription")').length, 1);
  assert.equal(generatedTextProblems('String source = "Pharmacy Fill";').length, 1);
  assert.equal(
    generatedTextProblems('warnings.add(n + " medications have overlapping therapy");').length,
    1,
  );
  assert.equal(generatedTextProblems('.duration(n + " days")').length, 1);
  assert.deepEqual(generatedTextProblems('.source(fill.getSourceSystem())'), []);
  assert.deepEqual(
    generatedTextProblems('warnings.add(MessageUtil.resolve(locale, "feature.warning", n));'),
    [],
  );
});

test('D1880 / D1883: every external and inline portal template uses locale punctuation', () => {
  const problems = templatesIn(app).flatMap(({ file, text }) =>
    templateDisplayProblems(text).map((problem) => `${relative(app, file)}: ${problem}`),
  );
  assert.deepEqual(problems, []);
});

test('D1881: every portal age formatter labels years as well as months', () => {
  const problems = walk(app, ['.ts'])
    .filter((file) => !file.endsWith('.spec.ts'))
    .flatMap((file) =>
      ageUnitProblems(readFileSync(file, 'utf8')).map(
        (problem) => `${relative(app, file)}: ${problem}`,
      ),
    );
  assert.deepEqual(problems, []);
});

test('D1574: generated sources, warning prose and day durations are translated across Java production code', () => {
  const java = join(root, 'hospital-core/src/main/java');
  // UploadStoragePersistenceCheck reports infrastructure diagnostics to startup logs,
  // never clinical display text; retain its English diagnostics unchanged.
  const problems = walk(java, ['.java'])
    .filter((file) => !file.endsWith('UploadStoragePersistenceCheck.java'))
    .flatMap((file) =>
      generatedTextProblems(readFileSync(file, 'utf8')).map(
        (problem) => `${relative(java, file)}: ${problem}`,
      ),
    );
  assert.deepEqual(problems, []);
});
