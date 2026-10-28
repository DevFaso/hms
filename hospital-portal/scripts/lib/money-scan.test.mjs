#!/usr/bin/env node
/**
 * The money-format scan (QA D948, D1008, D621). The failing cases are the
 * D948 markup and the D621 helpers as reported; the passing cases are | cfa,
 * formatCfa() and the things that look like money but are not.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { moneyTemplateFindings, moneyComponentFindings } from './money-scan.mjs';

const anchors = (findings) => findings.map((f) => `${f.rule}:${f.anchor}`);

test('D948: an amount through | number has no currency and two decimals', () => {
  const html = `<span>{{ 'X.BALANCE' | translate }}: {{ inv.balanceDue | number: '1.2-2' }}</span>
    <td>{{ row.amount | currency: 'XOF' }}</td>`;
  assert.deepEqual(anchors(moneyTemplateFindings(html)), [
    'pipe:inv.balanceDue | number',
    'pipe:row.amount | currency',
  ]);
});

test('| cfa passes, and a rate, a count or a page total is not money', () => {
  const html = `{{ inv.balanceDue | cfa }} {{ paymentCollectionRate() | number: '1.1-1' }}
    {{ totalPages }} {{ stats().total }} {{ w.totalBeds | number }} {{ copayPercent | number }}`;
  assert.deepEqual(moneyTemplateFindings(html), []);
});

test('toFixed on an amount is hand formatting', () => {
  assert.deepEqual(anchors(moneyTemplateFindings(`{{ item.unitPrice.toFixed(2) }}`)), [
    'fixed:item.unitPrice.toFixed',
  ]);
  assert.deepEqual(anchors(moneyComponentFindings(`const s = (total.amount).toFixed(2);`)), [
    'fixed:total.amount.toFixed',
  ]);
});

test('D621: a component helper that formats money without formatCfa, or Intl currency outside one', () => {
  const helper = `formatCurrency(amount: number): string {
    return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'XOF' }).format(amount);
  }`;
  assert.deepEqual(anchors(moneyComponentFindings(helper)), ['helper:formatCurrency']);
  const loose = `const label = new Intl.NumberFormat(locale, { style: 'currency', currency }).format(v);`;
  assert.deepEqual(anchors(moneyComponentFindings(loose)), ['helper:Intl currency']);
});

test('a helper that delegates francs to formatCfa may use Intl for another currency', () => {
  const ok = `formatPrice(cents: number, currency: string): string {
    if ((currency || '').toUpperCase() === 'XOF') return formatCfa(cents / 100);
    return new Intl.NumberFormat(currentLocale(), { style: 'currency', currency }).format(cents / 100);
  }
  private fractionDigits(currency: string): number {
    return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2;
  }`;
  assert.deepEqual(moneyComponentFindings(ok), []);
  assert.deepEqual(
    moneyComponentFindings(`new Intl.NumberFormat(l, { style: 'currency', currency: c })`, true),
    [],
  );
});
