import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import {
  AssignmentPublicService,
  AssignmentPublicView,
} from '../../services/assignment-public.service';
import { RoleWelcomeComponent } from './role-welcome';

describe('RoleWelcomeComponent', () => {
  let service: jasmine.SpyObj<AssignmentPublicService>;
  let fixture: ComponentFixture<RoleWelcomeComponent>;

  const view = (over: Partial<AssignmentPublicView> = {}): AssignmentPublicView => ({
    assignmentId: 'id-1',
    assignmentCode: 'A-1',
    roleName: 'ROLE_NURSE',
    roleCode: 'ROLE_NURSE',
    roleDescription: null,
    hospitalName: 'CHU Test',
    hospitalCode: 'CHU',
    hospitalAddress: null,
    assigneeName: 'Awa',
    confirmationVerified: false,
    confirmationVerifiedAt: null,
    profileCompletionUrl: null,
    profileChecklist: null,
    tempUsername: null,
    tempPassword: null,
    ...over,
  });

  function create(code: string | null): void {
    TestBed.configureTestingModule({
      imports: [RoleWelcomeComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: AssignmentPublicService, useValue: service },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap(code ? { assignment: code } : {}) },
          },
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      ONBOARDING: {
        OTHER_ROLES_HINT: 'Confirmez chaque autre rôle avec son propre code.',
        ERR_CODE_INCORRECT: 'Code incorrect.',
        ERR_NO_LINK_CODE: 'Aucun code dans le lien.',
      },
    });
    translate.use('fr');
    fixture = TestBed.createComponent(RoleWelcomeComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<AssignmentPublicService>('AssignmentPublicService', [
      'getPublicView',
      'verifyCode',
      'verifyByPhone',
    ]);
  });

  afterEach(() => TestBed.resetTestingModule());

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('confirms the assignment named in the link and points at the other roles of the welcome mail', () => {
    service.getPublicView.and.returnValue(of(view()));
    service.verifyCode.and.returnValue(of(view({ confirmationVerified: true })));
    create('A-2');

    expect(service.getPublicView).toHaveBeenCalledWith('A-2');
    const component = fixture.componentInstance;
    component.digits = ['1', '2', '3', '4', '5', '6'];
    component.submitCode();
    fixture.detectChanges();

    expect(service.verifyCode).toHaveBeenCalledWith('A-1', '123456');
    expect(text()).toContain('Confirmez chaque autre rôle avec son propre code.');
  });

  it('shows a wrong code in the reader language, never as English source text', () => {
    service.getPublicView.and.returnValue(of(view()));
    service.verifyCode.and.returnValue(throwError(() => ({ status: 400 })));
    create('A-1');

    const component = fixture.componentInstance;
    component.digits = ['1', '2', '3', '4', '5', '6'];
    component.submitCode();
    fixture.detectChanges();

    expect(text()).toContain('Code incorrect.');
    expect(text()).not.toContain('Please check your email');
  });

  // QA D1123: with no link (an SMS reader, "I have a code" on the login page)
  // the page was a dead end ("Aucun code dans le lien… vérifiez votre e-mail").
  // It now takes the phone number and the code from the SMS.
  describe('"I have a code" (QA D1123)', () => {
    const fill = (id: string, value: string) => {
      const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
        `#${id}`,
      )!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    const submit = () => {
      (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLButtonElement>('[data-testid="activate-by-phone-submit"]')!
        .click();
      fixture.detectChanges();
    };

    it('with no link, offers the phone + code form instead of a dead end', () => {
      create(null);
      expect(service.getPublicView).not.toHaveBeenCalled();
      expect(
        fixture.nativeElement.querySelector('[data-testid="activate-by-phone"]'),
      ).not.toBeNull();
      expect(text()).not.toContain('Aucun code dans le lien.');
    });

    it('activates with the phone number and the SMS code, and shows the sign-in details once', async () => {
      service.verifyByPhone.and.returnValue(
        of(view({ confirmationVerified: true, tempUsername: '70003201', tempPassword: 'T3mp!' })),
      );
      create(null);
      await fixture.whenStable();
      fill('rw-phone', '70 00 32 01');
      fill('rw-phone-code', '482913');
      await fixture.whenStable();
      submit();

      expect(service.verifyByPhone).toHaveBeenCalledOnceWith('70 00 32 01', '482913');
      expect(fixture.componentInstance.pageState).toBe('success');
      expect(text()).toContain('70003201');
    });

    it('refuses an empty phone or a short code on the page, before any request', async () => {
      create(null);
      await fixture.whenStable();
      submit();
      expect(fixture.componentInstance.verifyError).toBe('ONBOARDING.ERR_PHONE_REQUIRED');
      fill('rw-phone', '70003201');
      fill('rw-phone-code', '123');
      await fixture.whenStable();
      submit();
      expect(fixture.componentInstance.verifyError).toBe('ONBOARDING.ERR_CODE_FORMAT');
      expect(service.verifyByPhone).not.toHaveBeenCalled();
    });

    it("shows the server's refusal (wrong code, too many tries) in the reader's language", async () => {
      service.verifyByPhone.and.returnValue(
        throwError(() => ({ status: 400, error: { message: 'Code de confirmation invalide.' } })),
      );
      create(null);
      await fixture.whenStable();
      fill('rw-phone', '70003201');
      fill('rw-phone-code', '000000');
      await fixture.whenStable();
      submit();
      expect(text()).toContain('Code de confirmation invalide.');
    });
  });
});
