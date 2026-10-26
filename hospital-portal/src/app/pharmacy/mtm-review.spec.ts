import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MtmReviewComponent } from './mtm-review';
import { MtmReviewResponse, PharmacyService } from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * QA D886: PHARMACIST_A moved a COMPLETED review back to DRAFT and saved a
 * follow-up dated 2020-01-01. The server now refuses both; the page stops
 * offering them and shows the server's reason when it refuses.
 */
describe('MtmReviewComponent (QA D886)', () => {
  let fixture: ComponentFixture<MtmReviewComponent>;
  let component: MtmReviewComponent;
  let svc: jasmine.SpyObj<PharmacyService>;
  let toast: jasmine.SpyObj<ToastService>;

  function review(id: string, status: MtmReviewResponse['status'], followUpDate?: string) {
    return {
      id,
      patientId: 'p-1',
      hospitalId: 'h-1',
      pharmacistUserId: 'u-1',
      reviewDate: '2026-10-20T09:00:00',
      adherenceConcern: true,
      polypharmacyAlert: false,
      status,
      followUpDate,
      createdAt: '2026-10-20T09:00:00',
      updatedAt: '2026-10-20T09:00:00',
    } as MtmReviewResponse;
  }

  beforeEach(() => {
    svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listMtmReviewsByHospital',
      'startMtmReview',
      'updateMtmReview',
    ]);
    svc.listMtmReviewsByHospital.and.returnValue(
      of({
        content: [
          review('r-done', 'COMPLETED', '2026-11-09'),
          review('r-ref', 'REFERRED', '2026-11-09'),
          review('r-draft', 'DRAFT', '2026-01-05'),
        ],
        totalElements: 3,
        totalPages: 1,
        size: 50,
        number: 0,
      }),
    );
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [MtmReviewComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: { effectiveHospitalIdForRequest: () => 'h-1' },
        },
      ],
    });
    fixture = TestBed.createComponent(MtmReviewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('offers no edit on a completed review, only on the open ones', () => {
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="mtm-edit-r-done"]')).toBeNull();
    expect(host.querySelector('[data-testid="mtm-closed-r-done"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="mtm-edit-r-ref"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="mtm-edit-r-draft"]')).not.toBeNull();

    component.openEdit(review('r-done', 'COMPLETED'));
    expect(component.showForm()).toBeFalse();
  });

  it('does not offer DRAFT for a referred review', () => {
    component.openEdit(review('r-ref', 'REFERRED', '2026-11-09'));
    fixture.detectChanges();
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('#mtmStatus option'),
    ).map((o) => (o as HTMLOptionElement).value);
    expect(options).toEqual(['COMPLETED', 'REFERRED']);
  });

  it('stops the follow-up picker at today, but keeps a stored date that has passed', () => {
    component.openCreate();
    const today = component.minFollowUp;
    expect(today).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    component.openEdit(review('r-draft', 'DRAFT', '2026-01-05'));
    expect(component.minFollowUp).toBe('2026-01-05');
  });

  it('shows the server reason when a save is refused', () => {
    svc.startMtmReview.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: 'La date de suivi ne peut pas être dans le passé.' },
      })),
    );
    component.openCreate();
    component.form.patientId = 'p-1';
    component.form.followUpDate = '2020-01-01';
    component.submit();

    expect(toast.error).toHaveBeenCalledWith('La date de suivi ne peut pas être dans le passé.');
  });
});
