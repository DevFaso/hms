import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import {
  HttpClient,
  HttpContext,
  HttpContextToken,
  HttpErrorResponse,
  HttpEventType,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  apiTimeoutInterceptor,
  API_REQUEST_TIMEOUT_MS,
  API_UPLOAD_TIMEOUT_MS,
} from './api-timeout.interceptor';
import { ApiTimeoutService } from '../core/api-timeout.service';
import {
  retryPaymentWithSameKey,
  PAYMENT_RETRY_DELAY_MS,
  paymentOutcomeUnknown,
} from '../shared/payment-request-key';
import fr from '../../assets/i18n/fr.json';
import { API_TIMEOUT_PROFILE, API_EXPORT_TIMEOUT_MS } from '../core/api-request-timeout';

describe('D3602 API request deadline', () => {
  let client: HttpClient;
  let backend: HttpTestingController;
  let notice: ApiTimeoutService;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withInterceptors([apiTimeoutInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    client = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    notice = TestBed.inject(ApiTimeoutService);
  });
  afterEach(() => backend.verify());

  for (const method of ['GET', 'HEAD', 'OPTIONS', 'POST', 'PUT', 'PATCH', 'DELETE']) {
    it(`bounds a stalled ${method}, aborts the transport and never replays it`, fakeAsync(() => {
      let failure: HttpErrorResponse | undefined;
      client.request(method, '/api/example').subscribe({ error: (e) => (failure = e) });
      const request = backend.expectOne('/api/example');
      request.event({ type: HttpEventType.Sent });
      tick(API_REQUEST_TIMEOUT_MS - 1);
      expect(failure).toBeUndefined();
      tick(1);
      expect(failure?.status).toBe(504);
      expect(failure?.error.code).toBe('REQUEST_TIMEOUT');
      expect(failure?.error.message).toContain('délai');
      expect(request.cancelled).toBeTrue();
      expect(notice.failure()).toBe(['GET', 'HEAD', 'OPTIONS'].includes(method) ? 'read' : 'write');
      tick(API_REQUEST_TIMEOUT_MS);
      backend.expectNone('/api/example');
    }));
  }

  it('allows progress uploads longer, but progress cannot remove their upper bound', fakeAsync(() => {
    let failure: HttpErrorResponse | undefined;
    client
      .post('/api/upload', new Blob(['example']), { observe: 'events', reportProgress: true })
      .subscribe({ error: (e) => (failure = e) });
    const request = backend.expectOne('/api/upload');
    tick(API_UPLOAD_TIMEOUT_MS - 1);
    expect(failure).toBeUndefined();
    request.event({ type: HttpEventType.UploadProgress, loaded: 1, total: 2 });
    tick(1);
    expect(failure?.status).toBe(504);
    expect(request.cancelled).toBeTrue();
  }));

  it('gives multipart uploads without progress reporting their upload context and longer deadline', fakeAsync(() => {
    const form = new FormData();
    form.append('file', new Blob(['report']), 'report.pdf');
    let uploaded = false;
    client.post('/api/documents', form).subscribe(() => (uploaded = true));
    const request = backend.expectOne('/api/documents');
    expect(request.request.reportProgress).toBeFalse();
    expect(request.request.context.get(API_TIMEOUT_PROFILE)).toBe('upload');
    tick(API_REQUEST_TIMEOUT_MS + 1);
    expect(request.cancelled).toBeFalse();
    request.flush({ id: 'document' });
    tick(API_UPLOAD_TIMEOUT_MS);
    expect(uploaded).toBeTrue();
    expect(notice.failure()).toBeNull();
  }));

  it('gives generated PDFs/exports their export context and longer bounded deadline', fakeAsync(() => {
    let error: HttpErrorResponse | undefined;
    client
      .get('/api/record.pdf', { responseType: 'blob' })
      .subscribe({ error: (value) => (error = value) });
    const request = backend.expectOne('/api/record.pdf');
    expect(request.request.context.get(API_TIMEOUT_PROFILE)).toBe('export');
    tick(API_REQUEST_TIMEOUT_MS + 1);
    expect(request.cancelled).toBeFalse();
    tick(API_EXPORT_TIMEOUT_MS - API_REQUEST_TIMEOUT_MS - 1);
    expect(error?.status).toBe(504);
    expect(request.cancelled).toBeTrue();
  }));

  it('allows a completed export after 20 seconds and removes its deadline', fakeAsync(() => {
    let file: Blob | undefined;
    client.get('/api/export.csv', { responseType: 'blob' }).subscribe((value) => (file = value));
    const request = backend.expectOne('/api/export.csv');
    tick(API_REQUEST_TIMEOUT_MS + 1);
    request.flush(new Blob(['export']));
    tick(API_EXPORT_TIMEOUT_MS);
    expect(file?.size).toBe(6);
    expect(notice.failure()).toBeNull();
  }));

  it('honours an explicit profile for JSON exports and preserves other context tokens', fakeAsync(() => {
    const scope = new HttpContextToken<string>(() => '');
    const context = new HttpContext()
      .set(API_TIMEOUT_PROFILE, 'export')
      .set(scope, 'hospital-scope');
    client.get('/api/export/fhir', { context }).subscribe();
    const request = backend.expectOne('/api/export/fhir');
    expect(request.request.context.get(scope)).toBe('hospital-scope');
    tick(API_REQUEST_TIMEOUT_MS + 1);
    expect(request.cancelled).toBeFalse();
    request.flush({ resourceType: 'Bundle' });
    tick(API_EXPORT_TIMEOUT_MS);
    expect(notice.failure()).toBeNull();
  }));

  it('preserves the existing keyed payment retry, with the same key and a bounded final outcome', fakeAsync(() => {
    const body = { clientRequestId: 'same-payment-key', amount: 100 };
    let failure: HttpErrorResponse | undefined;
    client
      .post('/api/payment', body)
      .pipe(retryPaymentWithSameKey(body.clientRequestId))
      .subscribe({ error: (e) => (failure = e) });
    const first = backend.expectOne('/api/payment');
    tick(API_REQUEST_TIMEOUT_MS);
    expect(first.cancelled).toBeTrue();
    expect(notice.failure()).toBe('write');
    expect(failure).toBeUndefined();
    tick(PAYMENT_RETRY_DELAY_MS);
    const keyedRetry = backend.expectOne('/api/payment');
    expect(keyedRetry.request.body).toEqual(first.request.body);
    tick(API_REQUEST_TIMEOUT_MS);
    expect(keyedRetry.cancelled).toBeTrue();
    expect(failure?.error.code).toBe('REQUEST_TIMEOUT');
    expect(paymentOutcomeUnknown(failure)).toBeTrue();
    backend.expectNone('/api/payment');
  }));

  it('does not abort local translations needed to explain a slow API', fakeAsync(() => {
    let body: unknown;
    client.get('./assets/i18n/fr.json').subscribe((value) => (body = value));
    const request = backend.expectOne('./assets/i18n/fr.json');
    tick(API_REQUEST_TIMEOUT_MS + 1);
    expect(request.cancelled).toBeFalse();
    request.flush({ TITLE: 'Connexion' });
    expect(body).toEqual({ TITLE: 'Connexion' });
    expect(notice.failure()).toBeNull();
  }));

  it('leaves a successful response intact and removes its timer', fakeAsync(() => {
    let body: unknown;
    client.get('/api/data').subscribe((value) => (body = value));
    backend.expectOne('/api/data').flush({ value: 12 });
    tick(API_REQUEST_TIMEOUT_MS);
    expect(body).toEqual({ value: 12 });
    expect(notice.failure()).toBeNull();
  }));

  it('removes the deadline on caller cancellation', fakeAsync(() => {
    const subscription = client.get('/api/data').subscribe();
    const request = backend.expectOne('/api/data');
    subscription.unsubscribe();
    tick(API_REQUEST_TIMEOUT_MS);
    expect(request.cancelled).toBeTrue();
    expect(notice.failure()).toBeNull();
  }));

  it('never lets a later read or unrelated response erase an uncertain write', () => {
    notice.noteTimeout('POST');
    notice.noteTimeout('GET');
    client.get('/api/data').subscribe();
    backend.expectOne('/api/data').flush({});
    expect(notice.failure()).toBe('write');
  });
});
