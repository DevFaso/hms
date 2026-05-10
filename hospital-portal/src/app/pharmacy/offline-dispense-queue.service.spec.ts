/**
 * Spec for OfflineDispenseQueueService — roadmap row 4 / T-68.
 *
 * Drives the public surface (enqueue, replayAll, clear, pending$) against
 * an InMemoryQueueStore so the test never touches IndexedDB. The IDB-backed
 * store has no logic of its own beyond the IDB protocol, so a Karma spec
 * is the wrong place for it — it would live in a Playwright fixture, which
 * row 5's pharmacy-tier1-dispense.spec.ts could grow into when needed.
 *
 * Two realities the implementation must protect:
 *   1. Replay is FIFO and survives partial failure — one bad request must
 *      not abort the sweep for the rest of the queue.
 *   2. Concurrent `replayAll` callers (online event + manual Sync button)
 *      coalesce into one in-flight sweep so the same item is not POSTed
 *      twice from the same browser tab.
 */
import {
  InMemoryQueueStore,
  OfflineDispenseQueueService,
  type QueueStore,
  type ReplayResult,
} from './offline-dispense-queue.service';
import type { DispenseRequest, DispenseResponse } from '../services/pharmacy.service';

/** Constructs a service whose store is the supplied test double. */
function makeService(store: QueueStore): OfflineDispenseQueueService {
  const svc = new OfflineDispenseQueueService();
  // Reach in once to swap the real store for the test fake — the service
  // never re-creates the store, so this hands us full control without
  // forcing the production class to expose a setter.
  (svc as unknown as { store: QueueStore }).store = store;
  return svc;
}

function baseRequest(overrides: Partial<DispenseRequest> = {}): DispenseRequest {
  return {
    prescriptionId: 'rx-1',
    patientId: 'pt-1',
    pharmacyId: 'ph-1',
    dispensedBy: 'user-1',
    medicationName: 'Amoxicilline 500 mg',
    quantityRequested: 30,
    quantityDispensed: 30,
    ...overrides,
  };
}

function fakeResponse(): DispenseResponse {
  return {
    id: 'd-1',
    prescriptionId: 'rx-1',
    patientId: 'pt-1',
    pharmacyId: 'ph-1',
    dispensedById: 'user-1',
    medicationName: 'Amoxicilline 500 mg',
    quantityRequested: 30,
    quantityDispensed: 30,
    status: 'COMPLETED',
    dispensedAt: '2026-05-10T12:00:00Z',
  } as DispenseResponse;
}

describe('OfflineDispenseQueueService', () => {
  let store: InMemoryQueueStore;
  let svc: OfflineDispenseQueueService;

  beforeEach(() => {
    store = new InMemoryQueueStore();
    svc = makeService(store);
  });

  describe('enqueue', () => {
    it('persists the request with a stable idempotency key', async () => {
      const queued = await svc.enqueue(baseRequest({ idempotencyKey: 'fixed-key-1' }));
      expect(queued.id).toBe('fixed-key-1');
      expect(queued.request.idempotencyKey).toBe('fixed-key-1');
      const onDisk = await store.list();
      expect(onDisk.length).toBe(1);
      expect(onDisk[0].id).toBe('fixed-key-1');
    });

    it('mints an id when the caller did not supply one', async () => {
      const queued = await svc.enqueue(baseRequest());
      expect(queued.id).toBeTruthy();
      expect(queued.id.length).toBeLessThanOrEqual(64);
      expect(queued.request.idempotencyKey).toBe(queued.id);
    });

    it('updates pending$ after enqueue', async () => {
      let observed = -1;
      svc.pending$.subscribe((n) => (observed = n));
      await svc.enqueue(baseRequest({ idempotencyKey: 'k1' }));
      expect(observed).toBe(1);
      await svc.enqueue(baseRequest({ idempotencyKey: 'k2' }));
      expect(observed).toBe(2);
    });
  });

  describe('replayAll', () => {
    it('drains in FIFO order and removes each successful item', async () => {
      const calls: string[] = [];
      const post = (req: DispenseRequest) => {
        calls.push(req.idempotencyKey ?? 'no-key');
        return Promise.resolve(fakeResponse());
      };
      await svc.enqueue(baseRequest({ idempotencyKey: 'k1' }));
      await svc.enqueue(baseRequest({ idempotencyKey: 'k2' }));
      await svc.enqueue(baseRequest({ idempotencyKey: 'k3' }));

      const result: ReplayResult = await svc.replayAll(post);

      expect(calls).toEqual(['k1', 'k2', 'k3']);
      expect(result.succeeded).toBe(3);
      expect(result.failed).toBe(0);
      expect(result.remaining).toBe(0);
      expect((await store.list()).length).toBe(0);
    });

    it('keeps failing items on the queue and bumps their attempt count', async () => {
      const post = (req: DispenseRequest) =>
        req.idempotencyKey === 'k2'
          ? Promise.reject(new Error('boom'))
          : Promise.resolve(fakeResponse());
      await svc.enqueue(baseRequest({ idempotencyKey: 'k1' }));
      await svc.enqueue(baseRequest({ idempotencyKey: 'k2' }));
      await svc.enqueue(baseRequest({ idempotencyKey: 'k3' }));

      const result = await svc.replayAll(post);

      expect(result.succeeded).toBe(2); // k1 + k3
      expect(result.failed).toBe(1); // k2
      expect(result.remaining).toBe(1);
      const left = await store.list();
      expect(left.length).toBe(1);
      expect(left[0].id).toBe('k2');
      expect(left[0].attempts).toBe(1);
    });

    it('coalesces concurrent calls into one in-flight sweep', async () => {
      let postCount = 0;
      // Hold the post promise open so the second replayAll() runs while the
      // first is still mid-sweep. If coalescing works, the second call
      // resolves to the same ReplayResult and doesn't double-post.
      let resolvePost: ((value: DispenseResponse) => void) | null = null;
      const post = () => {
        postCount += 1;
        return new Promise<DispenseResponse>((resolve) => {
          resolvePost = resolve;
        });
      };
      await svc.enqueue(baseRequest({ idempotencyKey: 'k1' }));

      const first = svc.replayAll(post);
      const second = svc.replayAll(post);

      // Let the post resolve so both promises wrap up.
      // Microtask + setTimeout(0) is enough — the inner sweep awaits post().
      await Promise.resolve();
      expect(resolvePost).not.toBeNull();
      resolvePost!(fakeResponse());

      const [a, b] = await Promise.all([first, second]);
      expect(postCount).toBe(1);
      expect(a).toEqual(b);
    });
  });

  describe('clear', () => {
    it('drops every queued item and resets pending$', async () => {
      let observed = -1;
      svc.pending$.subscribe((n) => (observed = n));
      await svc.enqueue(baseRequest({ idempotencyKey: 'k1' }));
      await svc.enqueue(baseRequest({ idempotencyKey: 'k2' }));
      expect(observed).toBe(2);

      await svc.clear();

      expect(observed).toBe(0);
      expect((await store.list()).length).toBe(0);
    });
  });

  describe('mintId', () => {
    it('produces a string within the 64-char V94 budget', () => {
      const id = OfflineDispenseQueueService.mintId(baseRequest());
      expect(id.length).toBeLessThanOrEqual(64);
    });

    it('encodes the user and prescription ids when present', () => {
      const id = OfflineDispenseQueueService.mintId(
        baseRequest({ dispensedBy: 'usr-7', prescriptionId: 'rx-9' }),
      );
      expect(id.startsWith('usr-7-rx-9-')).toBeTrue();
    });
  });
});
