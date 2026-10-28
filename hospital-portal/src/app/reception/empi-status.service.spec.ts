import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EmpiStatusService } from './empi-status.service';

describe('EmpiStatusService (QA D987)', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    }),
  );

  it('reads the switch once and shares it', () => {
    const service = TestBed.inject(EmpiStatusService);
    const http = TestBed.inject(HttpTestingController);
    service.load().subscribe();
    service.load().subscribe();
    http.expectOne('/empi/candidates/status').flush({ enabled: true });
    expect(service.enabled()).toBeTrue();
    http.verify();
  });

  it('does not keep a failed read: the next call asks again', () => {
    const service = TestBed.inject(EmpiStatusService);
    const http = TestBed.inject(HttpTestingController);
    service.load().subscribe();
    http
      .expectOne('/empi/candidates/status')
      .flush('down', { status: 502, statusText: 'Bad Gateway' });
    expect(service.enabled()).toBeFalse();
    service.load().subscribe();
    http.expectOne('/empi/candidates/status').flush({ enabled: true });
    expect(service.enabled()).toBeTrue();
  });
});
