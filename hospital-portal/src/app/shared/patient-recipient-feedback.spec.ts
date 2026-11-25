import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { registerLocaleData } from '@angular/common';
import frenchLocale from '@angular/common/locales/fr';
import spanishLocale from '@angular/common/locales/es';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RoleContextService } from '../core/role-context.service';
import { PatientRecipientVerificationComponent } from './patient-recipient-verification.component';
import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';

describe('D3705 recipient feedback', () => {
  registerLocaleData(frenchLocale);
  registerLocaleData(spanishLocale);
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

  for (const [language, translations] of Object.entries({ en, fr, es })) {
    for (const scope of ['patient', 'desk']) {
      for (const channel of ['SMS', 'EMAIL'] as const) {
        it(`announces the server-selected masked ${channel} contact in ${language} on the ${scope} page`, () => {
          const translate = TestBed.inject(TranslateService);
          translate.setTranslation(language, translations);
          translate.use(language).subscribe();
          if (scope === 'desk') fixture.componentRef.setInput('patientId', 'cdx-patient');
          fixture.detectChanges();
          const root = scope === 'desk' ? '/patients/cdx-patient' : '/me/patient';
          http.expectOne(`${root}/recipient-verification`).flush([{ channel, verified: false }]);
          fixture.componentInstance.request(channel);
          const masked = channel === 'SMS' ? '•••• 3456' : 'p•••@e•••.test';
          http.expectOne(`${root}/recipient-verification/${channel}/request`).flush({
            channel,
            verified: false,
            maskedRecipient: masked,
            expiresAt: '2026-11-24T08:10:00',
          });
          fixture.detectChanges();
          const announcement = fixture.nativeElement.querySelector(
            '[data-testid="verification-code-sent"]',
          ) as HTMLElement;
          expect(announcement.getAttribute('role')).toBe('status');
          expect(announcement.textContent).toContain(masked);
          expect(announcement.textContent).toContain(
            translations.RECIPIENT_VERIFICATION.CODE_SENT.split('{{contact}}')[0].trim(),
          );
          expect(announcement.textContent).not.toContain('RECIPIENT_VERIFICATION.');
          expect(announcement.textContent).not.toContain('2026-11-24T');
          expect(announcement.textContent).not.toContain('patient@example.test');
          expect(fixture.componentInstance.statuses()[0].verified).toBeFalse();
        });
      }
    }
  }

  it('ignores a previous patient request response after switching patients', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr').subscribe();
    fixture.componentRef.setInput('patientId', 'old');
    fixture.detectChanges();
    http
      .expectOne('/patients/old/recipient-verification')
      .flush([{ channel: 'SMS', verified: false }]);
    fixture.componentInstance.request('SMS');
    const oldRequest = http.expectOne('/patients/old/recipient-verification/SMS/request');
    fixture.componentRef.setInput('patientId', 'current');
    fixture.detectChanges();
    http
      .expectOne('/patients/current/recipient-verification')
      .flush([{ channel: 'SMS', verified: false, maskedRecipient: '•••• 9999' }]);
    oldRequest.flush({ channel: 'SMS', verified: false, maskedRecipient: '•••• 1111' });
    fixture.detectChanges();
    expect(fixture.componentInstance.pending()).toBeNull();
    expect(fixture.componentInstance.sentTo()).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('1111');
    expect(fixture.nativeElement.textContent).toContain('9999');
  });
});
