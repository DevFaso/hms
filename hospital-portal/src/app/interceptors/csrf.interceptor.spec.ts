import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { csrfInterceptor } from './csrf.interceptor';

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
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([csrfInterceptor])),
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
});
