// QA D1820 (r51) guard: one word per imaging report status.
//
// The request drawer's report card read "Final" while the report drawer, the
// results tabs and the list read "Définitif" (and "Complément" / "Addendum",
// "Amendé" / "Modifié"): the card used the imagingReportStatus enum labels
// (PORTAL.ENUM.IMAGING_REPORT_STATUS), the rest a key family of their own
// (IMAGING.REPORT_STATUS_*). The family is gone; every surface names a report
// status through the enum label, so two screens cannot say two words.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (lang) =>
  JSON.parse(readFileSync(join(root, 'src', 'assets', 'i18n', `${lang}.json`), 'utf8'));

test('D1820: no second key family names an imaging report status', () => {
  for (const lang of ['en', 'fr', 'es']) {
    const copies = Object.keys(read(lang).IMAGING ?? {}).filter((k) =>
      k.startsWith('REPORT_STATUS_'),
    );
    assert.deepEqual(
      copies,
      [],
      `${lang}: name a report status with PORTAL.ENUM.IMAGING_REPORT_STATUS`,
    );
  }
  const files = walk(join(root, 'src', 'app'), ['.html', '.ts']).filter(
    (f) => !f.endsWith('.spec.ts'),
  );
  const offenders = files.filter((f) => /IMAGING\.REPORT_STATUS_/.test(readFileSync(f, 'utf8')));
  assert.deepEqual(
    offenders.map((f) => relative(root, f).replaceAll('\\', '/')),
    [],
    "use | enumLabel: 'imagingReportStatus'",
  );
});

test('D1820: the words the r51 run expected', () => {
  const fr = read('fr').PORTAL.ENUM.IMAGING_REPORT_STATUS;
  assert.equal(fr.FINAL, 'Définitif');
  assert.equal(fr.ADDENDUM, 'Addendum');
  assert.equal(fr.AMENDED, 'Modifié');
  assert.equal(read('es').PORTAL.ENUM.IMAGING_REPORT_STATUS.ADDENDUM, 'Adenda');
});
