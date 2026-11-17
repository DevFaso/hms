import { EMPTY, Observable, defer, expand, map, reduce } from 'rxjs';

/**
 * QA D1347 (round 30): a list read from ONE page of a fixed size and treated
 * as the whole list. The lab orders page asked for `size: 200` once, so with
 * 216 orders the 16 oldest — a pending one among them — could not be found,
 * and the counters counted the page, not the orders. The same shape capped the
 * patient's medicines (D1346), the lab results (D1213) and the nurse inbox
 * (D1295).
 *
 * `allPages` reads every page a Spring `Page` endpoint has for the request and
 * returns the rows in server order. It stops on the page the server marks as
 * the last one (`last`, or `number + 1 >= totalPages`), or on an empty page.
 * The page size asked for is only a transfer size: the server may lower it
 * (its own bound), and the next page index is counted on the server's own
 * paging, so a lowered size never skips or repeats rows.
 *
 * A row whose `id` was already read (the list moved between two pages) is
 * kept once. Nothing is silently dropped: past `ALL_PAGES_MAX_PAGES` pages the
 * stream FAILS instead of returning a partial list, and the page says that the
 * load failed.
 */
export const ALL_PAGES_SIZE = 200;
export const ALL_PAGES_MAX_PAGES = 200;

/** The parts of a Spring Data `Page` the reader needs. */
export interface PageSlice<T> {
  content?: T[] | null;
  totalElements?: number | null;
  totalPages?: number | null;
  number?: number | null;
  /** The page size the server answered with (it may lower the one asked for). */
  size?: number | null;
  last?: boolean | null;
}

/** Every row of the request, and the server's own count of them. */
export interface AllRows<T> {
  rows: T[];
  /** `totalElements` of the first page: the server's count, not the loaded length. */
  total: number;
}

interface Step<T> {
  index: number;
  page: PageSlice<T>;
  /** Rows of this page not already read on an earlier one. */
  rows: T[];
  fresh: number;
}

function isLastPage<T>(step: Step<T>): boolean {
  const { page, index } = step;
  if ((page.content ?? []).length === 0 || page.last === true) return true;
  const current = page.number ?? index;
  if (page.totalPages != null) return current + 1 >= page.totalPages;
  // No paging metadata: a page that brought nothing new (an endpoint that
  // ignores `page`) ends the read instead of looping. With metadata a page of
  // repeats (rows moved between two reads) does not end it early.
  return step.fresh === 0;
}

function rowKey(row: unknown): unknown {
  return row !== null && typeof row === 'object' && 'id' in row
    ? (row as { id: unknown }).id
    : undefined;
}

/**
 * @param fetchPage reads page `page` (0-based) of `size` rows
 * @returns every row and the server's total, or an error past the page ceiling
 */
export function allPages<T>(
  fetchPage: (page: number, size: number) => Observable<PageSlice<T> | null | undefined>,
  size: number = ALL_PAGES_SIZE,
): Observable<AllRows<T>> {
  return defer(() => {
    // Per subscription: two readers of the same stream never share state.
    const seen = new Set<unknown>();
    const read = (index: number): Observable<Step<T>> =>
      fetchPage(index, size).pipe(
        map((page) => {
          const slice = page ?? {};
          const rows: T[] = [];
          for (const row of slice.content ?? []) {
            const key = rowKey(row);
            if (key !== undefined) {
              if (seen.has(key)) continue;
              seen.add(key);
            }
            rows.push(row);
          }
          return { index, page: slice, rows, fresh: rows.length };
        }),
      );
    return read(0).pipe(
      expand((step) => {
        if (isLastPage(step)) return EMPTY;
        const next = (step.page.number ?? step.index) + 1;
        if (next >= ALL_PAGES_MAX_PAGES) {
          throw new Error(`allPages: more than ${ALL_PAGES_MAX_PAGES} pages`);
        }
        return read(next);
      }),
      // The seed is built per subscription too (reduce mutates it).
      reduce(
        (acc, step) => {
          if (step.index === 0) acc.total = step.page.totalElements ?? 0;
          acc.rows.push(...step.rows);
          return acc;
        },
        { rows: [] as T[], total: 0 },
      ),
      map(({ rows, total }) => ({ rows, total: Math.max(total, rows.length) })),
    );
  });
}

/** {@link allPages}, rows only. */
export function allRows<T>(
  fetchPage: (page: number, size: number) => Observable<PageSlice<T> | null | undefined>,
  size: number = ALL_PAGES_SIZE,
): Observable<T[]> {
  return allPages(fetchPage, size).pipe(map((r) => r.rows));
}

/**
 * {@link allRows} for an endpoint that pages but answers a bare array (no
 * total): a page shorter than `size` is the last one. Only for an endpoint
 * that honours the size asked for — one that lowers it would end the read
 * after its first page; such an endpoint must answer a `Page`.
 */
export function allRowsOfArrays<T>(
  fetchPage: (page: number, size: number) => Observable<T[] | null | undefined>,
  size: number = ALL_PAGES_SIZE,
): Observable<T[]> {
  return allRows(
    (page, pageSize) =>
      fetchPage(page, pageSize).pipe(
        map((rows) => ({
          content: rows ?? [],
          number: page,
          last: (rows ?? []).length < pageSize,
        })),
      ),
    size,
  );
}
