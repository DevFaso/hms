import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { AdtIntakeConfigComponent } from './adt-intake-config.component';
import { AdtIntakeConfig, AdtIntakeConfigService } from '../../services/adt-intake-config.service';
import { HospitalService } from '../../services/hospital.service';
import { ReferralService } from '../../services/referral.service';
import { StaffResponse, StaffService } from '../../services/staff.service';
import { ToastService } from '../../core/toast.service';

describe('AdtIntakeConfigComponent', () => {
  let fixture: ComponentFixture<AdtIntakeConfigComponent>;
  let svc: jasmine.SpyObj<AdtIntakeConfigService>;
  let toast: jasmine.SpyObj<ToastService>;
  let hospitals: jasmine.SpyObj<HospitalService>;
  let staff: jasmine.SpyObj<StaffService>;
  let referrals: jasmine.SpyObj<ReferralService>;

  const sample: AdtIntakeConfig = {
    id: 'cfg-1',
    hospitalId: 'h1',
    hospitalName: 'Test Hospital',
    admittingProviderId: 'p1',
    departmentId: 'd1',
    defaultAssignmentId: null,
    defaultAdmissionType: 'EMERGENCY',
    defaultAcuityLevel: 'LEVEL_2_MODERATE',
    defaultEncounterType: 'INPATIENT',
    defaultChiefComplaint: 'Auto-created from ADT^A01',
    enabled: true,
    createdAt: '2026-05-17T09:00:00Z',
    updatedAt: '2026-05-17T09:00:00Z',
  };

  beforeEach(async () => {
    svc = jasmine.createSpyObj<AdtIntakeConfigService>('AdtIntakeConfigService', [
      'list',
      'findByHospital',
      'upsert',
      'remove',
    ]);
    svc.list.and.returnValue(of([sample]));
    svc.upsert.and.returnValue(of(sample));
    svc.remove.and.returnValue(of(void 0));

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    // Row 24 follow-on: the component now consumes three services to
    // power the hospital typeahead + provider/department dropdowns.
    // Stub them all with empty defaults so tests focused on the list
    // / form happy paths still pass without exercising the typeahead.
    hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', ['searchHospitals']);
    hospitals.searchHospitals.and.returnValue(of([]));
    staff = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staff.list.and.returnValue(of([]));
    referrals = jasmine.createSpyObj<ReferralService>('ReferralService', [
      'getDepartmentsByHospital',
    ]);
    referrals.getDepartmentsByHospital.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [AdtIntakeConfigComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AdtIntakeConfigService, useValue: svc },
        { provide: ToastService, useValue: toast },
        { provide: HospitalService, useValue: hospitals },
        { provide: StaffService, useValue: staff },
        { provide: ReferralService, useValue: referrals },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdtIntakeConfigComponent);
  });

  it('lists existing intake configs on init', () => {
    fixture.detectChanges();
    expect(svc.list).toHaveBeenCalled();
    const rows = fixture.nativeElement.querySelectorAll('[data-config-id]');
    expect(rows.length).toBe(1);
    expect(rows[0].getAttribute('data-config-id')).toBe('cfg-1');
  });

  it('shows the empty state when no configs exist', () => {
    svc.list.and.returnValue(of([]));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="adt-intake-empty"]')).not.toBeNull();
  });

  it('shows the error state when list fails', () => {
    svc.list.and.returnValue(throwError(() => new Error('500')));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="adt-intake-error"]')).not.toBeNull();
  });

  it('rejects the save when hospital + provider are blank', () => {
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      save: (e: Event) => void;
    };
    component.save({ preventDefault: () => undefined } as unknown as Event);
    expect(svc.upsert).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  it('upserts and reloads on successful submit', () => {
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      form: Record<string, unknown>;
      save: (e: Event) => void;
    };
    component.form['hospitalId'] = 'h2';
    component.form['admittingProviderId'] = 'p2';
    component.save({ preventDefault: () => undefined } as unknown as Event);

    expect(svc.upsert).toHaveBeenCalledOnceWith(
      jasmine.objectContaining({
        hospitalId: 'h2',
        admittingProviderId: 'p2',
        enabled: false,
      }),
    );
    expect(toast.success).toHaveBeenCalled();
    // Reload triggered after save.
    expect(svc.list).toHaveBeenCalledTimes(2);
  });

  const member = (id: string, roleCode: string, assignmentId?: string): StaffResponse =>
    ({
      id,
      userId: 'u-' + id,
      username: id,
      name: 'Staff ' + id,
      email: '',
      hospitalId: 'h1',
      roleCode,
      assignmentId,
      active: true,
      createdAt: '',
    }) as StaffResponse;

  interface Exposed {
    form: Record<string, unknown>;
    staffOptions: () => { id: string; label: string; assignmentId: string | null }[];
    assignmentOptions: () => { assignmentId: string | null }[];
    selectHospital: (o: { id: string; label: string }) => void;
    onProviderChange: (id: string) => void;
    fieldError: (field: string) => string | null;
    save: (e: Event) => void;
  }

  it('QA D852: the admitting-provider picker offers the hospital physicians only', () => {
    staff.list.and.returnValue(
      of([
        member('doc', 'ROLE_DOCTOR', 'a-doc'),
        member('surg', 'ROLE_SURGEON', 'a-surg'),
        member('admin', 'ROLE_HOSPITAL_ADMIN', 'a-admin'),
        member('nurse', 'ROLE_NURSE', 'a-nurse'),
        member('desk', 'ROLE_RECEPTIONIST'),
      ]),
    );
    fixture.detectChanges();
    const c = fixture.componentInstance as unknown as Exposed;
    c.selectHospital({ id: 'h1', label: 'Hospital A' });

    expect(c.staffOptions().map((o) => o.id)).toEqual(['doc', 'surg']);
    // No raw ROLE_* code in what the picker shows.
    expect(c.staffOptions().every((o) => !o.label.includes('ROLE_'))).toBeTrue();
  });

  it('QA D872: the default assignment is picked from the physicians, and follows the provider', () => {
    staff.list.and.returnValue(
      of([
        member('doc', 'ROLE_DOCTOR', 'a-doc'),
        member('admin', 'ROLE_HOSPITAL_ADMIN', 'a-admin'),
      ]),
    );
    fixture.detectChanges();
    const c = fixture.componentInstance as unknown as Exposed;
    c.selectHospital({ id: 'h1', label: 'Hospital A' });

    expect(c.assignmentOptions().map((o) => o.assignmentId)).toEqual(['a-doc']);
    c.form['admittingProviderId'] = 'doc';
    c.onProviderChange('doc');
    expect(c.form['defaultAssignmentId']).toBe('a-doc');
  });

  it('QA D872: a blank complaint is sent as null so the server writes it in the admin language', () => {
    fixture.detectChanges();
    const c = fixture.componentInstance as unknown as Exposed;
    c.form['hospitalId'] = 'h2';
    c.form['admittingProviderId'] = 'p2';
    expect(c.form['defaultChiefComplaint']).toBe('');
    c.save({ preventDefault: () => undefined } as unknown as Event);

    expect(svc.upsert).toHaveBeenCalledOnceWith(
      jasmine.objectContaining({ defaultChiefComplaint: null }),
    );
  });

  it('QA D872: a refused save shows the server reason on the field it names', () => {
    const reason =
      'Seul un médecin ou un chirurgien de cet hôpital peut être le médecin admetteur.';
    svc.upsert.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: {
              message: reason,
              field: 'admittingProviderId',
              fieldErrors: { admittingProviderId: reason },
            },
          }),
      ),
    );
    fixture.detectChanges();
    const c = fixture.componentInstance as unknown as Exposed;
    c.form['hospitalId'] = 'h2';
    c.form['admittingProviderId'] = 'p2';
    c.save({ preventDefault: () => undefined } as unknown as Event);

    expect(c.fieldError('admittingProviderId')).toBe(reason);
    expect(toast.error).toHaveBeenCalledWith(reason);
  });
});
