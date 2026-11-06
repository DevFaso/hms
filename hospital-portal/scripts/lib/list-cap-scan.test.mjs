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

import {
  listCapFindings,
  sliceCountFindings,
  serverLimitFindings,
  serverLimitedPaths,
} from './list-cap-scan.mjs';

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

test('D1362: a read with one literal limit is found; a named preview size and a default are not', () => {
  const ts = `export class MyRecords {
  ngOnInit(): void {
    this.portal.getMyLabResults(50).subscribe();
    this.portal.getMyLabResults(RECORD_CARD_ROWS).subscribe();
    this.portal.getMyMedications().subscribe();
  }
}`;
  assert.deepEqual(rules(ts), ['fixed-limit:ngOnInit:getMyLabResults:50']);
});

test('D1362: a "+ N more" counted in a template from the rows on hand is found', () => {
  const html = `@if (labs().length > 4) {
  <span class="card-more">+ {{ labs().length - 4 }} {{ 'PORTAL.RECORDS.MORE' | translate }}</span>
}
<span>+ {{ summary()!.activeDiagnoses.length - 5 }}</span>`;
  assert.deepEqual(
    sliceCountFindings(html).map((f) => `${f.rule}:${f.anchor}`),
    ['slice-count:labs():4', 'slice-count:summary()!.activeDiagnoses:5'],
  );
});

test('D1362: the server-total count, an index into a list and a comment are not findings', () => {
  const html = `<span>+ {{ moreLabs() }}</span>
{{ trend()[trend().length - 1]?.date }}
<!-- labs().length - 4 was the D1362 count -->`;
  assert.deepEqual(sliceCountFindings(html), []);
});

test('D1379: a read whose limit is a defaulted parameter and that answers a bare array is found', () => {
  const ts = `export class PatientPortalService {
  getMyVitals(limit = 10): Observable<VitalSignSummary[]> {
    return this.http.get<X>('/vitals', { params: { limit } });
  }

  getMyVitalsPage(page: number, size = VITALS_PAGE_SIZE): Observable<VitalsPage> {
    return this.http.get<X>('/vitals/history', { params: { page, size } });
  }

  searchInvoices(filters: F, page = 0, size = 20): Observable<InvoicePage> {
    return this.http.get<InvoicePage>('/invoices');
  }

  listRecent(
    patientId: string,
    hospitalId?: string,
    limit: number = 20,
  ): Observable<NursingNote[]> {
    return this.http.get<NursingNote[]>('/notes');
  }
}`;
  assert.deepEqual(rules(ts), [
    'default-limit:getMyVitals:limit=10',
    'default-limit:listRecent:limit=20',
  ]);
});

test('D1380: a count read from the length of a payload list is found; the server total is not', () => {
  const ts = `export class ChartReview {
  protected readonly counts = computed(() => {
    const c = this.chart();
    return {
      medications: c?.medications?.length ?? 0,
      results: c?.totals?.results ?? 0,
    };
  });

  protected readonly encountersById = computed(() => {
    const n = this.chart()?.encounters?.length ?? 0;
    return n;
  });
}`;
  assert.deepEqual(rules(ts), ['payload-length-count:counts:c.medications']);
});

test('D1399: a read of a list the server caps by default is found; a paged one is not', () => {
  const controller = `
    @GetMapping("/medications")
    public ResponseEntity<X> a(Authentication auth,
            @RequestParam(defaultValue = "20") int limit) { return null; }
    @GetMapping("/medications/page")
    public ResponseEntity<X> b(Authentication auth, Pageable pageable) { return null; }
    @GetMapping("/proxy-access/{patientId}/lab-results")
    public ResponseEntity<X> c(@PathVariable UUID patientId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Integer offset) { return null; }
  `;
  const paths = serverLimitedPaths(controller);
  assert.deepEqual(paths, ['/medications', '/proxy-access/{patientId}/lab-results']);
  const service = `export class S {
  getMine() {
    return this.http.get(\`\${this.base}/medications\`);
  }
  getAll() {
    return this.rows(\`\${this.base}/medications/page\`);
  }
  getPage(patientId, offset) {
    return this.http.get(\`\${this.base}/proxy-access/\${patientId}/lab-results\`, { params: { offset } });
  }
  getCut(patientId) {
    return this.http.get(\`\${this.base}/proxy-access/\${patientId}/lab-results\`, { params: { limit: 5 } });
  }
}`;
  assert.deepEqual(
    serverLimitFindings(service, paths).map((f) => f.anchor),
    ['getMine:/medications', 'getCut:/proxy-access/{patientId}/lab-results'],
  );
});
