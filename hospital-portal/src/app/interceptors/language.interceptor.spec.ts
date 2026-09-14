import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { languageInterceptor } from './language.interceptor';

describe('languageInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('lang');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([languageInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    backend.verify();
    localStorage.removeItem('lang');
  });

  it('sends French when nothing is stored — the product default, not the browser', () => {
    http.get('/api/ping').subscribe();
    const req = backend.expectOne('/api/ping');
    expect(req.request.headers.get('Accept-Language')).toBe('fr');
    req.flush({});
  });

  it('sends the language the user chose', () => {
    localStorage.setItem('lang', 'es');
    http.get('/api/ping').subscribe();
    const req = backend.expectOne('/api/ping');
    expect(req.request.headers.get('Accept-Language')).toBe('es');
    req.flush({});
  });

  it('never forwards an unsupported stored value', () => {
    localStorage.setItem('lang', 'de');
    http.get('/api/ping').subscribe();
    const req = backend.expectOne('/api/ping');
    expect(req.request.headers.get('Accept-Language')).toBe('fr');
    req.flush({});
  });
});
