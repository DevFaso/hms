import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MfaEnrollComponent } from './mfa-enroll';
import { AuthService } from '../auth/auth.service';
import { MfaService } from '../auth/mfa.service';

/**
 * The enrolment wizard (start → QR → verify → backup codes). Asserted through
 * the rendered DOM in French, the shape #661 settled on.
 */
describe('MfaEnrollComponent', () => {
  let fixture: ComponentFixture<MfaEnrollComponent>;
  let mfa: jasmine.SpyObj<MfaService>;
  let auth: jasmine.SpyObj<AuthService>;
  let router: { getCurrentNavigation: jasmine.Spy; navigateByUrl: jasmine.Spy };

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (selector: string): string =>
    host().querySelector(selector)?.textContent?.trim() ?? '';
  const click = (selector: string): void => {
    (host().querySelector(selector) as HTMLElement).click();
    fixture.detectChanges();
  };

  function create(mfaToken?: string): void {
    router.getCurrentNavigation.and.returnValue(
      mfaToken ? { extras: { state: { mfaToken } } } : null,
    );
    fixture = TestBed.createComponent(MfaEnrollComponent);
    fixture.detectChanges();
  }

  function verify(code: string): void {
    fixture.componentInstance.totpCode = code;
    host().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    mfa = jasmine.createSpyObj<MfaService>('MfaService', ['enroll', 'verifyEnrollment']);
    mfa.enroll.and.returnValue(
      of({
        secret: 'JBSWY3DPEHPK3PXP',
        otpauthUri: 'otpauth://totp/x',
        qrCodeDataUrl: 'data:image/png;base64,AAAA',
        backupCodes: ['aaaa-1111', 'bbbb-2222'],
      }),
    );
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['setToken', 'logout']);
    router = {
      getCurrentNavigation: jasmine.createSpy('getCurrentNavigation'),
      navigateByUrl: jasmine.createSpy('navigateByUrl'),
    };

    TestBed.configureTestingModule({
      imports: [MfaEnrollComponent, TranslateModule.forRoot()],
      providers: [
        { provide: MfaService, useValue: mfa },
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: router },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      COMMON: { NEXT: 'Suivant' },
      MFA: {
        ENROLL_TITLE: 'Activer la double authentification',
        ENROLL_BEGIN: 'Commencer',
        ENROLL_QR_ALT: 'Code QR à scanner',
        ENROLL_START_FAILED: 'Impossible de démarrer.',
        ENROLL_INVALID_CODE: 'Saisissez un code à 6 chiffres.',
        ENROLL_WRONG_CODE: 'Code incorrect.',
        ENROLL_BACKUP_TITLE: 'Codes de secours',
      },
    });
  });

  it('keeps the temporary MFA token for the enrolment calls', () => {
    create('mfa-1');
    expect(auth.setToken).toHaveBeenCalledWith('mfa-1', false);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(text('h1')).toBe('Activer la double authentification');
  });

  it('sends anyone who arrived without an MFA token back to sign-in', () => {
    create();
    expect(auth.setToken).not.toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('walks start → QR → verify → backup codes', () => {
    mfa.verifyEnrollment.and.returnValue(of({ message: 'ok' }));
    create('mfa-1');
    expect(text('.step-content .btn-primary')).toBe('Commencer');

    click('.step-content .btn-primary');
    const img = host().querySelector('.qr-container img') as HTMLImageElement;
    expect(img.getAttribute('alt')).toBe('Code QR à scanner');
    expect(text('.secret-code')).toBe('JBSWY3DPEHPK3PXP');
    expect(text('.step-content .btn-primary')).toBe('Suivant');

    click('.step-content .btn-primary');
    expect(host().querySelector('input[name="totpCode"]')).not.toBeNull();

    verify('123456');
    expect(mfa.verifyEnrollment).toHaveBeenCalledWith('123456');
    expect(text('h2')).toBe('Codes de secours');
    const codes = Array.from(host().querySelectorAll('.backup-code')).map((el) =>
      el.textContent?.trim(),
    );
    expect(codes).toEqual(['aaaa-1111', 'bbbb-2222']);
  });

  it('shows the translated fallback when enrolment cannot start', () => {
    mfa.enroll.and.returnValue(throwError(() => ({ status: 500 })));
    create('mfa-1');
    click('.step-content .btn-primary');
    expect(text('[role="alert"]')).toBe('Impossible de démarrer.');
    expect(host().querySelector('.qr-container')).toBeNull();
  });

  it('refuses a short code on screen without calling the server', () => {
    create('mfa-1');
    fixture.componentInstance.step.set('verify');
    fixture.detectChanges();
    verify('12');
    expect(mfa.verifyEnrollment).not.toHaveBeenCalled();
    expect(text('[role="alert"]')).toBe('Saisissez un code à 6 chiffres.');
  });

  it('keeps the verify step and says why when the code is wrong', () => {
    mfa.verifyEnrollment.and.returnValue(throwError(() => ({ status: 400 })));
    create('mfa-1');
    fixture.componentInstance.step.set('verify');
    fixture.detectChanges();
    verify('654321');
    expect(text('[role="alert"]')).toBe('Code incorrect.');
    expect(host().querySelector('input[name="totpCode"]')).not.toBeNull();
  });

  it('drops the temporary token and returns to sign-in when done', () => {
    create('mfa-1');
    fixture.componentInstance.step.set('backup');
    fixture.detectChanges();
    click('.backup-actions .btn-primary');
    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });
});
