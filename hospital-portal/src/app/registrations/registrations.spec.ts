import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';
import { RegistrationsComponent } from './registrations';
import { RegistrationService, HospitalRegistration } from '../services/registration.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { roleContextStub, RoleContextStub } from '../testing/role-context.stub';

describe('RegistrationsComponent dashboard', () => {
  let service: jasmine.SpyObj<RegistrationService>;
  let role: RoleContextStub;
  const row = (overrides: Partial<HospitalRegistration> = {}): HospitalRegistration => ({
    id: 'cdx-1',
    patientId: 'cdx-p1',
    hospitalId: 'h2',
    patientFullName: 'Cdx Patient',
    mri: 'CDX-MRN-1',
    active: true,
    stayStatus: 'REGISTERED',
    ...overrides,
  });
  beforeEach(() => {
    service = jasmine.createSpyObj('RegistrationService', [
      'list',
      'create',
      'update',
      'delete',
      'multiHospital',
    ]);
    service.list.and.returnValue(of([row()]));
    service.multiHospital.and.returnValue(of([]));
    role = roleContextStub({ superAdmin: true, hospitalId: 'h1', roles: ['ROLE_SUPER_ADMIN'] });
    role.scopeToHospital('h2');
    TestBed.configureTestingModule({
      imports: [RegistrationsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: RegistrationService, useValue: service },
        { provide: RoleContextService, useValue: role },
        { provide: AuthService, useValue: { getHospitalId: () => 'h1' } },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    });
  });
  const make = () => TestBed.createComponent(RegistrationsComponent);

  it('uses the effective hospital and preserves super-admin read-only registration actions', () => {
    const fixture = make();
    fixture.detectChanges();
    expect(service.list).toHaveBeenCalledWith({ hospitalId: 'h2', active: true, size: 200 });
    expect(fixture.componentInstance.canManage).toBeFalse();
    expect(fixture.componentInstance.canSeeMultiHospital).toBeTrue();
    expect(fixture.nativeElement.querySelectorAll('.actions-cell button').length).toBe(0);
    expect(fixture.nativeElement.querySelectorAll('.summary-card').length).toBe(4);
  });

  it('distinguishes no matches from an empty register and restores rows when filters clear', () => {
    const fixture = make();
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.searchTerm.set('missing');
    fixture.detectChanges();
    const empty = fixture.nativeElement.querySelector('.empty-state');
    expect(empty.textContent).toContain('REGISTRATIONS.NO_MATCHES');
    expect(empty.textContent).not.toContain('REGISTRATIONS.NONE');
    empty.querySelector('button').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(1);
    service.list.and.returnValue(of([]));
    c.load();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty-state').textContent).toContain(
      'REGISTRATIONS.NONE',
    );
  });

  it('filters loaded stays and MRNs while metrics keep their loaded-set denominator', () => {
    const c = make().componentInstance;
    c.registrations.set([
      row(),
      row({ id: 'admitted', mri: 'CDX2', stayStatus: 'ADMITTED' }),
      row({ id: 'held', stayStatus: 'HOLD' }),
    ]);
    c.selectMetric('ADMITTED');
    c.searchTerm.set('cdx2');
    expect(c.filtered().map((r) => r.id)).toEqual(['admitted']);
    expect(c.metrics()[0].count).toBe(3);
    expect(service.list).not.toHaveBeenCalled();
    c.clearFilters();
    expect(c.filtered().length).toBe(3);
  });

  it('pages only the loaded set and clamps the final page after a deletion', () => {
    const c = make().componentInstance;
    c.registrations.set(Array.from({ length: 21 }, (_, i) => row({ id: `cdx-${i}` })));
    c.setPage(2);
    expect(c.pagedRows().length).toBe(1);
    c.registrations.update((rows) => rows.slice(0, 20));
    expect(c.currentPage()).toBe(1);
    expect(c.pagedRows().length).toBe(10);
    c.searchTerm.set('missing');
    c.filterChanged();
    expect(c.resultStart()).toBe(0);
    expect(c.resultEnd()).toBe(0);
  });

  it('ignores an old activity-filter response and clears a current load error', () => {
    const old = new Subject<HospitalRegistration[]>();
    const current = new Subject<HospitalRegistration[]>();
    service.list.and.returnValues(old, current);
    const c = make().componentInstance;
    c.ngOnInit();
    c.setActiveFilter('inactive');
    current.next([row({ id: 'inactive', active: false })]);
    old.next([row({ id: 'old' })]);
    old.error(new Error('stale'));
    expect(c.registrations().map((r) => r.id)).toEqual(['inactive']);
    expect(c.loadError()).toBeFalse();
    service.list.and.returnValue(throwError(() => new Error('offline')));
    c.load();
    expect(c.registrations()).toEqual([]);
    expect(c.loadError()).toBeTrue();
  });

  it('reports the 200-row boundary without inventing an all-server total', () => {
    service.list.and.returnValue(
      of(Array.from({ length: 200 }, (_, i) => row({ id: `cdx-${i}` }))),
    );
    const c = make().componentInstance;
    c.ngOnInit();
    expect(c.limitedResults()).toBeTrue();
    expect(c.totalPages()).toBe(20);
    c.activeFilter.set('all');
    c.load();
    expect(service.list).toHaveBeenCalledWith({ hospitalId: 'h2', active: undefined, size: 200 });
  });

  it('keeps receptionist write authority separate from multi-hospital visibility', () => {
    role.set({ superAdmin: false, hospitalId: 'h2', roles: ['ROLE_RECEPTIONIST'] });
    const c = make().componentInstance;
    expect(c.canManage).toBeTrue();
    expect(c.canSeeMultiHospital).toBeFalse();
    c.ngOnInit();
    service.create.and.returnValue(of(row()));
    c.openCreate();
    c.form.patientId = 'cdx-p1';
    c.submitForm();
    expect(service.create).toHaveBeenCalledWith(
      jasmine.objectContaining({ hospitalId: 'h2', patientId: 'cdx-p1' }),
    );
  });

  it('deactivates through the full replacement PUT and retains existing stay fields', () => {
    const c = make().componentInstance;
    const registration = row({
      currentRoom: 'Cdx Room',
      currentBed: 'Cdx Bed',
      attendingPhysicianName: 'Cdx Doctor',
      stayStatus: 'HOLD',
      readyForDischargeNote: 'Cdx note',
    });
    service.update.and.returnValue(of({ ...registration, active: false }));
    c.registrations.set([registration]);
    c.deactivate(registration);
    expect(service.update).toHaveBeenCalledWith(
      registration.id,
      jasmine.objectContaining({
        active: false,
        currentRoom: 'Cdx Room',
        currentBed: 'Cdx Bed',
        stayStatus: 'HOLD',
        readyForDischargeNote: 'Cdx note',
      }),
    );
    expect(service.delete).not.toHaveBeenCalled();
  });
});
