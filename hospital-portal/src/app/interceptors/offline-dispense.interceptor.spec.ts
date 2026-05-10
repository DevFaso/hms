/**
 * Spec for offlineDispenseInterceptor — roadmap row 4 / T-68.
 *
 * Drives the interceptor through HttpTestingController so we can simulate
 * the four cases the implementation must distinguish:
 *
 *   1. Non-dispense request           → pass-through, queue untouched
 *   2. Dispense + 200 OK              → queue untouched, body unchanged
 *   3. Dispense + network failure (0) → queued, synthetic 202 returned
 *   4. Dispense + 4xx business error  → NOT queued, error rethrown
 *
 * The OfflineDispenseQueueService is stubbed via DI so we never touch
 * IndexedDB or persist state across tests.
 */
import {
  HttpClient,
  HttpErrorResponse,
  HttpResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { offlineDispenseInterceptor } from './offline-dispense.interceptor';
import {
  OfflineDispenseQueueService,
  type QueuedDispense,
} from '../pharmacy/offline-dispense-queue.service';
import type { DispenseRequest } from '../services/pharmacy.service';

class StubQueue {
  enqueued: QueuedDispense[] = [];
  enqueue(req: DispenseRequest): Promise<QueuedDispense> {
    const item: QueuedDispense = {
      id: req.idempotencyKey ?? 'stub-key',
      request: req,
      enqueuedAt: 1700000000000,
      attempts: 0,
    };
    this.enqueued.push(item);
    return Promise.resolve(item);
  }
}

function baseRequest(overrides: Partial<DispenseRequest> = {}): DispenseRequest {
  return {
    prescriptionId: 'rx-1',
    patientId: 'pt-1',
    pharmacyId: 'ph-1',
    dispensedBy: 'user-1',
    medicationName: 'Amoxicilline',
    quantityRequested: 30,
    quantityDispensed: 30,
    ...overrides,
  };
}

describe('offlineDispenseInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let queue: StubQueue;

  beforeEach(() => {
    queue = new StubQueue();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([offlineDispenseInterceptor])),
        provideHttpClientTesting(),
        { provide: OfflineDispenseQueueService, useValue: queue },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('passes through non-dispense requests untouched', () => {
    http.get('/api/patients').subscribe();
    const req = httpMock.expectOne('/api/patients');
    expect(req.request.method).toBe('GET');
    req.flush({ ok: true });
    expect(queue.enqueued).toEqual([]);
  });

  it('stamps an idempotencyKey on the outbound dispense body when missing', () => {
    http.post('/api/pharmacy/dispense', baseRequest()).subscribe();
    const req = httpMock.expectOne('/api/pharmacy/dispense');
    const body = req.request.body as DispenseRequest;
    expect(body.idempotencyKey).toBeTruthy();
    expect(body.idempotencyKey?.length ?? 0).toBeLessThanOrEqual(64);
    req.flush({ id: 'd-1' });
  });

  it('preserves a caller-supplied idempotencyKey verbatim', () => {
    http.post('/api/pharmacy/dispense', baseRequest({ idempotencyKey: 'caller-key' })).subscribe();
    const req = httpMock.expectOne('/api/pharmacy/dispense');
    expect((req.request.body as DispenseRequest).idempotencyKey).toBe('caller-key');
    req.flush({ id: 'd-1' });
  });

  it('returns 202 Accepted and queues the request on a network failure (status 0)', (done) => {
    http.post('/api/pharmacy/dispense', baseRequest({ idempotencyKey: 'k1' })).subscribe({
      next: (res: any) => {
        expect(res?.success).toBeTrue();
        expect(res?.data?.status).toBe('QUEUED');
        expect(queue.enqueued.length).toBe(1);
        expect(queue.enqueued[0].id).toBe('k1');
        done();
      },
      error: () => done.fail('expected success after queueing, got error'),
    });
    const req = httpMock.expectOne('/api/pharmacy/dispense');
    // Network error in Angular HttpClient surfaces as status 0.
    req.error(new ProgressEvent('error'), { status: 0, statusText: 'Network Error' });
  });

  it('queues on 503 Service Unavailable (transient backend outage)', (done) => {
    http.post('/api/pharmacy/dispense', baseRequest({ idempotencyKey: 'k2' })).subscribe({
      next: () => {
        expect(queue.enqueued.length).toBe(1);
        done();
      },
      error: () => done.fail('expected queue on 503, got error'),
    });
    const req = httpMock.expectOne('/api/pharmacy/dispense');
    req.flush({ message: 'down' }, { status: 503, statusText: 'Service Unavailable' });
  });

  it('does NOT queue on a 400 business-rule rejection — error propagates', (done) => {
    http.post('/api/pharmacy/dispense', baseRequest()).subscribe({
      next: () => done.fail('expected error on 400, got success'),
      error: (err: HttpErrorResponse) => {
        expect(err.status).toBe(400);
        expect(queue.enqueued).toEqual([]);
        done();
      },
    });
    const req = httpMock.expectOne('/api/pharmacy/dispense');
    req.flush({ message: 'insufficient stock' }, { status: 400, statusText: 'Bad Request' });
  });
});
