import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

import { RegistrationsComponent } from './registrations';
import { RegistrationService, HospitalRegistration } from '../services/registration.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { ALL_PAGES_SIZE } from '../shared/all-pages';
import { roleContextStub, RoleContextStub } from '../testing/role-context.stub';

/**
 * RCPT-CLIN: the desk neither reads the discharge note nor writes it, and the server keeps
 * the stored one. A box left on the desk's form would drop what they type without a word.
 */
describe('RegistrationsComponent discharge note', () => {
  function openFormAs(roles: string[]): HTMLElement {
    TestBed.configureTestingModule({
      imports: [RegistrationsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles }),
        },
      ],
    });
    const fixture = TestBed.createComponent(RegistrationsComponent);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .match(() => true)
      .forEach((call) => call.flush([]));
    fixture.componentInstance.showFormModal.set(true);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('offers no discharge note to a receptionist', () => {
    const page = openFormAs(['ROLE_RECEPTIONIST']);
    expect(page.querySelector('#reg-physician')).withContext('the form is open').not.toBeNull();
    expect(page.querySelector('#reg-note')).toBeNull();
  });

  it('keeps it for a hospital administrator', () => {
    expect(openFormAs(['ROLE_HOSPITAL_ADMIN']).querySelector('#reg-note')).not.toBeNull();
  });
});

/** QA D1430: one click deactivated a patient's registration. */
describe('RegistrationsComponent deactivation', () => {
  const row = {
    id: 'r1',
    patientId: 'p1',
    hospitalId: 'h1',
    patientFullName: 'Awa Traoré',
    active: true,
    stayStatus: 'REGISTERED',
  };

  function page() {
    TestBed.configureTestingModule({
      imports: [RegistrationsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h1',
            roles: ['ROLE_RECEPTIONIST'],
          }),
        },
      ],
    });
    const fixture = TestBed.createComponent(RegistrationsComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.match(() => true).forEach((call) => call.flush([]));
    fixture.componentInstance.registrations.set([row] as never);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('the row button only opens the question: nothing is sent', () => {
    const { fixture, http, el } = page();
    (el.querySelector('[data-testid="registration-deactivate"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(el.querySelector('[data-testid="reason-dialog"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="registration-deactivate-who"]')?.textContent).toContain(
      'Awa Traoré',
    );
    http.expectNone((r) => r.method === 'PUT');
  });

  it('the reason goes with the PUT; the row shows it once saved', () => {
    const { fixture, http, el } = page();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', { COMMON: { REASON_VALUE: 'Motif : {{reason}}' } });
    translate.use('fr');
    fixture.componentInstance.deactivate(row as never, 'Parti vivre ailleurs');
    const put = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/r1'));
    expect(put.request.body.active).toBeFalse();
    expect(put.request.body.deactivationReason).toBe('Parti vivre ailleurs');
    put.flush({ ...row, active: false, deactivationReason: 'Parti vivre ailleurs' });
    fixture.detectChanges();

    expect(
      el.querySelector('[data-testid="registration-deactivation-reason"]')?.textContent,
    ).toContain('Parti vivre ailleurs');
    expect(fixture.componentInstance.deactivating()).toBeNull();
  });

  it('the box in the edit form asks too: refused on the field until there is a reason', async () => {
    const { fixture, http, el } = page();
    const c = fixture.componentInstance;
    c.openEdit(row as never);
    fixture.detectChanges();
    expect(el.querySelector('#reg-deactivation-reason')).toBeNull();

    await fixture.whenStable();
    (el.querySelector('.checkbox-field input') as HTMLInputElement).click();
    fixture.detectChanges();
    const box = el.querySelector('#reg-deactivation-reason') as HTMLElement;
    expect(box).not.toBeNull();
    c.submitForm();
    http.expectNone((r) => r.method === 'PUT');
    expect(box.getAttribute('aria-invalid')).toBe('true');

    c.form.deactivationReason = 'Doublon';
    c.submitForm();
    expect(box.getAttribute('aria-invalid')).toBeNull();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect(put.request.body.deactivationReason).toBe('Doublon');
  });
});

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
    // QA D1347: every page of the register is read, from page 0.
    expect(service.list).toHaveBeenCalledWith({
      hospitalId: 'h2',
      active: true,
      page: 0,
      size: ALL_PAGES_SIZE,
    });
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
    current.complete();
    old.next([row({ id: 'old' })]);
    old.error(new Error('stale'));
    expect(c.registrations().map((r) => r.id)).toEqual(['inactive']);
    expect(c.loadError()).toBeFalse();
    service.list.and.returnValue(throwError(() => new Error('offline')));
    c.load();
    expect(c.registrations()).toEqual([]);
    expect(c.loadError()).toBeTrue();
  });

  it('QA D1347: reads past the first 200 rows instead of stopping at the page boundary', () => {
    service.list.and.returnValues(
      of(Array.from({ length: ALL_PAGES_SIZE }, (_, i) => row({ id: `cdx-${i}` }))),
      of(Array.from({ length: 5 }, (_, i) => row({ id: `cdx-next-${i}` }))),
    );
    const c = make().componentInstance;
    c.ngOnInit();
    expect(c.registrations().length).toBe(ALL_PAGES_SIZE + 5);
    expect(c.totalPages()).toBe(21);
    expect(service.list).toHaveBeenCalledWith({
      hospitalId: 'h2',
      active: true,
      page: 1,
      size: ALL_PAGES_SIZE,
    });
    service.list.and.returnValue(of([]));
    c.activeFilter.set('all');
    c.load();
    expect(service.list).toHaveBeenCalledWith({
      hospitalId: 'h2',
      active: undefined,
      page: 0,
      size: ALL_PAGES_SIZE,
    });
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
    c.deactivate(registration, 'Cdx reason');
    expect(service.update).toHaveBeenCalledWith(
      registration.id,
      jasmine.objectContaining({
        active: false,
        deactivationReason: 'Cdx reason',
        currentRoom: 'Cdx Room',
        currentBed: 'Cdx Bed',
        stayStatus: 'HOLD',
        readyForDischargeNote: 'Cdx note',
      }),
    );
    expect(service.delete).not.toHaveBeenCalled();
  });
});
