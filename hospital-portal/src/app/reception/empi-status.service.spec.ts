import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EmpiStatusService } from './empi-status.service';
import { BACKGROUND_REQUEST } from '../services/ws-transport';

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
    const probe = http.expectOne('/empi/candidates/status');
    // Round 6: a background request, so a 403 for a role the matcher does not
    // admit never redirects the shell to /error/403.
    expect(probe.request.context.get(BACKGROUND_REQUEST)).toBeTrue();
    probe.flush({ enabled: true });
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
    // A failed read is "unknown" (null), not "off", and is not kept.
    expect(service.enabled()).toBeNull();
    // Review round 16: and it is flagged, so the menu still offers the entry.
    expect(service.failed()).toBeTrue();
    service.load().subscribe();
    http.expectOne('/empi/candidates/status').flush({ enabled: true });
    expect(service.enabled()).toBeTrue();
    expect(service.failed()).toBeFalse();
  });
});
