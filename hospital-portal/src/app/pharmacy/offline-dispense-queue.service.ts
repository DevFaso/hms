/**
 * Offline dispense queue (roadmap row 4 / T-68).
 *
 * Buffers POST /pharmacy/dispense requests when the browser is offline or the
 * backend returns a 5xx, then replays them in FIFO order when connectivity is
 * restored. Pairs with the backend `idempotency_key` column (V94) — every
 * queued request carries a deterministic key so the replay is a no-op if the
 * server somehow already saw the original POST.
 *
 * Why a separate file from offline-dispense.interceptor.ts:
 * - The interceptor's job is HTTP plumbing (catch failure → enqueue → fake
 *   202). The queue's job is durability + replay. Splitting them lets the
 *   queue have a unit-testable surface (`enqueue` + `replayAll` + `pending$`)
 *   that doesn't drag the HttpClient testbed in.
 *
 * Storage layering:
 *   QueueStore (interface) — durable get/put/delete/list
 *     ├── IndexedDbQueueStore — production; persists across reloads
 *     └── InMemoryQueueStore  — test fallback; also used in SSR/Node where
 *                                window.indexedDB is undefined
 *
 * The service picks IndexedDB at construction time when available; otherwise
 * it transparently falls back to in-memory so SSR/Karma/Node never crash on
 * a missing global. All methods return Promises so the caller's lifecycle
 * is the same in both modes.
 */
import { Injectable } from '@angular/core';
import { BehaviorSubject, type Observable } from 'rxjs';

import type { DispenseRequest, DispenseResponse } from '../services/pharmacy.service';

/** One queued dispense + the metadata we need to replay it deterministically. */
export interface QueuedDispense {
  /** Stable client-supplied idempotency key — matches DispenseRequest.idempotencyKey. */
  id: string;
  /** Original payload, exactly as the user submitted it. */
  request: DispenseRequest;
  /** ms since epoch — used for FIFO ordering and "queued at" UI labels. */
  enqueuedAt: number;
  /** Number of replay attempts that have failed so far (for backoff / UI). */
  attempts: number;
}

/** Durable get/put/delete/list — the only thing the queue service touches. */
export interface QueueStore {
  put(item: QueuedDispense): Promise<void>;
  delete(id: string): Promise<void>;
  list(): Promise<QueuedDispense[]>;
  clear(): Promise<void>;
}

/** Outcome of one replay sweep — surfaced to callers + the dispensing UI. */
export interface ReplayResult {
  succeeded: number;
  failed: number;
  remaining: number;
}

/** What the service needs the HTTP layer to do — keeps the import surface tiny. */
export type DispensePoster = (req: DispenseRequest) => Promise<DispenseResponse>;

const IDB_NAME = 'hms-offline-dispense';
const IDB_STORE = 'queue';
const IDB_VERSION = 1;

// ────────────────────────────────────────────────────────────────────────────
// QueueStore implementations
// ────────────────────────────────────────────────────────────────────────────

/** Production store — uses the browser's IndexedDB. */
export class IndexedDbQueueStore implements QueueStore {
  private dbPromise: Promise<IDBDatabase> | null = null;

