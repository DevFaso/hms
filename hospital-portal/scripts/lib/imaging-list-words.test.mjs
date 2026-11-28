// QA D4166 guard: the imaging request list agrees with itself. The status chip
// of a request ("demande", feminine) reads like the tabs beside it ("Annulée",
// "Terminée"), and the "Demandé" column carries the time of day because the
// list is used for STAT work.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const fr = JSON.parse(readFileSync(join(root, 'src', 'assets', 'i18n', 'fr.json'), 'utf8'));

test('D4166: French request status words are feminine, as the tabs are', () => {
  const status = fr.PORTAL.ENUM.IMAGING_ORDER_STATUS;
  assert.equal(status.CANCELLED, fr.IMAGING.CANCELLED);
  assert.equal(status.COMPLETED, fr.IMAGING.COMPLETED);
  assert.equal(status.ORDERED, 'Prescrite');
  assert.equal(status.SCHEDULED, 'Planifiée');
});

test('D4166: the Demandé column shows the time', () => {
  const html = readFileSync(join(root, 'src', 'app', 'imaging', 'imaging.html'), 'utf8');
  assert.match(html, /o\.orderedAt \? \(o\.orderedAt \| serverTime \| dateTime\)/);
});
