import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ConnectivityBannerComponent, RESTORED_NOTICE_MS } from './connectivity-banner.component';
import { ConnectivityService } from '../../core/connectivity.service';
import { ChunkRecoveryService } from '../../core/chunk-recovery.service';
import { connectivityInterceptor } from '../../interceptors/connectivity.interceptor';
import fr from '../../../assets/i18n/fr.json';

/**
 * QA D2352 (r63): with the connection gone, a page failed with nothing on
 * screen. The state comes from the browser's events and every request's
 * outcome, and the banner says it in French with a retry.
 */
describe('Connectivity (QA D2352)', () => {
  let fixture: ComponentFixture<ConnectivityBannerComponent>;
  let connectivity: ConnectivityService;
  let http: HttpTestingController;
  let client: HttpClient;
  const failedUrl = signal<string | null>(null);

  beforeEach(() => {
    failedUrl.set(null);
    TestBed.configureTestingModule({
      imports: [ConnectivityBannerComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withInterceptors([connectivityInterceptor])),
        provideHttpClientTesting(),
        { provide: ChunkRecoveryService, useValue: { failedUrl } },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    connectivity = TestBed.inject(ConnectivityService);
    connectivity.state.set('online');
    connectivity.restored.set(false);
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    fixture = TestBed.createComponent(ConnectivityBannerComponent);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  const el = (): HTMLElement => fixture.nativeElement;
  const banner = (): HTMLElement | null =>
    el().querySelector('[data-testid="connectivity-banner"]');

  it('says nothing while requests reach the server', () => {
    client.get('/api/x').subscribe();
    http.expectOne('/api/x').flush({});
    fixture.detectChanges();
    expect(banner()).toBeNull();
  });

  it('a request that never reaches the server shows the banner in French, with a retry', () => {
    client.get('/api/x').subscribe({ error: () => undefined });
    http.expectOne('/api/x').error(new ProgressEvent('error'), { status: 0 });
    fixture.detectChanges();
    expect(banner()).not.toBeNull();
    expect(banner()!.getAttribute('role')).toBe('alert');
    expect(banner()!.textContent).toContain('Ce que vous avez saisi reste sur la page');
    expect(banner()!.textContent).not.toContain('CONNECTIVITY.');
    expect(el().querySelector('[data-testid="connectivity-retry"]')?.textContent).toContain(
      'Réessayer',
    );
  });

  it('a server error is an answer: the connection is fine', () => {
    connectivity.state.set('unreachable');
    client.get('/api/x').subscribe({ error: () => undefined });
    http.expectOne('/api/x').flush({}, { status: 500, statusText: 'Server Error' });
    expect(connectivity.state()).toBe('online');
  });

  it('the browser going offline shows the offline wording', () => {
    window.dispatchEvent(new Event('offline'));
    fixture.detectChanges();
    expect(connectivity.state()).toBe('offline');
    expect(banner()!.textContent).toContain('Pas de connexion Internet');
    window.dispatchEvent(new Event('online'));
    fixture.detectChanges();
    expect(banner()).toBeNull();
    expect(el().querySelector('[data-testid="connectivity-restored"]')).not.toBeNull();
  });

  it('retry asks the server: still down is said, back hides the banner', fakeAsync(() => {
    connectivity.state.set('unreachable');
    fixture.detectChanges();
    (el().querySelector('[data-testid="connectivity-retry"]') as HTMLElement).click();
    http.expectOne('/api/auth/csrf-token').error(new ProgressEvent('error'), { status: 0 });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="connectivity-still-down"]')).not.toBeNull();

    (el().querySelector('[data-testid="connectivity-retry"]') as HTMLElement).click();
    http.expectOne('/api/auth/csrf-token').flush(null);
    fixture.detectChanges();
    expect(banner()).toBeNull();
    expect(el().querySelector('[data-testid="connectivity-restored"]')).not.toBeNull();
    tick(RESTORED_NOTICE_MS);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="connectivity-restored"]')).toBeNull();
  }));

  it('stays out of the way of the page-failed notice, which says the same', () => {
    connectivity.state.set('offline');
    failedUrl.set('/my-appointments');
    fixture.detectChanges();
    expect(banner()).toBeNull();
  });
});
