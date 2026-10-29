import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EmpiStatusService } from './empi-status.service';
import { BACKGROUND_REQUEST } from '../services/ws-transport';
import { AuthService } from '../auth/auth.service';

describe('EmpiStatusService (QA D987)', () => {
  let held: string[];
  beforeEach(() => {
    held = ['ROLE_RECEPTIONIST'];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { hasAnyRole: (rs: string[]) => rs.some((r) => held.includes(r)) },
        },
      ],
    });
  });

  for (const role of ['ROLE_PATIENT', 'ROLE_PHARMACIST', 'ROLE_LAB_TECHNICIAN', 'ROLE_LAB_DIRECTOR']) {
    it(`QA D1012: never asks for ${role}, which the matcher refuses, and reads it as off`, () => {
      held = [role];
      const service = TestBed.inject(EmpiStatusService);
      const http = TestBed.inject(HttpTestingController);
      let answer: boolean | null | undefined;
      service.load().subscribe((on) => (answer = on));
      service.load().subscribe();
      http.expectNone('/empi/candidates/status');
      expect(answer).toBeFalse();
      expect(service.enabled()).toBeFalse();
      expect(service.failed()).toBeFalse();
      http.verify();
    });
  }

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
