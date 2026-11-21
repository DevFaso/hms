import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';
import { of, Subject, throwError } from 'rxjs';

import { MedicationCatalogComponent } from './medication-catalog';
import { PharmacyService, MedicationCatalogItemResponse } from '../services/pharmacy.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

const item = {
  id: 'm-1',
  nameFr: 'Amoxicilline',
  form: 'TABLET',
  active: true,
  // The hospital's own entry (a platform entry of the national list has none: r66 D2901).
  hospitalId: 'h-1',
} as MedicationCatalogItemResponse;

describe('MedicationCatalogComponent (QA D50)', () => {
  function render(roles: string[]): HTMLElement {
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listMedications',
      'searchMedications',
    ]);
    svc.listMedications.and.returnValue(
      of({ content: [item], totalElements: 1, totalPages: 1, size: 20, number: 0 }),
    );
    TestBed.configureTestingModule({
      imports: [MedicationCatalogComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRoleExactly: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(MedicationCatalogComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the pharmacist the catalog read-only: no create, edit or delete', () => {
    const el = render(['ROLE_PHARMACIST']);
    expect(el.querySelector('.page-header .btn-primary')).toBeNull();
    expect(el.querySelector('[data-test="catalog-edit"]')).toBeNull();
    expect(el.querySelector('[data-test="catalog-delete"]')).toBeNull();
  });

  it('shows the dosage form column through its translation key (QA D434)', () => {
    const el = render(['ROLE_PHARMACIST']);
    const cells = Array.from(el.querySelectorAll('tbody td')).map((td) => td.textContent?.trim());
    expect(cells).toContain('PHARMACY.FORM_OPTION.TABLET');
    expect(cells).not.toContain('TABLET');
  });

  it('keeps a legacy free-text form as typed, and a dash for none (QA D434)', () => {
    render(['ROLE_PHARMACIST']);
    const component = TestBed.createComponent(MedicationCatalogComponent).componentInstance;
    expect(component.formLabel('Comprimé sécable')).toBe('Comprimé sécable');
    expect(component.formLabel(null)).toBe('—');
  });

  it('QA D935 (round 18): the paediatric limits are carried into the dosing dialog, a new one pending approval', () => {
    render(['ROLE_PHARMACIST']);
    const component = TestBed.createComponent(MedicationCatalogComponent).componentInstance;
    component.openDosing({
      id: 'cat-1',
      nameFr: 'Paracétamol sirop',
      genericName: 'paracetamol',
      essentialList: true,
      controlled: false,
      active: true,
      pediatricMaxDoseMgPerKg: 15,
      pediatricMaxDailyMg: 4000,
      pediatricDoseSource: 'WHO Pocket Book 2013',
    } as never);
    expect(component.dosingForm.pediatricMaxDoseMgPerKg).toBe(15);
    expect(component.dosingForm.pediatricMaxDailyMg).toBe(4000);
    expect(component.dosingForm.pediatricDoseStatus).toBe('DEFAULT_PENDING_APPROVAL');
    component.addBand();
    expect(component.dosingForm.pediatricWeightBands.length).toBe(1);
    component.removeBand(0);
    expect(component.dosingForm.pediatricWeightBands.length).toBe(0);
  });

  it('QA D935 (round 18): the pharmacist may set the paediatric limits, not edit the item', () => {
    const el = render(['ROLE_PHARMACIST']);
    expect(el.querySelector('[data-test="catalog-dosing"]')).not.toBeNull();
    expect(el.querySelector('[data-test="catalog-edit"]')).toBeNull();
  });

  it('QA D935 (round 18): a doctor reads the catalogue with no dosing action', () => {
    const el = render(['ROLE_DOCTOR']);
    expect(el.querySelector('[data-test="catalog-dosing"]')).toBeNull();
  });

  it('offers the hospital admin create, edit and delete', () => {
    const el = render(['ROLE_HOSPITAL_ADMIN']);
    expect(el.querySelector('.page-header .btn-primary')).not.toBeNull();
    expect(el.querySelector('[data-test="catalog-edit"]')).not.toBeNull();
    expect(el.querySelector('[data-test="catalog-delete"]')).not.toBeNull();
  });
});

/**
 * r66 D2901: every save of the catalogue editor answered 404 for seven months,
 * so a hospital admin could not tag an antiretroviral as sensitive. The editor
 * names no hospital on its writes: the server acts at the caller's hospital
 * (MedicationCatalogItemServiceImpl.requireWritable, WriteHospitalContextGuardTest).
 * This pins the editor's side of that contract, over the real HTTP calls.
 */
describe('MedicationCatalogComponent: save, tag and deactivate (r66 D2901)', () => {
  let http: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;
  let fixture: ComponentFixture<MedicationCatalogComponent>;
  let el: HTMLElement;

  const zidovudine = {
    id: 'm-azt',
    nameFr: 'Zidovudine 300 mg',
    genericName: 'zidovudine',
    form: 'TABLET',
    essentialList: true,
    controlled: false,
    active: true,
    hospitalId: 'h-1',
  } as MedicationCatalogItemResponse;
  const national = {
    ...zidovudine,
    id: 'm-lnme',
    nameFr: 'Paracétamol 500 mg',
    hospitalId: undefined,
  };

  const page = (content: MedicationCatalogItemResponse[]) => ({
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 20,
    number: 0,
  });

  function render(roles: string[], rows: MedicationCatalogItemResponse[]): void {
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [MedicationCatalogComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRoleExactly: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MedicationCatalogComponent);
    fixture.detectChanges();
    http.expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog').flush(page(rows));
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('saves an edit and its sensitive tag with no hospital in the request, then reads the tag back', () => {
    render(['ROLE_HOSPITAL_ADMIN'], [zidovudine]);
    const component = fixture.componentInstance;
    el.querySelector<HTMLButtonElement>('[data-test="catalog-edit"]')!.click();
    fixture.detectChanges();
    component.medSensitivity.set('HIV');
    component.submitForm();

    const put = http.expectOne((r) => r.method === 'PUT');
    expect(put.request.url).toBe('/medication-catalog/m-azt');
    expect(put.request.params.keys()).toEqual([]);
    expect('hospitalId' in put.request.body).toBeFalse();
    expect(put.request.body.sensitivityCategory).toBe('HIV');
    expect(put.request.body.nameFr).toBe('Zidovudine 300 mg');
    put.flush({ ...zidovudine, sensitivityCategory: 'HIV' });

    expect(toast.success).toHaveBeenCalledOnceWith('PHARMACY.MED_UPDATED');
    expect(toast.error).not.toHaveBeenCalled();
    // The list is read again, and the row and the editor show the tag the server kept.
    http
      .expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog')
      .flush(page([{ ...zidovudine, sensitivityCategory: 'HIV' }]));
    fixture.detectChanges();
    expect(component.filtered()[0].sensitivityCategory).toBe('HIV');
    el.querySelector<HTMLButtonElement>('[data-test="catalog-edit"]')!.click();
    fixture.detectChanges();
    expect(component.medSensitivity()).toBe('HIV');

    // Saved again untouched: the tag is neither sent nor cleared (the server keeps it).
    component.submitForm();
    const again = http.expectOne((r) => r.method === 'PUT');
    expect('sensitivityCategory' in again.request.body).toBeFalse();
    expect('clearSensitivityCategory' in again.request.body).toBeFalse();
    again.flush({ ...zidovudine, sensitivityCategory: 'HIV' });
    http.expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog').flush(page([]));
  });

  it('deactivates an entry with no hospital in the request', () => {
    render(['ROLE_HOSPITAL_ADMIN'], [zidovudine]);
    el.querySelector<HTMLButtonElement>('[data-test="catalog-delete"]')!.click();
    fixture.detectChanges();
    fixture.componentInstance.executeDelete();

    const del = http.expectOne((r) => r.method === 'DELETE');
    expect(del.request.url).toBe('/medication-catalog/m-azt');
    expect(del.request.params.keys()).toEqual([]);
    del.flush(null);
    expect(toast.success).toHaveBeenCalledOnceWith('PHARMACY.MED_DELETED');
    http.expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog').flush(page([]));
  });

  it('offers a hospital admin no edit or delete on a platform (national list) entry, and the platform admin both', () => {
    render(['ROLE_HOSPITAL_ADMIN'], [national, zidovudine]);
    // One row of two is the hospital's own.
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
    expect(el.querySelectorAll('[data-test="catalog-edit"]').length).toBe(1);
    expect(el.querySelectorAll('[data-test="catalog-delete"]').length).toBe(1);
    expect(fixture.componentInstance.canManageItem(national)).toBeFalse();
    expect(fixture.componentInstance.canManageItem(zidovudine)).toBeTrue();

    TestBed.resetTestingModule();
    render(['ROLE_SUPER_ADMIN'], [national, zidovudine]);
    expect(el.querySelectorAll('[data-test="catalog-edit"]').length).toBe(2);
    expect(el.querySelectorAll('[data-test="catalog-delete"]').length).toBe(2);
  });
});

describe('MedicationCatalogComponent ATC format (QA D129)', () => {
  it('accepts WHO ATC codes at levels 3, 4 and 5, and rejects malformed ones', () => {
    const ok = ['J01', 'J01C', 'J01CA', 'J01CA04', 'n02be01'];
    const bad = ['J', 'J0', 'J01CA0', 'J01CA045', '101CA04', 'J01C4'];
    for (const code of ok) {
      expect(MedicationCatalogComponent.ATC_PATTERN.test(code.toUpperCase()))
        .withContext(code)
        .toBeTrue();
    }
    for (const code of bad) {
      expect(MedicationCatalogComponent.ATC_PATTERN.test(code)).withContext(code).toBeFalse();
    }
  });
});

/** QA D528/D663 replay: HOSPITAL_ADMIN_A opens /medication-catalog in FR, then ES; the category read ANALGESIC. */
describe('MedicationCatalogComponent — the category column is labelled (QA D528/D663)', () => {
  function renderIn(lang: 'fr' | 'es'): string[] {
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listMedications',
      'searchMedications',
    ]);
    svc.listMedications.and.returnValue(
      of({
        content: [{ ...item, category: 'ANALGESIC' } as MedicationCatalogItemResponse],
        totalElements: 1,
        totalPages: 1,
        size: 20,
        number: 0,
      }),
    );
    TestBed.configureTestingModule({
      imports: [MedicationCatalogComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: RoleContextService, useValue: { hasAnyActiveRoleExactly: () => true } },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.setTranslation('es', es);
    translate.use(lang);
    const fixture = TestBed.createComponent(MedicationCatalogComponent);
    fixture.detectChanges();
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tbody td')).map(
      (td) => td.textContent?.trim() ?? '',
    );
  }

  it('shows Analgésique in French, never the token', () => {
    const cells = renderIn('fr');
    expect(cells).toContain('Analgésique');
    expect(cells).not.toContain('ANALGESIC');
  });

  it('shows Analgésico in Spanish, never the token', () => {
    const cells = renderIn('es');
    expect(cells).toContain('Analgésico');
    expect(cells).not.toContain('ANALGESIC');
  });
});

describe('Medication catalogue search ordering', () => {
  it('announces a failed list inline once and recovers through Retry', () => {
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', ['listMedications']);
    svc.listMedications.and.returnValues(
      throwError(() => new Error('offline')),
      of({ content: [item], totalElements: 1, totalPages: 1, size: 20, number: 0 }),
    );
    const toast = jasmine.createSpyObj<ToastService>('ToastService', ['error']);
    TestBed.configureTestingModule({
      imports: [MedicationCatalogComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: { hasAnyActiveRoleExactly: () => false } },
      ],
    });
    const fixture = TestBed.createComponent(MedicationCatalogComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('[role="alert"]').length).toBe(1);
    expect(el.querySelector('table')).toBeNull();
    expect(el.querySelector('.empty-state')).toBeNull();
    expect(toast.error).not.toHaveBeenCalled();
    el.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    fixture.detectChanges();
    expect(svc.listMedications).toHaveBeenCalledTimes(2);
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(el.querySelector('table')).not.toBeNull();
  });

  it('keeps the latest search when the earlier request finishes last', () => {
    const old = new Subject<any>();
    const latest = new Subject<any>();
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', ['searchMedications']);
    svc.searchMedications.and.returnValues(old, latest);
    TestBed.configureTestingModule({
      imports: [MedicationCatalogComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: RoleContextService, useValue: { hasAnyActiveRoleExactly: () => true } },
      ],
    });
    const c = TestBed.createComponent(MedicationCatalogComponent).componentInstance;
    c.searchTerm = 'old';
    c.onSearch();
    c.searchTerm = 'latest';
    c.onSearch();
    latest.next({
      content: [{ ...item, id: 'latest', essentialList: true }],
      totalElements: 1,
      totalPages: 1,
    });
    old.next({ content: [{ ...item, id: 'old' }], totalElements: 5, totalPages: 1 });
    expect(c.filtered()[0].id).toBe('latest');
    expect(c.totalElements).toBe(1);
    expect(c.pageCount('essentialList')).toBe(1);
  });
});
