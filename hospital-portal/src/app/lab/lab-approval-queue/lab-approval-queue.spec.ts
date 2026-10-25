import { ComponentFixture, TestBed } from '@angular/core/testing';
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
  let fixture: ComponentFixture<LabApprovalQueueComponent>;

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
          {
            id: 'f',
            approvalStatus: 'PENDING_DIRECTOR_APPROVAL',
            submittedById: 'me',
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
    fixture = TestBed.createComponent(LabApprovalQueueComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('keeps an approved definition in the queue so it can be activated', () => {
    const c = create(['ROLE_LAB_MANAGER']);
    expect(c.definitions().map((d) => d.id)).toEqual(['a', 'c', 'd', 'e', 'f']);
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

  function hints(): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.own-submission-hint'),
    ).map((el) => (el.textContent ?? '').trim());
  }

  it('QA D644: the QA-step hint names the roles that may complete the QA review', () => {
    create(['ROLE_QUALITY_MANAGER']);
    fixture.componentInstance.setStatusFilter('PENDING_QA_REVIEW');
    fixture.detectChanges();
    expect(hints()).toEqual(['LAB_APPROVAL.ACTION.OWN_SUBMISSION_HINT_QA']);
  });

  it('QA D644: the approve-step hint names only the lab director', () => {
    create(['ROLE_LAB_DIRECTOR']);
    fixture.componentInstance.setStatusFilter('PENDING_DIRECTOR_APPROVAL');
    fixture.detectChanges();
    expect(hints()).toEqual(['LAB_APPROVAL.ACTION.OWN_SUBMISSION_HINT_APPROVE']);
  });

  function buttons(selector: string): number {
    return (fixture.nativeElement as HTMLElement).querySelectorAll(selector).length;
  }

  it('QA D723: the quality manager can reject a definition pending director approval', () => {
    create(['ROLE_QUALITY_MANAGER']);
    fixture.componentInstance.setStatusFilter('PENDING_DIRECTOR_APPROVAL');
    fixture.detectChanges();
    expect(buttons('.btn-reject')).toBe(1);
    expect(buttons('.btn-approve')).toBe(0);
    expect(hints()).toEqual([]);
  });

  it('QA D722: the QA reviewer is not offered Approve unless the server says so', () => {
    const c = create(['ROLE_LAB_DIRECTOR']);
    const reviewed = {
      id: 'g',
      approvalStatus: 'PENDING_DIRECTOR_APPROVAL',
      submittedById: 'other',
      reviewedById: 'me',
    } as LabTestDefinition;
    expect(c.isOwnReview(reviewed)).toBeTrue();
    expect(c.canApprove(reviewed)).toBeFalse();
    expect(c.canApprove({ ...reviewed, reviewerMayApprove: false })).toBeFalse();
    expect(c.canApprove({ ...reviewed, reviewerMayApprove: true })).toBeTrue();
  });

  it('QA D707: the submitting director approves after an independent QA review', () => {
    const c = create(['ROLE_LAB_DIRECTOR']);
    const def = {
      id: 'h',
      approvalStatus: 'PENDING_DIRECTOR_APPROVAL',
      submittedById: 'me',
      reviewedById: 'qm',
    } as LabTestDefinition;
    expect(c.canApprove(def)).toBeTrue();
    expect(c.canApprove({ ...def, reviewedById: null })).toBeFalse();
  });
});
