import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ProfileService } from './profile.service';

describe('Recovery-code read-back request', () => {
  it('carries the confirmed saved destination to the server', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    const service = TestBed.inject(ProfileService);
    const http = TestBed.inject(HttpTestingController);
    for (const value of ['backup@example.test', '+22670123456']) {
      service.sendRecoveryVerificationCode('contact-1', value).subscribe();
      const request = http.expectOne('/auth/credentials/recovery/contact-1/send-code');
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ confirmedContactValue: value });
      request.flush({ message: 'sent', contactType: value.includes('@') ? 'EMAIL' : 'PHONE' });
    }
    http.verify();
  });
});
