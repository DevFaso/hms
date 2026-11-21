import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

import { AdminAssignmentsComponent } from './admin-assignments';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import {
  AssignmentActivationCode,
  AssignmentAdminService,
  AssignmentResponse,
} from '../services/assignment-admin.service';
import { HospitalService } from '../services/hospital.service';
import { RoleService } from '../services/role.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

/**
 * QA r65 D2764: « Remettez le code en main propre » was a dead end, because
 * the 6-digit activation code was shown nowhere. The registrar (the person
 * who created the assignment) now shows it from the assignment's details.
 */
describe('AdminAssignmentsComponent — the activation code, handed over in person (QA r65 D2764)', () => {
  const ME = 'user-me';
  let service: jasmine.SpyObj<AssignmentAdminService>;

  const pending = (over: Partial<AssignmentResponse> = {}): AssignmentResponse => ({
    id: 'a-1',
    assignmentCode: 'ASG-1',
    userName: 'Test Médecin 1',
    userEmail: 'medecin1@example.test',
    hospitalName: 'Clinique Test D',
    roleCode: 'ROLE_DOCTOR',
    active: false,
    confirmationVerified: false,
    registeredByUserId: ME,
    registeredByUserName: 'Test Admin D',
    ...over,
  });

  function render(row: AssignmentResponse) {
    service = jasmine.createSpyObj<AssignmentAdminService>('AssignmentAdminService', [
      'list',
      'revealActivationCode',
    ]);
    service.list.and.returnValue(
      of({ content: [row], totalElements: 1, totalPages: 1, size: 20, number: 0 }) as never,
    );
    TestBed.configureTestingModule({
      imports: [AdminAssignmentsComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: AuthService,
          useValue: { getUserProfile: () => ({ id: ME }), getUserId: () => ME },
        },
        { provide: AssignmentAdminService, useValue: service },
        {
          provide: HospitalService,
          useValue: {
            list: () => of([]),
            getMyHospitalAsResponse: () => of({ id: 'h-1', name: 'Clinique Test D' }),
          },
        },
        { provide: RoleService, useValue: { list: () => of([]) } },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => false,
            hasAnyActiveRole: () => true,
            activeHospitalId: 'h-1',
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'warning', 'info']),
        },
        { provide: ConfirmService, useValue: { ask: () => Promise.resolve(true) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(AdminAssignmentsComponent);
    fixture.detectChanges();
    fixture.componentInstance.openDetail(row);
    fixture.detectChanges();
    const el = (id: string) =>
      (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(`[data-testid="${id}"]`);
    return { fixture, component: fixture.componentInstance, el };
  }

  afterEach(() => TestBed.resetTestingModule());

  it('the registrar is offered « Afficher le code », and the code appears only once asked for', () => {
    const { fixture, el } = render(pending());
    service.revealActivationCode.and.returnValue(
      of({
        assignmentId: 'a-1',
        confirmationCode: '482913',
        expiresAt: '2026-11-22T10:00:00',
      } as AssignmentActivationCode),
    );

    expect(el('activation-code')).not.toBeNull();
    expect(el('activation-code-value')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('482913');

    el('activation-code-show')!.click();
    fixture.detectChanges();

    expect(service.revealActivationCode).toHaveBeenCalledOnceWith('a-1');
    expect(el('activation-code-value')!.textContent).toContain('482913');
    expect(el('activation-code-expiry')).not.toBeNull();
    expect(el('activation-code-show')).toBeNull();
  });

  it('the code is forgotten when the dialog closes, and is not shown for the next row', () => {
    const { fixture, component, el } = render(pending());
    service.revealActivationCode.and.returnValue(
      of({ assignmentId: 'a-1', confirmationCode: '482913' } as AssignmentActivationCode),
    );
    el('activation-code-show')!.click();
    fixture.detectChanges();
    expect(component.activationCode()).not.toBeNull();

    component.closeModal();
    expect(component.activationCode()).toBeNull();

    component.openDetail(pending({ id: 'a-2' }));
    fixture.detectChanges();
    expect(el('activation-code-value')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('482913');
  });

  it('a code that answers after the dialog moved to another row is never shown there', () => {
    const { fixture, component, el } = render(pending());
    const answer = new Subject<AssignmentActivationCode>();
    service.revealActivationCode.and.returnValue(answer);
    el('activation-code-show')!.click();

    component.openDetail(pending({ id: 'a-2' }));
    answer.next({ assignmentId: 'a-1', confirmationCode: '482913' });
    fixture.detectChanges();

    expect(component.activationCode()).toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('482913');
  });

  it('someone who did not create the assignment is told who can show the code, and offered no button', () => {
    const { el } = render(pending({ registeredByUserId: 'someone-else' }));

    expect(el('activation-code-show')).toBeNull();
    expect(el('activation-code-registrar-only')).not.toBeNull();
    expect(service.revealActivationCode).not.toHaveBeenCalled();
  });

  it('a verified assignment has no activation code section at all', () => {
    const { el } = render(pending({ confirmationVerified: true, active: true }));
    expect(el('activation-code')).toBeNull();
  });

  it('a refusal (an expired code) is said in the dialog, with the server reason', () => {
    const { fixture, el } = render(pending());
    service.revealActivationCode.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: {
              message: 'Ce code d’activation a expiré. Générez un nouveau code, puis remettez-le.',
            },
          }),
      ),
    );

    el('activation-code-show')!.click();
    fixture.detectChanges();

    expect(el('activation-code-error')!.textContent).toContain('a expiré');
    expect(el('activation-code-value')).toBeNull();
  });
});
