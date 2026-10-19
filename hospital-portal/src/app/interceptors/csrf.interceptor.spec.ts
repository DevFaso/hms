import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { csrfInterceptor, resetCsrfBootstrapForTests } from './csrf.interceptor';

function setXsrfCookie(value: string): void {
  document.cookie = `XSRF-TOKEN=${encodeURIComponent(value)}; path=/`;
}

function clearXsrfCookie(): void {
  document.cookie = 'XSRF-TOKEN=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';
}

describe('csrfInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    clearXsrfCookie();
    resetCsrfBootstrapForTests();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr(), withInterceptors([csrfInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    clearXsrfCookie();
    httpMock.verify();
  });

  it('does not touch non-mutating requests', () => {
    setXsrfCookie('tok');
    http.get('/data').subscribe();
    const req = httpMock.expectOne('/data');
    expect(req.request.headers.has('X-XSRF-TOKEN')).toBeFalse();
    req.flush({});
  });

  it('echoes the XSRF-TOKEN cookie on mutating requests', () => {
    setXsrfCookie('tok-123');
    http.post('/data', {}).subscribe();
    const req = httpMock.expectOne('/data');
    expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('tok-123');
    req.flush({});
  });

  it('attaches the token to DELETE requests too', () => {
    setXsrfCookie('tok-del');
    http.delete('/data/1').subscribe();
    const req = httpMock.expectOne('/data/1');
    expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('tok-del');
    req.flush({});
  });

  it('never intercepts the CSRF bootstrap endpoint itself', () => {
    http.post('/auth/csrf-token', {}).subscribe();
    const req = httpMock.expectOne('/auth/csrf-token');
    expect(req.request.headers.has('X-XSRF-TOKEN')).toBeFalse();
    req.flush({});
  });

  it('never attaches the token to cross-origin requests', () => {
    setXsrfCookie('tok');
    http.post('https://other.test/data', {}).subscribe();
    const req = httpMock.expectOne('https://other.test/data');
    expect(req.request.headers.has('X-XSRF-TOKEN')).toBeFalse();
    req.flush({});
  });

  it('self-heals a missing cookie by bootstrapping before the mutating request', () => {
    http.post('/data', {}).subscribe();

    // Bootstrap GET fires first; simulate Spring setting the cookie in response.
    const bootstrap = httpMock.expectOne('/auth/csrf-token');
    setXsrfCookie('fresh-tok');
    bootstrap.flush(null);

    const replay = httpMock.expectOne('/data');
    expect(replay.request.headers.get('X-XSRF-TOKEN')).toBe('fresh-tok');
    replay.flush({});
  });

  it('proceeds without the header when bootstrap fails to set the cookie', () => {
    http.post('/data', {}).subscribe();

    const bootstrap = httpMock.expectOne('/auth/csrf-token');
    bootstrap.flush(null); // no cookie issued

    const replay = httpMock.expectOne('/data');
    expect(replay.request.headers.has('X-XSRF-TOKEN')).toBeFalse();
    replay.flush({});
  });

  it('proceeds without the header when the bootstrap request errors', () => {
    http.post('/data', {}).subscribe();

    const bootstrap = httpMock.expectOne('/auth/csrf-token');
    bootstrap.error(new ProgressEvent('network error'));

    const replay = httpMock.expectOne('/data');
    expect(replay.request.headers.has('X-XSRF-TOKEN')).toBeFalse();
    replay.flush({});
  });

  it('QA D180: concurrent first requests share one bootstrap and send the same token', () => {
    http.post('/billing-invoices/search', {}).subscribe();
    http.post('/other', {}).subscribe();

    // One bootstrap only: two would each rotate the cookie under the other.
    const bootstrap = httpMock.expectOne('/auth/csrf-token');
    setXsrfCookie('shared-tok');
    bootstrap.flush(null);

    const search = httpMock.expectOne('/billing-invoices/search');
    const other = httpMock.expectOne('/other');
    expect(search.request.headers.get('X-XSRF-TOKEN')).toBe('shared-tok');
    expect(other.request.headers.get('X-XSRF-TOKEN')).toBe('shared-tok');
    search.flush({});
    other.flush({});
  });

  it('QA D180: replays once when a 403 came back after the cookie was rotated', () => {
    setXsrfCookie('old-tok');
    let body: unknown;
    http.post('/billing-invoices/search', {}).subscribe((b) => (body = b));

    const first = httpMock.expectOne('/billing-invoices/search');
    expect(first.request.headers.get('X-XSRF-TOKEN')).toBe('old-tok');
    setXsrfCookie('new-tok');
    first.flush({}, { status: 403, statusText: 'Forbidden' });

    const retry = httpMock.expectOne('/billing-invoices/search');
    expect(retry.request.headers.get('X-XSRF-TOKEN')).toBe('new-tok');
    retry.flush({ ok: true });
    expect(body).toEqual({ ok: true });
  });

  it('passes a 403 on when the cookie did not change (a real refusal)', () => {
    setXsrfCookie('same-tok');
    let status = 0;
    http.post('/data', {}).subscribe({ error: (e) => (status = e.status) });

    httpMock.expectOne('/data').flush({}, { status: 403, statusText: 'Forbidden' });
    httpMock.expectNone('/data');
    expect(status).toBe(403);
  });
});
