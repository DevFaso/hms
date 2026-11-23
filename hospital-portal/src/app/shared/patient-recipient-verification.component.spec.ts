import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { RoleContextService } from '../core/role-context.service';
import { PatientRecipientVerificationComponent } from './patient-recipient-verification.component';

describe('Patient recipient verification', () => {
  let fixture: ComponentFixture<PatientRecipientVerificationComponent>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PatientRecipientVerificationComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: RoleContextService, useValue: { hasAnyActiveRole: () => true } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PatientRecipientVerificationComponent);
  });
  afterEach(() => http.verify());
  const rows = [
    { channel: 'SMS', verified: false },
    { channel: 'EMAIL', verified: true },
  ];

  it('self-service uses no patient id or recipient from the browser, then clears the code', () => {
    fixture.detectChanges();
    http.expectOne('/me/patient/recipient-verification').flush(rows);
    fixture.detectChanges();
    fixture.componentInstance.request('SMS');
    const request = http.expectOne('/me/patient/recipient-verification/SMS/request');
    expect(request.request.body).toEqual({});
    request.flush({ channel: 'SMS', verified: false });
    fixture.componentInstance.code = '123456';
    fixture.componentInstance.confirm('SMS');
    const confirmation = http.expectOne('/me/patient/recipient-verification/SMS/confirm');
    expect(confirmation.request.body).toEqual({ code: '123456' });
    confirmation.flush({ channel: 'SMS', verified: true });
    fixture.detectChanges();
    expect(fixture.componentInstance.code).toBe('');
    expect(fixture.nativeElement.querySelector('input')).toBeNull();
    expect(fixture.componentInstance.statuses()[0].verified).toBeTrue();
  });

  it('the desk uses the scoped patient route and a refusal never becomes verified', () => {
    fixture.componentRef.setInput('patientId', 'cdx-patient');
    fixture.detectChanges();
    http.expectOne('/patients/cdx-patient/recipient-verification').flush(rows);
    fixture.componentInstance.request('SMS');
    http
      .expectOne('/patients/cdx-patient/recipient-verification/SMS/request')
      .flush({ channel: 'SMS', verified: false });
    fixture.componentInstance.code = '123456';
    fixture.componentInstance.confirm('SMS');
    http
      .expectOne('/patients/cdx-patient/recipient-verification/SMS/confirm')
      .flush({}, { status: 400, statusText: 'Invalid code' });
    fixture.detectChanges();
    expect(fixture.componentInstance.statuses()[0].verified).toBeFalse();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('failed status reads show retry rather than verified controls', () => {
    fixture.detectChanges();
    http
      .expectOne('/me/patient/recipient-verification')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.componentInstance.statuses()).toEqual([]);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('input')).toBeNull();
  });
});
