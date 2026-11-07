import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { AdmissionsComponent } from './admissions';
import { AdmissionResponse, AdmissionService } from '../services/admission.service';
import { HospitalResponse, HospitalService } from '../services/hospital.service';
import { StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { DepartmentLookupService } from '../services/department-lookup.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';
import { AuthService } from '../auth/auth.service';
import { BedService } from '../services/bed.service';
import { roleContextStub } from '../testing/role-context.stub';

/**
 * QA D1122 (round 20, fix-induced by D1100): the "Libérer le lit" dialog was a
 * SIBLING of its fixed, z-index 1000 backdrop, so it sat in the page flow
 * under it and every mouse click landed on the backdrop. The D1100 spec called
 * `confirmUnassignBed()` directly and never saw it. This one does what a mouse
 * does: it asks the browser which element is under the centre of each button
 * (`elementFromPoint`, the hit test a real click uses) and clicks THAT element.
 */
describe('AdmissionsComponent "Libérer le lit" by mouse (QA D1122)', () => {
  const stay = {
    id: 'a1',
    hospitalId: 'h1',
    patientId: 'p1',
    patientName: 'Boukary Zongo',
    status: 'ACTIVE',
    roomBed: 'M1-04',
  } as AdmissionResponse;
  let admissions: jasmine.SpyObj<AdmissionService>;

  function mount() {
    admissions = jasmine.createSpyObj<AdmissionService>('AdmissionService', [
      'getAll',
      'unassignBed',
    ]);
    admissions.getAll.and.returnValue(of([stay]));
    admissions.unassignBed.and.returnValue(of(stay));
    const hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitals.list.and.returnValue(of([]));
    hospitals.getMyHospitalAsResponse.and.returnValue(of({ id: 'h1' } as HospitalResponse));
    const staff = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staff.list.and.returnValue(of([]));
    const departments = jasmine.createSpyObj<DepartmentLookupService>('DepartmentLookupService', [
      'getActiveDepartments',
    ]);
    departments.getActiveDepartments.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [AdmissionsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AdmissionService, useValue: admissions },
        { provide: HospitalService, useValue: hospitals },
        { provide: StaffService, useValue: staff },
        { provide: PatientService, useValue: jasmine.createSpyObj('PatientService', ['list']) },
        { provide: DepartmentLookupService, useValue: departments },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: AuthService, useValue: jasmine.createSpyObj('AuthService', ['getUserProfile']) },
        { provide: BedService, useValue: jasmine.createSpyObj('BedService', ['getAvailableBeds']) },
        { provide: HospitalScopeUrlService, useValue: { applyUrlScopeSync: () => undefined } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles: ['ROLE_NURSE'] }),
        },
      ],
    });
    const fixture = TestBed.createComponent(AdmissionsComponent);
    fixture.detectChanges();
    return fixture;
  }

  /** The element a mouse click at the centre of `el` would land on. */
  function hitAt(el: Element): Element | null {
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    return document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
  }

  it('the dialog is above its backdrop: the confirm button takes the click and frees the bed', () => {
    const fixture = mount();
    fixture.componentInstance.openUnassignBed(stay);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const dialog = host.querySelector('[aria-labelledby="admissions-unassign-bed-title"]');
    expect(dialog).withContext('the dialog is rendered').not.toBeNull();

    const reason = host.querySelector<HTMLTextAreaElement>('#admission-unassign-bed-reason')!;
    const reasonHit = hitAt(reason);
    expect(reasonHit === reason)
      .withContext(`the reason box takes the click, not ${reasonHit?.className}`)
      .toBeTrue();
    reason.value = 'Transfert en réanimation';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const confirm = dialog!.querySelector<HTMLButtonElement>('.btn-danger')!;
    const hit = hitAt(confirm);
    expect(hit !== null && confirm.contains(hit))
      .withContext(`a click on "Libérer le lit" lands on ${hit?.tagName}.${hit?.className}`)
      .toBeTrue();
    (hit as HTMLElement).click();
    expect(admissions.unassignBed).toHaveBeenCalledOnceWith('a1', 'Transfert en réanimation');
  });

  it('Annuler is clickable too, and a click inside the dialog does not close it', () => {
    const fixture = mount();
    fixture.componentInstance.openUnassignBed(stay);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const dialog = host.querySelector<HTMLElement>(
      '[aria-labelledby="admissions-unassign-bed-title"]',
    )!;
    (hitAt(dialog.querySelector('.modal-body')!) as HTMLElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.unassigningAdm())
      .withContext('a click on the dialog body is not a click on the backdrop')
      .not.toBeNull();

    const cancel = dialog.querySelector<HTMLButtonElement>('.btn-secondary')!;
    const hit = hitAt(cancel);
    expect(hit !== null && cancel.contains(hit))
      .withContext('Annuler takes the click')
      .toBeTrue();
    (hit as HTMLElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.unassigningAdm()).toBeNull();
    expect(admissions.unassignBed).not.toHaveBeenCalled();
  });
});
