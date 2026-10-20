import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of } from 'rxjs';
import { LabApprovalQueueComponent } from './lab-approval-queue';
import { LabService, LabTestDefinition } from '../../services/lab.service';
import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../core/toast.service';

type SearchPage =
  ReturnType<LabService['searchTestDefinitions']> extends Observable<infer T> ? T : never;

describe('LabApprovalQueueComponent (D124)', () => {
  function create(roles: string[]): LabApprovalQueueComponent {
    const lab = jasmine.createSpyObj<LabService>('LabService', [
      'searchTestDefinitions',
      'submitApprovalAction',
    ]);
    lab.searchTestDefinitions.and.returnValue(
      of({
        content: [
          { id: 'a', approvalStatus: 'APPROVED' } as LabTestDefinition,
          { id: 'b', approvalStatus: 'ACTIVE' } as LabTestDefinition,
          { id: 'c', approvalStatus: 'REJECTED' } as LabTestDefinition,
        ],
      } as unknown as SearchPage),
    );
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['hasAnyRole']);
    auth.hasAnyRole.and.callFake((expected: string[]) => expected.some((r) => roles.includes(r)));
    TestBed.configureTestingModule({
      imports: [LabApprovalQueueComponent, TranslateModule.forRoot()],
      providers: [
        { provide: LabService, useValue: lab },
        { provide: AuthService, useValue: auth },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    });
    const fixture = TestBed.createComponent(LabApprovalQueueComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('keeps an approved definition in the queue so it can be activated', () => {
    const c = create(['ROLE_LAB_MANAGER']);
    expect(c.definitions().map((d) => d.id)).toEqual(['a', 'c']);
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
});
