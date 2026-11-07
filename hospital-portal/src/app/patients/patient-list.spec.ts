import { signal } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, Subject, of, throwError } from 'rxjs';
import { PatientListComponent } from './patient-list';
import { PatientService, PatientResponse } from '../services/patient.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

describe('PatientListComponent directory', () => {
  let patients: jasmine.SpyObj<PatientService>;
  let hospitals: jasmine.SpyObj<HospitalService>;
  let toast: jasmine.SpyObj<ToastService>;
  let permission: jasmine.Spy;
  let superAdmin: ReturnType<typeof signal<boolean>>;
  const row = (overrides: Partial<PatientResponse> = {}): PatientResponse => ({
    id: 'cdx-1',
    firstName: 'Cdx',
    lastName: 'Example',
    hospitalId: 'h1',
    active: true,
    ...overrides,
  });
  beforeEach(() => {
    patients = jasmine.createSpyObj('PatientService', ['list']);
    patients.list.and.returnValue(
      of([
        row(),
        row({
          id: 'cdx-2',
          lastName: 'Second',
          hospitalId: 'h2',
          active: false,
          email: 'cdx@example.test',
        }),
      ]),
    );
    hospitals = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospitals.list.and.returnValue(
      of([
        { id: 'h1', name: 'Cdx Hospital' },
        { id: 'h2', name: 'Cdx Clinic' },
      ] as HospitalResponse[]),
    );
    hospitals.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'Cdx Hospital' } as HospitalResponse),
    );
    toast = jasmine.createSpyObj('ToastService', ['error']);
    permission = jasmine.createSpy('hasPermission').and.returnValue(true);
    superAdmin = signal(true);
    TestBed.configureTestingModule({
      imports: [PatientListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: PatientService, useValue: patients },
        { provide: HospitalService, useValue: hospitals },
        { provide: PermissionService, useValue: { hasPermission: permission } },
        { provide: RoleContextService, useValue: { isSuperAdmin: superAdmin } },
        { provide: ToastService, useValue: toast },
      ],
    });
  });
  const make = () => TestBed.createComponent(PatientListComponent);

  it('renders four registration metrics, grouped contacts and the existing chart destination', () => {
    const fixture = make();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('.summary-card').length).toBe(4);
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
    expect(el.querySelector('.action-link')?.getAttribute('href')).toBe('/patients/cdx-1');
    expect(el.querySelector('.page-actions a')?.getAttribute('href')).toBe('/patients/new');
    expect(el.querySelector('.contact-secondary')?.textContent).toContain('cdx@example.test');
    expect(permission).toHaveBeenCalledWith('Register Patients');
  });

  it('keeps registration permission and the non-admin hospital lookup intact', () => {
    permission.and.returnValue(false);
    superAdmin.set(false);
    const fixture = make();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.page-actions a')).toBeNull();
    expect(hospitals.list).not.toHaveBeenCalled();
    expect(hospitals.getMyHospitalAsResponse).toHaveBeenCalledTimes(1);
    expect(patients.list).toHaveBeenCalledWith();
  });

  it('counts only the loaded hospital subset and preserves hospital selection on metric click', () => {
    const c = make().componentInstance;
    c.loadPatients();
    c.hospitalFilter = 'h2';
    c.onFilterChange();
    expect(c.directoryMetrics.find((m) => m.id === 'all')?.count).toBe(1);
    c.selectMetric('inactive');
    expect(c.hospitalFilter).toBe('h2');
    expect(c.filtered().map((p) => p.id)).toEqual(['cdx-2']);
    expect(c.patients().length).toBe(2);
    expect(patients.list).toHaveBeenCalledTimes(1);
  });

  it('marks all loaded records selected only without extra facets and resets them on metric click', () => {
    const fixture = make();
    fixture.detectChanges();
    const c = fixture.componentInstance;
    const all = (): HTMLButtonElement => fixture.nativeElement.querySelector('[data-metric="all"]');
    c.hospitalFilter = 'h1';
    c.onFilterChange();
    fixture.detectChanges();
    expect(all().getAttribute('aria-pressed')).toBe('true');
    for (const facet of ['searchTerm', 'genderFilter', 'bloodTypeFilter'] as const) {
      c[facet] = facet === 'searchTerm' ? 'Second' : facet === 'genderFilter' ? 'FEMALE' : 'O+';
      c.onFilterChange();
      fixture.detectChanges();
      expect(all().getAttribute('aria-pressed')).toBe('false');
      all().click();
      fixture.detectChanges();
      expect(c[facet]).toBe('');
      expect(c.hospitalFilter).toBe('h1');
      expect(c.filtered().map((p) => p.id)).toEqual(['cdx-1']);
      expect(all().getAttribute('aria-pressed')).toBe('true');
    }
  });

  it('treats either phone or email as contact, including phone-first and secondary-only records', () => {
    const c = make().componentInstance;
    c.patients.set([
      row({ id: 'none', email: '  ', phoneNumberPrimary: ' ' }),
      row({ id: 'primary', phoneNumberPrimary: '123' }),
      row({ id: 'secondary', phoneNumberSecondary: '456' }),
      row({ id: 'email', email: 'cdx@example.test' }),
    ]);
    c.selectMetric('no-contact');
    expect(c.filtered().map((p) => p.id)).toEqual(['none']);
    expect(c.directoryMetrics.find((m) => m.id === 'no-contact')?.count).toBe(1);
    expect(c.activeFilterCount).toBe(1);
    c.removeFilter('contact');
    expect(c.filtered().length).toBe(4);
  });

  it('searches full names, MRNs and secondary phones with combined facets', () => {
    const c = make().componentInstance;
    c.patients.set([
      row({
        middleName: 'Test',
        mrn: 'CDX123',
        phoneNumberSecondary: '778899',
        gender: 'FEMALE',
        bloodType: 'O+',
      }),
      row({ id: 'other' }),
    ]);
    c.genderFilter = 'FEMALE';
    c.bloodTypeFilter = 'O+';
    c.statusFilter = 'active';
    for (const term of ['Cdx Test Example', 'Cdx Example', 'Example Cdx', 'cdx123', '778899']) {
      c.searchTerm = term;
      c.applyFilter();
      expect(c.filtered().map((p) => p.id)).toEqual(['cdx-1']);
    }
  });

  it('displays a secondary phone when the primary is blank and suppresses blank contact lines', () => {
    patients.list.and.returnValue(
      of([
        row({ phoneNumberPrimary: ' ', phoneNumberSecondary: ' 456 ', email: ' ' }),
        row({ id: 'blank', phoneNumberPrimary: ' ', email: ' ' }),
      ]),
    );
    const fixture = make();
    fixture.detectChanges();
    const cells: NodeListOf<HTMLElement> = fixture.nativeElement.querySelectorAll('.contact-cell');
    expect(cells[0].querySelector('.contact-primary')?.textContent?.trim()).toBe('456');
    expect(cells[0].querySelector('.contact-secondary')).toBeNull();
    expect(cells[0].querySelector('.contact-missing')).toBeNull();
    expect(cells[1].querySelector('.contact-primary')).toBeNull();
    expect(cells[1].querySelector('.contact-secondary')).toBeNull();
    expect(cells[1].querySelector('.contact-missing')).not.toBeNull();
  });

  it('renders the retry state and disables metrics until a successful retry', () => {
    patients.list.and.returnValue(throwError(() => new Error('offline')));
    const fixture = make();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.load-error')?.getAttribute('role')).toBe('alert');
    expect(
      [...el.querySelectorAll<HTMLButtonElement>('.summary-card')].every((b) => b.disabled),
    ).toBeTrue();
    expect(toast.error).not.toHaveBeenCalled();
    const pending = new Subject<PatientResponse[]>();
    patients.list.and.returnValue(pending);
    el.querySelector<HTMLButtonElement>('.load-error button')!.click();
    fixture.detectChanges();
    expect(patients.list).toHaveBeenCalledTimes(2);
    expect(el.querySelector('.loading-state')).not.toBeNull();
    expect(
      [...el.querySelectorAll<HTMLButtonElement>('.summary-card')].every((b) => b.disabled),
    ).toBeTrue();
    pending.next([row()]);
    fixture.detectChanges();
    expect(el.querySelector('.load-error')).toBeNull();
    expect(
      [...el.querySelectorAll<HTMLButtonElement>('.summary-card')].every((b) => !b.disabled),
    ).toBeTrue();
  });

  it('cancels a superseded list request', () => {
    const pending = new Subject<PatientResponse[]>();
    const cancelled = jasmine.createSpy('cancelled');
    patients.list.and.returnValue(
      new Observable<PatientResponse[]>((subscriber) => {
        const subscription = pending.subscribe(subscriber);
        return () => {
          subscription.unsubscribe();
          cancelled();
        };
      }),
    );
    const c = make().componentInstance;
    c.loadPatients();
    patients.list.and.returnValue(of([row()]));
    c.loadPatients();
    expect(cancelled).toHaveBeenCalledTimes(1);
    expect(c.filtered().length).toBe(1);
  });

  it('reapplies a repeated search after clearing a metric without an extra request', fakeAsync(() => {
    const fixture = make();
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.onSearchChange('Second');
    tick(300);
    expect(c.filtered().length).toBe(1);
    c.selectMetric('all');
    expect(c.filtered().length).toBe(2);
    c.onSearchChange('Second');
    tick(300);
    expect(c.filtered().map((p) => p.id)).toEqual(['cdx-2']);
    expect(patients.list).toHaveBeenCalledTimes(1);
  }));

  it('clamps pagination after a smaller reload and resets pages when page size changes', () => {
    const c = make().componentInstance;
    c.patients.set(Array.from({ length: 45 }, (_, i) => row({ id: `cdx-${i}` })));
    c.applyFilter();
    c.goToPage(2);
    expect(c.pagedFiltered.length).toBe(5);
    c.loadPatients();
    expect(c.currentPage()).toBe(0);
    expect(c.pagedFiltered.length).toBe(2);
    c.changePageSize(10);
    expect(c.pageSize).toBe(10);
    c.changePageSize(0);
    expect(c.pageSize).toBe(10);
  });

  it('discards stale responses and errors and clears a current failed reload', () => {
    const c = make().componentInstance;
    const old = new Subject<PatientResponse[]>();
    const current = new Subject<PatientResponse[]>();
    patients.list.and.returnValues(old, current);
    c.loadPatients();
    c.loadPatients();
    current.next([row({ id: 'new' })]);
    old.next([row({ id: 'old' })]);
    old.error(new Error('stale'));
    expect(c.patients().map((p) => p.id)).toEqual(['new']);
    expect(c.loadError()).toBeFalse();
    expect(toast.error).not.toHaveBeenCalled();
    patients.list.and.returnValue(throwError(() => new Error('offline')));
    c.loadPatients();
    expect(c.patients()).toEqual([]);
    expect(c.filtered()).toEqual([]);
    expect(c.loadError()).toBeTrue();
    patients.list.and.returnValue(of([row()]));
    c.loadPatients();
    expect(c.loadError()).toBeFalse();
  });

  it('stops a pending list from updating the destroyed page', () => {
    const fixture = make();
    const pending = new Subject<PatientResponse[]>();
    patients.list.and.returnValue(pending);
    fixture.detectChanges();
    fixture.destroy();
    pending.next([row()]);
    expect(fixture.componentInstance.patients()).toEqual([]);
  });

  it('sorts without mutating the response and resets pagination', () => {
    const c = make().componentInstance;
    c.loadPatients();
    c.sort('name');
    expect(c.filtered().map((p) => p.id)).toEqual(['cdx-2', 'cdx-1']);
    expect(c.patients().map((p) => p.id)).toEqual(['cdx-1', 'cdx-2']);
    c.sort('mrn');
    expect(c.sortDir()).toBe('asc');
    expect(c.currentPage()).toBe(0);
  });
});
