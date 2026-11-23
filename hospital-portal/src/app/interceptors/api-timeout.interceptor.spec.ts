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

  it('does not retain an inferred deadline in a context shared by ordinary and file requests', () => {
    const scope = new HttpContextToken<string>(() => '');
    const context = new HttpContext().set(scope, 'hospital-a');
    for (const responseType of ['json', 'blob', 'json', 'arraybuffer'] as const) {
      client.request('GET', '/api/shared-context', { context, responseType }).subscribe();
      const request = backend.expectOne('/api/shared-context');
      expect(request.request.context.get(API_TIMEOUT_PROFILE)).toBe(
        responseType === 'json' ? 'default' : 'export',
      );
      expect(request.request.context.get(scope)).toBe('hospital-a');
      expect(context.has(API_TIMEOUT_PROFILE)).toBeFalse();
      request.flush(
        responseType === 'blob'
          ? new Blob(['file'])
          : responseType === 'arraybuffer'
            ? new ArrayBuffer(1)
            : {},
      );
    }
  });

  it('allows array-buffer downloads beyond the ordinary deadline', fakeAsync(() => {
    let bytes: ArrayBuffer | undefined;
    client
      .get('/api/export.bin', { responseType: 'arraybuffer' })
      .subscribe((value) => (bytes = value));
    const request = backend.expectOne('/api/export.bin');
    tick(API_REQUEST_TIMEOUT_MS + 1);
    expect(request.cancelled).toBeFalse();
    request.flush(new ArrayBuffer(8));
    expect(bytes?.byteLength).toBe(8);
    tick(API_EXPORT_TIMEOUT_MS);
    expect(notice.failure()).toBeNull();
  }));

  for (const url of [
    '/api/auth/login',
    '/api/auth/token/refresh',
    '/api/auth/mfa/verify',
    '/api/auth/verify-password',
  ]) {
    it(`a stalled session protocol call (${url}) is a read failure, not an uncertain saved action`, fakeAsync(() => {
      client.post(url, {}).subscribe({ error: () => undefined });
      const request = backend.expectOne(url);
      tick(API_REQUEST_TIMEOUT_MS);
      expect(request.cancelled).toBeTrue();
      expect(notice.failure()).toBe('read');
    }));
  }

  it('a slow logout is bounded without raising a notice on the next login screen', fakeAsync(() => {
    client.post('/api/auth/logout', {}).subscribe({ error: () => undefined });
    const request = backend.expectOne('/api/auth/logout');
    tick(API_REQUEST_TIMEOUT_MS);
    expect(request.cancelled).toBeTrue();
    expect(notice.failure()).toBeNull();
  }));

  it('ignores late timeout notices from a session that has already ended', fakeAsync(() => {
    client.post('/api/old-action', {}).subscribe({ error: () => undefined });
    const request = backend.expectOne('/api/old-action');
    notice.resetForSession();
    tick(API_REQUEST_TIMEOUT_MS);
    expect(request.cancelled).toBeTrue();
    expect(notice.failure()).toBeNull();
  }));

  it('clears a prior warning when sign-in succeeds', () => {
    notice.noteTimeout('POST');
    client.post('/api/auth/login', {}).subscribe();
    backend.expectOne('/api/auth/login').flush({ accessToken: 'new-session' });
    expect(notice.failure()).toBeNull();
  });

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
