import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MtmReviewComponent } from './mtm-review';
import { MtmReviewResponse, PharmacyService } from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { PatientResponse, PatientService } from '../services/patient.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { By } from '@angular/platform-browser';

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
  let patients: jasmine.SpyObj<PatientService>;

  function review(id: string, status: MtmReviewResponse['status'], followUpDate?: string) {
    return {
      id,
      patientId: 'p-1',
      patientName: 'Seydou Traoré',
      patientMrn: 'CHU0421',
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
    patients = jasmine.createSpyObj<PatientService>('PatientService', ['search', 'lookup']);
    patients.search.and.returnValue(of([]));
    patients.lookup.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [MtmReviewComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: ToastService, useValue: toast },
        { provide: PatientService, useValue: patients },
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

  /* ── QA D900: a patient picker, and the name and MRN in the list ── */

  it('QA D900: the list names each patient with the MRN, never the UUID', () => {
    const host = fixture.nativeElement as HTMLElement;
    const cell = host.querySelector('[data-testid="mtm-patient-r-draft"]');
    expect(cell?.textContent?.trim()).toBe('Seydou Traoré · CHU0421');
    expect(host.textContent).not.toContain('p-1');
  });

  it('QA D900: a review without a name falls back to the MRN, then a dash', () => {
    expect(component.patientLabel({ ...review('x', 'DRAFT'), patientName: undefined })).toBe(
      'CHU0421',
    );
    expect(
      component.patientLabel({
        ...review('x', 'DRAFT'),
        patientName: undefined,
        patientMrn: undefined,
      }),
    ).toBe('—');
  });

  it('QA D900: a new review picks the patient at the pinned hospital, with no UUID field', () => {
    svc.startMtmReview.and.returnValue(of(review('r-new', 'DRAFT')));
    component.openCreate();
    fixture.detectChanges();

    const picker = fixture.debugElement.query(By.directive(PatientPickerComponent));
    expect(picker).withContext('the patient is picked, not typed').not.toBeNull();
    expect((picker.componentInstance as PatientPickerComponent).hospitalId).toBe('h-1');
    expect(fixture.nativeElement.querySelector('input[name="patientId"]')).toBeNull();

    const seydou = { id: 'p-9', firstName: 'Seydou', lastName: 'Traoré' } as PatientResponse;
    (picker.componentInstance as PatientPickerComponent).selectedChange.emit(seydou);
    component.submit();

    expect(svc.startMtmReview).toHaveBeenCalledWith(
      jasmine.objectContaining({ patientId: 'p-9', hospitalId: 'h-1' }),
    );
  });

  it('QA D900: without a picked patient the review is not sent', () => {
    component.openCreate();
    fixture.detectChanges();
    component.submit();
    expect(svc.startMtmReview).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  it('QA D900: clearing the picked patient clears the review patient', () => {
    component.openCreate();
    component.onPatientPicked({ id: 'p-9', firstName: 'A', lastName: 'B' } as PatientResponse);
    expect(component.form.patientId).toBe('p-9');
    component.onPatientPicked(null);
    expect(component.form.patientId).toBe('');
  });

  it('QA D900: an edited review shows who it is for, not a patient field', () => {
    component.openEdit(review('r-draft', 'DRAFT', '2026-01-05'));
    fixture.detectChanges();
    const shown = fixture.nativeElement.querySelector('[data-testid="mtm-edit-patient"]');
    expect(shown?.textContent?.trim()).toBe('Seydou Traoré · CHU0421');
    expect(fixture.debugElement.query(By.directive(PatientPickerComponent))).toBeNull();
  });
});
