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
import { TestBed } from '@angular/core/testing';

import {
  InMemoryQueueStore,
  OFFLINE_QUEUE_STORE,
  OfflineDispenseQueueService,
  type QueueStore,
  type ReplayResult,
} from './offline-dispense-queue.service';
import type { DispenseRequest, DispenseResponse } from '../services/pharmacy.service';

/**
 * Builds a service that uses the supplied store via Angular DI. TestBed
 * resolves OFFLINE_QUEUE_STORE BEFORE the service constructor runs, so the
 * IDB-backed default factory never executes — Copilot review on PR #287
 * caught the older field-monkeypatching seam, which raced the constructor's
 * IDB connection.
 */
function makeService(store: QueueStore): OfflineDispenseQueueService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [{ provide: OFFLINE_QUEUE_STORE, useValue: store }],
  });
  return TestBed.inject(OfflineDispenseQueueService);
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

    it('falls back to randomized suffix when UUID inputs would overflow VARCHAR(64)', () => {
      // Two consecutive calls for the same logical dispense (UUIDs that
      // make the natural candidate > 64 chars). Without the
      // crypto.randomUUID fallback the mint would slice off the timestamp
      // entirely and produce identical ids — the exact collision
      // Copilot review on PR #287 flagged.
      const longUserUuid = '12345678-1234-1234-1234-123456789abc';
      const longRxUuid = 'abcdefab-1111-2222-3333-aaaaaaaaaaaa';
      const a = OfflineDispenseQueueService.mintId(
        baseRequest({ dispensedBy: longUserUuid, prescriptionId: longRxUuid }),
      );
      const b = OfflineDispenseQueueService.mintId(
        baseRequest({ dispensedBy: longUserUuid, prescriptionId: longRxUuid }),
      );
      expect(a.length).toBeLessThanOrEqual(64);
      expect(b.length).toBeLessThanOrEqual(64);
      expect(a).not.toEqual(b);
      // Human-debuggable prefix preserved so a DBA reading the column can
      // still trace which user/prescription the row came from.
      expect(a.startsWith('12345678-abcdefab-')).toBeTrue();
      expect(b.startsWith('12345678-abcdefab-')).toBeTrue();
    });
  });
});
