// QA round r81, pharmacy dispensing lane: the class guards of D4182, D4185, D4186 and D4195.
//
// - D4182 (a partner pharmacy offered as one of the hospital's stores): a store picker reads the hospital's
//   own stores (`listStores`). The whole registry (`listPharmacies`, `searchPharmacies`), which also holds the
//   partner and community pharmacies, is read by the registry page alone.
// - D4185 (a pharmacy row that names a patient and nothing else): a pharmacy template that shows a patient's
//   name shows the record number and date of birth beside it (`app-patient-id-line`).
// - D4186 (a receipt that cannot be reopened): the checkout and the counter sale each reopen a receipt, mark
//   it as a duplicate, and print it.
// - D4195 (two date formats on one screen): a date-time on a pharmacy screen is the locale's medium date and
//   short time (`| dateTime`), never `date: 'short'` (« 27/11/2026 05:41 » beside « 27 nov. 2026 »).
//
// Sites that belong to the stock lane of the same round (branch fix/r81-pharmacy-stock) are pinned with their
// defect id. A pin only shrinks: a pinned file that no longer offends fails here until it is removed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const pharmacyDir = join(appDir, 'pharmacy');
const rel = (file) => relative(appDir, file).split('\\').join('/');
const read = (file) => readFileSync(file, 'utf8');

test('D4182: only the registry page reads the whole pharmacy registry', () => {
  const readers = walk(appDir, ['.ts'])
    .filter((file) => !file.endsWith('.spec.ts'))
    .filter((file) => /\.(listPharmacies|searchPharmacies)\(/.test(read(file)))
    .map(rel)
    .sort();
  assert.deepEqual(
    readers,
    ['pharmacy/pharmacy-registry.ts'],
    'a store picker must call PharmacyService.listStores (the hospital’s own stores): the registry also ' +
      'lists partner and community pharmacies, which hold no hospital stock',
  );
});

test('D4182: every pharmacy page that picks a store asks for the hospital’s own stores', () => {
  const pickers = walk(pharmacyDir, ['.ts'])
    .filter((file) => !file.endsWith('.spec.ts'))
    .filter((file) => /\.listStores\(/.test(read(file)))
    .map(rel)
    .sort();
  assert.deepEqual(pickers, [
    'pharmacy/controlled-register.ts',
    'pharmacy/counter-sale.ts',
    'pharmacy/dispensing.ts',
    'pharmacy/goods-receipt.ts',
    'pharmacy/inventory-dashboard.ts',
    'pharmacy/pharmacy-checkout.ts',
    'pharmacy/stock-adjustment.ts',
  ]);
  const service = read(join(appDir, 'services', 'pharmacy.service.ts'));
  assert.match(
    service,
    /listStores\([^)]*\)[^{]*\{[^}]*\.set\('storesOnly', true\)/s,
    'listStores asks the server for the stores only',
  );
});

/** Pharmacy templates that still name a patient alone, each with the defect that owns it. */
const NAME_ONLY_PINS = {
  'pharmacy/controlled-register.html': 'D4185: the controlled-drug register (stock lane)',
  'pharmacy/stock-adjustment.html': 'D4185: the return picker of the stock adjustment (stock lane)',
  'pharmacy/billing-deferrals.html':
    'D4185: shows its own « N° de dossier » column, not the shared line',
  'pharmacy/pharmacy-reconciliation.html':
    'D4185: shows the record number inline, not the shared line',
};

test('D4185: a pharmacy template that names a patient shows the record number beside the name', () => {
  const NAMES = /patientName|\bpatient\s*\|\s*personName/;
  const nameOnly = [];
  for (const file of walk(pharmacyDir, ['.html'])) {
    const html = read(file);
    if (NAMES.test(html) && !html.includes('<app-patient-id-line')) nameOnly.push(rel(file));
  }
  assert.deepEqual(
    nameOnly.sort(),
    Object.keys(NAME_ONLY_PINS).sort(),
    'a pharmacy row with a patient name carries <app-patient-id-line [mrn] [dateOfBirth]>; a pinned ' +
      'file that now does must leave NAME_ONLY_PINS',
  );
});

test('D4185: the dispensing queue, its panels and the recent fills all carry the identity line', () => {
  const html = read(join(pharmacyDir, 'dispensing.html'));
  const lines = html.match(/<app-patient-id-line/g) ?? [];
  assert.equal(
    lines.length,
    5,
    'queue row, dispense panel, hand-over panel, take-over dialog, recent fills',
  );
  assert.match(html, /\[mrn\]="rx\.patient\?\.mrn"\s+\[dateOfBirth\]="rx\.patient\?\.dateOfBirth"/);
  assert.match(html, /\[mrn\]="selectedPrescription\?\.patient\?\.mrn"/);
});

test('D4186: the checkout and the counter sale reopen a receipt, mark the duplicate and print it', () => {
  const checkout = read(join(pharmacyDir, 'pharmacy-checkout.html'));
  assert.match(checkout, /data-testid="receipt-history"/);
  assert.match(checkout, /\(click\)="reopenReceipt\(p\)"/);
  assert.match(checkout, /@if \(isDuplicate\(r\)\) \{\s*<p class="receipt-duplicate"/);
  assert.match(checkout, /id="dispenseSearch"/, 'the fill picker is searched');
  assert.match(checkout, /d\.chargeAmount \| cfa: 'XOF'/, 'the fill picker shows the amount');
  const checkoutTs = read(join(pharmacyDir, 'pharmacy-checkout.ts'));
  assert.match(
    checkoutTs,
    /this\.svc\.getPayment\(payment\.id\)/,
    'a reopened receipt is read from the server',
  );

  const sale = read(join(pharmacyDir, 'counter-sale.html'));
  assert.match(sale, /\(click\)="reopenSale\(s\)"/);
  assert.match(sale, /@if \(receiptDuplicate\(\)\) \{\s*<p class="receipt-duplicate"/);
  assert.match(sale, /\(click\)="printReceipt\(\)"/);
  assert.match(
    sale,
    /r\.receiptNumber \|\| '—'/,
    'the receipt names its number, never the internal id',
  );
  assert.doesNotMatch(sale, /class="receipt-id">\{\{ r\.id \}\}/);
});

/** Pharmacy templates that still print `date: 'short'`, each with the defect that owns it. */
const SHORT_DATE_PINS = {
  'pharmacy/stock-adjustment.html': 'D4195 (stock lane)',
};

test('D4195: a date-time on a pharmacy screen is never the numeric short format', () => {
  const offenders = walk(pharmacyDir, ['.html'])
    .filter((file) => /\|\s*date\s*:\s*'short'/.test(read(file)))
    .map(rel)
    .sort();
  assert.deepEqual(
    offenders,
    Object.keys(SHORT_DATE_PINS).sort(),
    "use `| serverTime | dateTime` (27 nov. 2026, 05:41); a pinned file that no longer prints 'short' " +
      'must leave SHORT_DATE_PINS',
  );
});
