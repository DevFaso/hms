#!/usr/bin/env node
/**
 * The silent list-cap scan (QA D1347). The failing cases are the lab orders
 * page as reported (`listOrders({ size: 200 })` counted as the whole list)
 * and the other spellings the round-30 audit found; the passing cases are
 * reads of every page, CSS sizes, string slices and screens that show the
 * server's total.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { listCapFindings } from './list-cap-scan.mjs';

const rules = (ts, file = 'src/app/x/x.ts') =>
  listCapFindings(ts, file).map((f) => `${f.rule}:${f.anchor}`);

test('D1347: the lab page read one page of 200 and counted it', () => {
  const ts = `export class LabComponent {
  loadOrders(): void {
    this.labService.listOrders({ size: 200 }).subscribe({
      next: (list) => this.orders.set(list),
    });
  }
}`;
  assert.deepEqual(rules(ts), ['fixed-size:loadOrders:listOrders:size=200']);
});

test('the other spellings of one fixed page are found', () => {
  const ts = `export class X {
  loadA(): void {
    const params = new HttpParams().set('size', '20');
  }
  loadB(): void {
    this.svc.listPharmacies(0, 100).subscribe();
  }
  loadC(): void {
    this.slotService.searchOpen({ from, to, limit: 200 });
  }
}`;
  assert.deepEqual(rules(ts), [
    'fixed-size:loadC:searchOpen:limit=200',
    'fixed-size:loadA:HttpParams:size=20',
    'fixed-size:loadB:listPharmacies:page0x100',
  ]);
});

test('a page reduced to its rows in a map is found', () => {
  const ts = `export class S {
  list(): Observable<T[]> {
    return this.http.get<Page<T>>('/x').pipe(map((res) => res?.data?.content ?? []));
  }
}`;
  assert.deepEqual(rules(ts), ['page-flattened:list:content']);
});

test("a page's rows read on a screen with no total are found; with a total they are not", () => {
  const silent = `export class A {
  load(): void {
    this.svc.getItems().subscribe({ next: (page) => this.items.set(page.content) });
  }
}`;
  assert.deepEqual(rules(silent), ['page-content:load:page']);
  const paged = `export class B {
  load(): void {
    this.svc.getItems(this.page).subscribe({
      next: (page) => { this.items.set(page.content); this.total.set(page.totalElements); },
    });
  }
}`;
  assert.deepEqual(rules(paged), []);
});

test('reads of every page, CSS sizes, string slices and comments are not findings', () => {
  const ts = `export class C {
  // listOrders({ size: 200 }) used to be here (QA D1347)
  styles = \`.x { font-size: 64px; } .y { size: 2rem; }\`;
  load(): void {
    allRows((page, size) => this.svc.list({ page, size })).subscribe();
    const short = id.slice(0, 8);
    const head = text.substring(0, 50);
    this.chat.lastMessageContent = msg.content;
  }
}`;
  assert.deepEqual(rules(ts), []);
});

test('the shared helper itself is not scanned', () => {
  const ts = `export function allRows() { return x.pipe(map((res) => res.content ?? [])); }`;
  assert.deepEqual(rules(ts, 'src/app/shared/all-pages.ts'), []);
});