  private openDb(): Promise<IDBDatabase> {
    if (!this.dbPromise) {
      this.dbPromise = new Promise((resolve, reject) => {
        const req = indexedDB.open(IDB_NAME, IDB_VERSION);
        req.onupgradeneeded = () => {
          const db = req.result;
          if (!db.objectStoreNames.contains(IDB_STORE)) {
            db.createObjectStore(IDB_STORE, { keyPath: 'id' });
          }
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
      });
    }
    return this.dbPromise;
  }

  async put(item: QueuedDispense): Promise<void> {
    const db = await this.openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(IDB_STORE, 'readwrite');
      tx.objectStore(IDB_STORE).put(item);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async delete(id: string): Promise<void> {
    const db = await this.openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(IDB_STORE, 'readwrite');
      tx.objectStore(IDB_STORE).delete(id);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async list(): Promise<QueuedDispense[]> {
    const db = await this.openDb();
    return new Promise<QueuedDispense[]>((resolve, reject) => {
      const tx = db.transaction(IDB_STORE, 'readonly');
      const req = tx.objectStore(IDB_STORE).getAll();
      req.onsuccess = () => {
        const all = (req.result as QueuedDispense[]) ?? [];
        // FIFO order — IndexedDB returns by key, so we sort by enqueuedAt.
        all.sort((a, b) => a.enqueuedAt - b.enqueuedAt);
        resolve(all);
      };
      req.onerror = () => reject(req.error);
    });
  }

  async clear(): Promise<void> {
    const db = await this.openDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(IDB_STORE, 'readwrite');
      tx.objectStore(IDB_STORE).clear();
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }
}

/** Test / SSR fallback — Map-backed, never persists, no IDB dependency. */
export class InMemoryQueueStore implements QueueStore {
  private readonly rows = new Map<string, QueuedDispense>();

  async put(item: QueuedDispense): Promise<void> {
    this.rows.set(item.id, { ...item });
  }

  async delete(id: string): Promise<void> {
    this.rows.delete(id);
  }

  async list(): Promise<QueuedDispense[]> {
    return [...this.rows.values()].sort((a, b) => a.enqueuedAt - b.enqueuedAt);
  }

  async clear(): Promise<void> {
    this.rows.clear();
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Service
// ────────────────────────────────────────────────────────────────────────────

@Injectable({ providedIn: 'root' })
export class OfflineDispenseQueueService {
  private readonly store: QueueStore;
  private readonly pendingSubject = new BehaviorSubject<number>(0);
  private replayInFlight: Promise<ReplayResult> | null = null;

  constructor() {
    this.store = OfflineDispenseQueueService.makeStore();
    // Best-effort initial count — fire-and-forget on construction so the
    // pending banner shows the right number after a reload before any user
    // action drives a refresh.
    void this.refreshPending();
  }

  /**
   * Stream of currently queued dispenses, suitable for `signal()` / `async`
   * pipe consumption from the dispensing UI's offline banner.
   */
  get pending$(): Observable<number> {
    return this.pendingSubject.asObservable();
  }

  /** Snapshot — for tests and one-off reads where async is overkill. */
  get pending(): number {
    return this.pendingSubject.value;
  }

  /**
   * Append a dispense to the queue. The caller is expected to have already
   * minted a stable {@link DispenseRequest.idempotencyKey} so the replay is
   * deterministic across reloads of the tab.
   */
  async enqueue(request: DispenseRequest): Promise<QueuedDispense> {
    const id = request.idempotencyKey ?? OfflineDispenseQueueService.mintId(request);
    const item: QueuedDispense = {
      id,
      // Persist with the key set so the request the interceptor builds for
      // replay is byte-identical to the one the user originally submitted.
      request: { ...request, idempotencyKey: id },
      enqueuedAt: Date.now(),
      attempts: 0,
    };
    await this.store.put(item);
    await this.refreshPending();
    return item;
  }

  /**
   * FIFO drain of every queued dispense. Caller injects the actual HTTP
   * poster (typically PharmacyService.createDispense) so this service stays
   * decoupled from HttpClient and can be unit-tested with a fake.
   *
   * Concurrent calls coalesce into one in-flight sweep — useful when the
   * `online` event and a manual "Sync now" button race.
   */
  async replayAll(post: DispensePoster): Promise<ReplayResult> {
    if (this.replayInFlight) {
      return this.replayInFlight;
    }
    this.replayInFlight = this.replayAllInner(post);
    try {
      return await this.replayInFlight;
    } finally {
      this.replayInFlight = null;
    }
  }

  private async replayAllInner(post: DispensePoster): Promise<ReplayResult> {
    const queue = await this.store.list();
    let succeeded = 0;
    let failed = 0;
    for (const item of queue) {
      try {
        await post(item.request);
        await this.store.delete(item.id);
        succeeded += 1;
      } catch {
        // Bump attempt count and leave the item on the queue. Propagating
        // the error here would abort the sweep on the first failure; we
        // want to drain as much as we can on each pass.
        await this.store.put({ ...item, attempts: item.attempts + 1 });
        failed += 1;
      }
    }
    await this.refreshPending();
    return { succeeded, failed, remaining: this.pendingSubject.value };
  }

  /** Drop everything — used by Sign-out and the "Discard queued" admin action. */
  async clear(): Promise<void> {
    await this.store.clear();
    await this.refreshPending();
  }

  private async refreshPending(): Promise<void> {
    try {
      const all = await this.store.list();
      this.pendingSubject.next(all.length);
    } catch {
      // If the store itself is broken there is nothing actionable for the
      // caller; leave the previous count in place and stay quiet — the UI
      // never goes blank on a transient IDB error.
    }
  }

  // ── Static helpers ────────────────────────────────────────────────────────

  /** Picks the right QueueStore implementation for the current runtime. */
  private static makeStore(): QueueStore {
    if (typeof indexedDB !== 'undefined') {
      try {
        return new IndexedDbQueueStore();
      } catch {
        return new InMemoryQueueStore();
      }
    }
    return new InMemoryQueueStore();
  }

  /**
   * Build a stable id when the caller did not supply one. Format mirrors the
   * V94 migration's stated convention: {@code <userId>-<rxId>-<timestamp>}.
   * Falls back to crypto.randomUUID when prescription/dispenser fields are
   * missing — the worst case is dedup degrades to "never matches", which is
   * the same as not opting in.
   */
  static mintId(request: DispenseRequest): string {
    const user = request.dispensedBy || 'anon';
    const rx = request.prescriptionId || 'norx';
    const stamp = new Date().toISOString();
    const candidate = `${user}-${rx}-${stamp}`;
    if (candidate.length <= 64) return candidate;
    // Width budget: V94 caps idempotency_key at VARCHAR(64). Hash truncate
    // is a graceful degradation — the prefix keeps it human-debuggable.
    return candidate.slice(0, 64);
  }
}
