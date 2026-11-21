// #1247 follow-up (Rex, twice): a sortable table can be sorted in card mode.
// Under 600 px the global table layer (styles.scss, "card mode") hides a list
// table's header row, and the sort buttons with it: Patients and Appointments
// could not be sorted on a phone or at high zoom. Every table with a sortable
// header must sit beside the shared « Trier par » control
// (shared/card-sort/card-sort.component.ts, `<app-card-sort>`), which drives
// the page's own sort state. One control per sortable table, in the same
// template, wired to the page's sort (`(sortChange)`).
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { loadTemplates } from './template-gate.mjs';

/** Whether a header cell's opening tag or body makes it sort the table. */
const SORTABLE_HEADER =
  /<th\b[^>]*(?:aria-sort|\(click\)|mat-sort-header|\bsortable)[^>]*>|<th\b[^>]*>(?:(?!<\/th>)[\s\S])*\(click\)="[^"]*\bsort\w*\(/gi;

/** The tables of a template whose header row sorts them. */
export function sortableTables(html) {
  const out = [];
  for (const m of html.matchAll(/<table\b[\s\S]*?<\/table>/gi)) {
    const head = /<thead\b[\s\S]*?<\/thead>/i.exec(m[0])?.[0] ?? '';
    if (new RegExp(SORTABLE_HEADER.source, 'i').test(head)) out.push(m.index);
  }
  return out;
}

/** The card-mode sort controls of a template wired to a sort handler. */
export function cardSortControls(html) {
  return (html.match(/<app-card-sort\b[^>]*\(sortChange\)="[^"]+"[^>]*>/g) ?? []).length;
}

export function cardSortOffenders(templates) {
  const offenders = [];
  for (const { rel, text } of templates) {
    const tables = sortableTables(text).length;
    const controls = cardSortControls(text);
    if (tables > controls) {
      offenders.push(`${rel}: ${tables} sortable table(s), ${controls} <app-card-sort>`);
    }
  }
  return offenders;
}

test('#1247: every table with a sortable header has the card-mode sort control', () => {
  const templates = loadTemplates();
  const sortable = templates.filter((t) => sortableTables(t.text).length > 0).map((t) => t.rel);
  // Patients and Appointments today: the scan must keep seeing them.
  assert.ok(sortable.length >= 2, `sortable tables found in: ${sortable.join(', ')}`);
  assert.deepEqual(cardSortOffenders(templates), []);
});

test('the scan sees a sortable header without the control', () => {
  const withAriaSort =
    '<table><thead><tr><th [attr.aria-sort]="s()">A</th><th>B</th></tr></thead></table>';
  const withButton =
    '<table><thead><tr><th><button (click)="sortBy(\'a\')">A</button></th></tr></thead></table>';
  const withClick = '<table><thead><tr><th (click)="order(\'a\')">A</th></tr></thead></table>';
  const plain = '<table><thead><tr><th>A</th><th>B</th></tr></thead><tbody></tbody></table>';
  const control = '<app-card-sort [columns]="c" (sortChange)="onCardSort($event)" />';
  for (const html of [withAriaSort, withButton, withClick]) {
    assert.equal(cardSortOffenders([{ rel: 'x.html', text: html }]).length, 1, html);
    assert.equal(cardSortOffenders([{ rel: 'x.html', text: control + html }]).length, 0, html);
  }
  assert.equal(cardSortOffenders([{ rel: 'x.html', text: plain }]).length, 0);
  // A control that changes nothing does not count.
  assert.equal(
    cardSortOffenders([{ rel: 'x.html', text: '<app-card-sort [columns]="c" />' + withAriaSort }])
      .length,
    1,
  );
  // Two sortable tables need two controls.
  assert.equal(
    cardSortOffenders([{ rel: 'x.html', text: control + withAriaSort + withClick }]).length,
    1,
  );
});
