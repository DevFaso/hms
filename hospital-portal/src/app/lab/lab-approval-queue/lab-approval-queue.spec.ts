import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of, throwError } from 'rxjs';
import { LabApprovalQueueComponent } from './lab-approval-queue';
import { LabService, LabTestDefinition } from '../../services/lab.service';
import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../core/toast.service';

type SearchPage =
  ReturnType<LabService['searchTestDefinitions']> extends Observable<infer T> ? T : never;

describe('LabApprovalQueueComponent (D124)', () => {
  let lab: jasmine.SpyObj<LabService>;
  let toast: jasmine.SpyObj<ToastService>;

  function create(roles: string[], userId = 'me'): LabApprovalQueueComponent {
    lab = jasmine.createSpyObj<LabService>('LabService', [
      'searchTestDefinitions',
      'submitApprovalAction',
    ]);
    lab.searchTestDefinitions.and.returnValue(
      of({
        content: [
          { id: 'a', approvalStatus: 'APPROVED' } as LabTestDefinition,
          { id: 'b', approvalStatus: 'ACTIVE' } as LabTestDefinition,
          { id: 'c', approvalStatus: 'REJECTED' } as LabTestDefinition,
          {
            id: 'd',
            approvalStatus: 'PENDING_QA_REVIEW',
            submittedById: 'me',
          } as LabTestDefinition,
          {
            id: 'e',
            approvalStatus: 'PENDING_QA_REVIEW',
            submittedById: 'other',
          } as LabTestDefinition,
        ],
      } as unknown as SearchPage),
    );
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['hasAnyRole', 'getUserId']);
    auth.getUserId.and.returnValue(userId);
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);
    auth.hasAnyRole.and.callFake((expected: string[]) => expected.some((r) => roles.includes(r)));
    TestBed.configureTestingModule({
      imports: [LabApprovalQueueComponent, TranslateModule.forRoot()],
      providers: [
        { provide: LabService, useValue: lab },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
      ],
    });
    const fixture = TestBed.createComponent(LabApprovalQueueComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('keeps an approved definition in the queue so it can be activated', () => {
    const c = create(['ROLE_LAB_MANAGER']);
    expect(c.definitions().map((d) => d.id)).toEqual(['a', 'c', 'd', 'e']);
    expect(c.canActivate).toBeTrue();
  });

  it('does not offer activation to a role the server refuses', () => {
    const c = create(['ROLE_LAB_SCIENTIST']);
    expect(c.canActivate).toBeFalse();
  });

  it('QA D417: keeps a rejected definition so it can be corrected and resubmitted', () => {
    const c = create(['ROLE_LAB_SCIENTIST']);
    c.setStatusFilter('REJECTED');
    expect(c.filtered().map((d) => d.id)).toEqual(['c']);
    expect(c.countByStatus('REJECTED')).toBe(1);
  });

  it('QA D534: does not offer the QA pass on the signed-in user own submission', () => {
    const c = create(['ROLE_LAB_DIRECTOR']);
    const [own, other] = c.definitions().filter((d) => d.approvalStatus === 'PENDING_QA_REVIEW');
    expect(c.isOwnSubmission(own)).toBeTrue();
    expect(c.isOwnSubmission(other)).toBeFalse();
  });

  it('QA D534: shows the server reason when an approval step is refused', () => {
    const c = create(['ROLE_LAB_DIRECTOR']);
    lab.submitApprovalAction.and.returnValue(
      throwError(() => ({ error: { message: 'Four-eyes reason' } })),
    );
    c.openApprovalModal(c.definitions()[3], 'COMPLETE_QA_REVIEW');
    c.submitApprovalAction();
    expect(toast.error).toHaveBeenCalledWith('Four-eyes reason');
  });
});
