import { Observable, firstValueFrom, of, throwError } from 'rxjs';

import { ALL_PAGES_MAX_PAGES, PageSlice, allPages, allRows, allRowsOfArrays } from './all-pages';

interface Row {
  id: string;
}

/** A Spring-shaped endpoint over `rows`, honouring `size` up to `cap`. */
function endpoint(rows: Row[], cap = Infinity) {
  const calls: { page: number; size: number }[] = [];
  const fetch = (page: number, size: number): Observable<PageSlice<Row>> => {
    calls.push({ page, size });
    const effective = Math.min(size, cap);
    const content = rows.slice(page * effective, (page + 1) * effective);
    return of({
      content,
      totalElements: rows.length,
      totalPages: Math.ceil(rows.length / effective),
      number: page,
    });
  };
  return { fetch, calls };
}

const rows = (n: number): Row[] => Array.from({ length: n }, (_, i) => ({ id: `r${i}` }));

describe('allPages (QA D1347: a list is every page, never the first one)', () => {
  it('reads every page of 216 orders asked 200 at a time, and reports the server total', async () => {
    const api = endpoint(rows(216));
    const all = await firstValueFrom(allPages(api.fetch, 200));
    expect(all.rows.length).toBe(216);
    expect(all.total).toBe(216);
    expect(all.rows[215].id).toBe('r215');
    expect(api.calls).toEqual([
      { page: 0, size: 200 },
      { page: 1, size: 200 },
    ]);
  });

  it('stops after one request when everything fits', async () => {
    const api = endpoint(rows(3));
    expect((await firstValueFrom(allRows(api.fetch))).length).toBe(3);
    expect(api.calls.length).toBe(1);
  });

  it('follows the server paging when the server lowers the size', async () => {
    const api = endpoint(rows(250), 100);
    const all = await firstValueFrom(allRows(api.fetch, 200));
    expect(all.map((r) => r.id)).toEqual(rows(250).map((r) => r.id));
    expect(api.calls.map((c) => c.page)).toEqual([0, 1, 2]);
  });

  it('keeps a row repeated on two pages once', async () => {
    const fetch = (page: number): Observable<PageSlice<Row>> =>
      of(
        page === 0
          ? { content: [{ id: 'a' }, { id: 'b' }], totalPages: 2, number: 0, totalElements: 3 }
          : { content: [{ id: 'b' }, { id: 'c' }], totalPages: 2, number: 1, totalElements: 3 },
      );
    expect((await firstValueFrom(allRows(fetch))).map((r) => r.id)).toEqual(['a', 'b', 'c']);
  });

  it('ends on a page that brings nothing new when the endpoint gives no paging data', async () => {
    let calls = 0;
    const fetch = (): Observable<PageSlice<Row>> => {
      calls++;
      return of({ content: [{ id: 'a' }] });
    };
    expect((await firstValueFrom(allRows(fetch))).length).toBe(1);
    expect(calls).toBe(2);
  });

  it('fails instead of returning a partial list past the page ceiling', async () => {
    const fetch = (page: number): Observable<PageSlice<Row>> =>
      of({ content: [{ id: `p${page}` }], totalPages: ALL_PAGES_MAX_PAGES + 10, number: page });
    await expectAsync(firstValueFrom(allRows(fetch))).toBeRejected();
  });

  it('passes a failed page on as a failure', async () => {
    const fetch = (page: number): Observable<PageSlice<Row>> =>
      page === 0
        ? of({ content: [{ id: 'a' }], totalPages: 2, number: 0 })
        : throwError(() => new Error('500'));
    await expectAsync(firstValueFrom(allRows(fetch))).toBeRejected();
  });

  it('gives each subscription its own rows', async () => {
    const api = endpoint(rows(5));
    const stream = allRows(api.fetch, 2);
    expect((await firstValueFrom(stream)).length).toBe(5);
    expect((await firstValueFrom(stream)).length).toBe(5);
  });

  it('reads a bare-array endpoint until a short page', async () => {
    const data = rows(450);
    const calls: number[] = [];
    const fetch = (page: number, size: number): Observable<Row[]> => {
      calls.push(page);
      return of(data.slice(page * size, (page + 1) * size));
    };
    expect((await firstValueFrom(allRowsOfArrays(fetch, 200))).length).toBe(450);
    expect(calls).toEqual([0, 1, 2]);
  });
});
