import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MfaService } from './mfa.service';

describe('MfaService — enrolment and the step-up', () => {
  let service: MfaService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MfaService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('a first enrolment sends no step-up', () => {
    service.enroll().subscribe();

    const req = httpMock.expectOne('/auth/mfa/enroll');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.has('X-Mfa-Token')).toBeFalse();
    req.flush({});
  });

  it('replacing the authenticator sends the code as X-Mfa-Token', () => {
    service.enroll('12345678').subscribe();

    const req = httpMock.expectOne('/auth/mfa/enroll');
    expect(req.request.headers.get('X-Mfa-Token')).toBe('12345678');
    req.flush({});
  });
});
