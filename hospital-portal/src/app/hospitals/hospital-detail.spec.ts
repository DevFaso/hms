import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { HospitalDetailComponent } from './hospital-detail';
import { HospitalLifecycleResponse } from '../services/hospital-lifecycle.model';
import { HospitalLifecycleService } from '../services/hospital-lifecycle.service';
import { HospitalResponse, HospitalService } from '../services/hospital.service';

const fakeHospital = (): HospitalResponse => ({
  id: 'h-1',
  name: 'Korle Bu',
  code: 'KB',
  address: '',
  city: 'Accra',
  state: '',
  zipCode: '',
  country: 'Ghana',
  province: '',
  region: '',
  sector: '',
  poBox: '',
  phoneNumber: '',
  email: '',
  website: '',
  organizationId: 'org-1',
  organizationName: 'Health Network',
  organizationCode: 'HN',
  active: true,
  lifecycleState: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-05-01T00:00:00Z',
});

const fakeLifecycle = (
  state: HospitalLifecycleResponse['state'] = 'ACTIVE',
): HospitalLifecycleResponse => ({
  hospitalId: 'h-1',
  hospitalName: 'Korle Bu',
  hospitalCode: 'KB',
  state,
  suspendedAt: null,
  suspendedBy: null,
  suspensionReason: null,
  archivedAt: null,
  archivedBy: null,
  purgeScheduledFor: null,
  purgedAt: null,
  updatedAt: '2026-05-01T00:00:00Z',
});

describe('HospitalDetailComponent (MVP-c2-frontend)', () => {
  let hospitalService: jasmine.SpyObj<HospitalService>;
  let lifecycleService: jasmine.SpyObj<HospitalLifecycleService>;

  function setup(): HospitalDetailComponent {
    TestBed.configureTestingModule({
      imports: [HospitalDetailComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: HospitalService, useValue: hospitalService },
        { provide: HospitalLifecycleService, useValue: lifecycleService },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => 'h-1' } } },
        },
      ],
    });
    const fixture = TestBed.createComponent(HospitalDetailComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    hospitalService = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'getById',
      'create',
      'update',
      'delete',
    ]);
    lifecycleService = jasmine.createSpyObj<HospitalLifecycleService>('HospitalLifecycleService', [
      'get',
      'suspend',
      'restore',
      'archive',
      'schedulePurge',
      'cancelPurge',
    ]);
  });

  it('loads the hospital and lifecycle on init', () => {
    hospitalService.getById.and.returnValue(of(fakeHospital()));
    lifecycleService.get.and.returnValue(of(fakeLifecycle()));

    const cmp = setup();

    expect(hospitalService.getById).toHaveBeenCalledWith('h-1');
    expect(lifecycleService.get).toHaveBeenCalledWith('h-1');
    expect(cmp.state()).toBe('ACTIVE');
    expect(cmp.canSuspend()).toBeTrue();
    expect(cmp.canRestore()).toBeFalse();
  });

  it('errors out when the hospital fetch fails', () => {
    hospitalService.getById.and.returnValue(throwError(() => new Error('boom')));
    lifecycleService.get.and.returnValue(of(fakeLifecycle()));

    const cmp = setup();
    expect(cmp.errored()).toBeTrue();
  });

  it('canRestore is true for SUSPENDED and ARCHIVED states', () => {
    hospitalService.getById.and.returnValue(of(fakeHospital()));
    lifecycleService.get.and.returnValue(of(fakeLifecycle('SUSPENDED')));

    const cmp = setup();
    expect(cmp.canRestore()).toBeTrue();
    expect(cmp.canArchive()).toBeTrue();
    expect(cmp.canSuspend()).toBeFalse();
  });

  it('submitDialog(suspend) requires reason ≥ 5 chars and posts when valid', () => {
    hospitalService.getById.and.returnValue(of(fakeHospital()));
    lifecycleService.get.and.returnValue(of(fakeLifecycle()));
    lifecycleService.suspend.and.returnValue(of(fakeLifecycle('SUSPENDED')));

    const cmp = setup();
    cmp.openDialog('suspend');
    cmp.patchDialog('reason', 'oops'); // too short
    expect(cmp.isReasonValid(cmp.dialog()!)).toBeFalse();

    cmp.patchDialog('reason', 'non-payment');
    expect(cmp.isReasonValid(cmp.dialog()!)).toBeTrue();
    cmp.submitDialog();

    expect(lifecycleService.suspend).toHaveBeenCalledWith('h-1', { reason: 'non-payment' });
    expect(cmp.state()).toBe('SUSPENDED');
    expect(cmp.dialog()).toBeNull();
  });

  it('submitDialog(restore) does not need a reason', () => {
    hospitalService.getById.and.returnValue(of(fakeHospital()));
    lifecycleService.get.and.returnValue(of(fakeLifecycle('SUSPENDED')));
    lifecycleService.restore.and.returnValue(of(fakeLifecycle('ACTIVE')));

    const cmp = setup();
    cmp.openDialog('restore');
    expect(cmp.needsReason('restore')).toBeFalse();
    cmp.submitDialog();

    expect(lifecycleService.restore).toHaveBeenCalledWith('h-1');
    expect(cmp.state()).toBe('ACTIVE');
  });

  it('submitDialog(schedule-purge) sends scheduledFor + reason', () => {
    hospitalService.getById.and.returnValue(of(fakeHospital()));
    lifecycleService.get.and.returnValue(of(fakeLifecycle('ARCHIVED')));
    lifecycleService.schedulePurge.and.returnValue(of(fakeLifecycle('PURGE_SCHEDULED')));

    const cmp = setup();
    cmp.openDialog('schedule-purge');
    cmp.patchDialog('reason', 'GDPR retention expiry');
    cmp.patchDialog('scheduledFor', '2026-06-01');
    cmp.submitDialog();

    expect(lifecycleService.schedulePurge).toHaveBeenCalledWith('h-1', {
      reason: 'GDPR retention expiry',
      scheduledFor: '2026-06-01',
    });
    expect(cmp.state()).toBe('PURGE_SCHEDULED');
  });

  it('submitDialog() surfaces an error key on failure and keeps the dialog open', () => {
    hospitalService.getById.and.returnValue(of(fakeHospital()));
    lifecycleService.get.and.returnValue(of(fakeLifecycle()));
    lifecycleService.suspend.and.returnValue(throwError(() => new Error('500')));

    const cmp = setup();
    cmp.openDialog('suspend');
    cmp.patchDialog('reason', 'audit testing');
    cmp.submitDialog();

    expect(cmp.dialog()?.errorKey).toBe('HOSPITAL_LIFECYCLE.ERROR.ACTION_FAILED');
    expect(cmp.dialog()?.busy).toBeFalse();
    // State unchanged.
    expect(cmp.state()).toBe('ACTIVE');
  });

  it('stateColor returns a sane fallback for null', () => {
    hospitalService.getById.and.returnValue(of(fakeHospital()));
    lifecycleService.get.and.returnValue(of(fakeLifecycle()));
    const cmp = setup();
    expect(cmp.stateColor(null)).toBe('#94a3b8');
    expect(cmp.stateColor('PURGED')).toBe('#1e293b');
  });
});
