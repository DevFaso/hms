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

  it('translates the missing-code message', () => {
    create(null);
    fixture.detectChanges();

    expect(service.getPublicView).not.toHaveBeenCalled();
    expect(text()).toContain('Aucun code dans le lien.');
  });
});
