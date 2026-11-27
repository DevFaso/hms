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
import { expandRoleEquivalents } from '../core/role-equivalence';
import { ToastService } from '../core/toast.service';
import { answerReasonDialog, reasonDialog } from '../shared/reason-dialog/reason-dialog.testing';

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
      'listCatalogVersions',
    ]);
    svc.listCatalogVersions.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 200, number: 0, last: true }),
    );
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
            // The real doctor equivalence (ROLE_PHYSICIAN and ROLE_SURGEON are doctors).
            hasAnyActiveRole: (wanted: string[]) =>
              expandRoleEquivalents(roles).some((r) => wanted.includes(r)),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(MedicationCatalogComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  for (const role of ['ROLE_PHYSICIAN', 'ROLE_SURGEON', 'ROLE_DOCTOR']) {
    it(`review of #1360: a médecin-chef holding ${role} sees the approval queue and history`, () => {
      const el = render([role]);
      expect(el.querySelector('.approvals-panel')).not.toBeNull();
    });
  }

  it('review of #1360: a nurse sees no approval panel', () => {
    const el = render(['ROLE_NURSE']);
    expect(el.querySelector('.approvals-panel')).toBeNull();
  });

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
    // Go-live D2 (V385): the caller no longer declares the limits approved; they state why.
    expect('pediatricDoseStatus' in component.dosingForm).toBeFalse();
    expect(component.dosingForm.changeReason).toBe('');
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
            hasAnyActiveRole: (wanted: string[]) =>
              expandRoleEquivalents(roles).some((r) => wanted.includes(r)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MedicationCatalogComponent);
    fixture.detectChanges();
    http.expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog').flush(page(rows));
    flushPendingCount(0);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  /** Go-live D2 (V385): the page reads the versions that await approval, every page, and counts them. */
  function flushPendingCount(waiting: number): void {
    const count = http.expectOne(
      (r) => r.method === 'GET' && r.url === '/medication-catalog/versions',
    );
    expect(count.request.params.get('status')).toBe('PENDING_APPROVAL');
    const content = Array.from({ length: waiting }, (_, i) => ({ id: `waiting-${i}` }));
    count.flush({
      content,
      totalElements: waiting,
      totalPages: 1,
      size: 200,
      number: 0,
      last: true,
    });
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
    flushPendingCount(0);
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
    flushPendingCount(0);
  });

  // ── Go-live D2 (V385): what is prescribed and dispensed changes through an approval ──

  it('sends a change of strength for approval with its reason, and says the entry keeps its values', () => {
    render(['ROLE_HOSPITAL_ADMIN'], [zidovudine]);
    const component = fixture.componentInstance;
    el.querySelector<HTMLButtonElement>('[data-test="catalog-edit"]')!.click();
    fixture.detectChanges();
    expect(component.needsApproval()).toBeFalse();
    expect(el.querySelector('[data-test="catalog-approval-hint"]')!.textContent).toContain(
      'PHARMACY.CATALOG_APPROVAL.EDIT_HINT_DIRECT',
    );

    component.form.strength = '300';
    fixture.detectChanges();
    expect(component.needsApproval()).toBeTrue();
    expect(el.querySelector('[data-test="catalog-approval-hint"]')!.textContent).toContain(
      'PHARMACY.CATALOG_APPROVAL.EDIT_HINT_PROPOSES',
    );

    // No reason: nothing is sent.
    component.submitForm();
    http.expectNone((r) => r.method === 'PUT');
    expect(toast.error).toHaveBeenCalledOnceWith(
      'PHARMACY.CATALOG_APPROVAL.CHANGE_REASON_REQUIRED',
    );

    component.form.changeReason = 'Nouveau dosage référencé';
    component.submitForm();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect(put.request.body.strength).toBe('300');
    expect(put.request.body.changeReason).toBe('Nouveau dosage référencé');
    put.flush({ ...zidovudine, pendingFormularyVersionId: 'v-1' });
    expect(toast.success).toHaveBeenCalledOnceWith(
      'PHARMACY.CATALOG_APPROVAL.PROPOSED_TOAST_UPDATE',
    );

    // The row still shows the entry in force, marked provisional with a version pending.
    http
      .expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog')
      .flush(page([{ ...zidovudine, pendingFormularyVersionId: 'v-1' }]));
    flushPendingCount(1);
    fixture.detectChanges();
    expect(el.querySelector('[data-test="catalog-formulary-status"]')!.textContent).toContain(
      'PHARMACY.CATALOG_APPROVAL.BADGE_PROVISIONAL',
    );
    expect(el.querySelector('[data-test="catalog-pending-version"]')).not.toBeNull();
    expect(el.querySelector('[data-test="catalog-approvals-count"]')).not.toBeNull();
  });

  it('proposes a new medicine with its reason: 202, a version awaiting approval, never "created"', () => {
    render(['ROLE_HOSPITAL_ADMIN'], []);
    const component = fixture.componentInstance;
    component.openCreate();
    component.form.nameFr = 'Amoxicilline 500 mg';
    component.form.genericName = 'amoxicilline';

    component.submitForm();
    http.expectNone((r) => r.method === 'POST');
    expect(toast.error).toHaveBeenCalledOnceWith(
      'PHARMACY.CATALOG_APPROVAL.CHANGE_REASON_REQUIRED',
    );

    component.form.changeReason = 'Ajout au formulaire';
    component.submitForm();
    const post = http.expectOne((r) => r.method === 'POST');
    expect(post.request.url).toBe('/medication-catalog');
    expect(post.request.body.changeReason).toBe('Ajout au formulaire');
    post.flush({ id: 'v-9', status: 'PENDING_APPROVAL' }, { status: 202, statusText: 'Accepted' });
    expect(toast.success).toHaveBeenCalledOnceWith(
      'PHARMACY.CATALOG_APPROVAL.PROPOSED_TOAST_CREATE',
    );
    http.expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog').flush(page([]));
    flushPendingCount(1);
  });

  it('proposes paediatric limits with a reason, and refuses a second proposal while one waits', () => {
    render(['ROLE_PHARMACIST'], [zidovudine]);
    const component = fixture.componentInstance;
    component.openDosing(zidovudine);
    component.dosingForm.pediatricMaxDoseMgPerKg = 8;

    component.saveDosing();
    http.expectNone((r) => r.method === 'PUT');
    expect(toast.error).toHaveBeenCalledOnceWith(
      'PHARMACY.CATALOG_APPROVAL.CHANGE_REASON_REQUIRED',
    );

    component.dosingForm.changeReason = 'Limites du protocole national';
    component.saveDosing();
    const put = http.expectOne((r) => r.method === 'PUT');
    expect(put.request.url).toBe('/medication-catalog/m-azt/pediatric-dosing');
    expect(put.request.body.changeReason).toBe('Limites du protocole national');
    expect('pediatricDoseStatus' in put.request.body).toBeFalse();
    put.flush({ ...zidovudine, pendingDoseLimitVersionId: 'v-2' });
    expect(toast.success).toHaveBeenCalledOnceWith(
      'PHARMACY.CATALOG_APPROVAL.PROPOSED_TOAST_DOSING',
    );
    const waiting = { ...zidovudine, pendingDoseLimitVersionId: 'v-2' };
    http
      .expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog')
      .flush(page([waiting]));
    flushPendingCount(1);

    component.openDosing(waiting);
    fixture.detectChanges();
    expect(el.querySelector('[data-test="dosing-pending-note"]')).not.toBeNull();
    // The button stays usable; a second proposal is the server's to refuse, and its reason is shown.
    component.dosingForm.changeReason = 'Autres limites';
    component.saveDosing();
    http
      .expectOne((r) => r.method === 'PUT')
      .flush(
        { message: 'Une version de cet article est déjà en attente de validation.' },
        { status: 400, statusText: 'Bad Request' },
      );
    expect(toast.error).toHaveBeenCalledWith(
      'Une version de cet article est déjà en attente de validation.',
    );
  });

  it('shows an approved entry its version, and limits one person set as provisional', () => {
    render(
      ['ROLE_PHARMACIST'],
      [
        {
          ...zidovudine,
          formularyApprovedVersion: 3,
          pediatricMaxDoseMgPerKg: 8,
          pediatricDoseStatus: 'APPROVED',
        },
      ],
    );
    expect(el.querySelector('[data-test="catalog-formulary-status"]')!.textContent).toContain(
      'PHARMACY.CATALOG_APPROVAL.BADGE_APPROVED',
    );
    expect(el.querySelector('[data-test="catalog-dosing-status"]')!.textContent).toContain(
      'PHARMACY.CATALOG_APPROVAL.DOSE_BADGE_PROVISIONAL',
    );
    expect(el.querySelector('[data-test="catalog-pending-version"]')).toBeNull();
  });

  it('submits an entry nobody approved yet as it stands, from its history, with a reason', () => {
    render(['ROLE_HOSPITAL_ADMIN'], [zidovudine]);
    el.querySelector<HTMLButtonElement>('[data-test="catalog-history"]')!.click();
    fixture.detectChanges();
    http
      .expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog/m-azt/versions')
      .flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-test="history-provisional-note"]')).not.toBeNull();
    expect(el.querySelector('[data-test="approvals-empty"]')).not.toBeNull();

    // The reason is asked by the shared dialog: nothing is sent until it is given.
    el.querySelector<HTMLButtonElement>('[data-test="history-submit-as-is"]')!.click();
    fixture.detectChanges();
    expect(reasonDialog(fixture)).not.toBeNull();
    http.expectNone((r) => r.method === 'POST');
    answerReasonDialog(fixture, 'Revue du formulaire à la mise en service');
    const post = http.expectOne((r) => r.method === 'POST');
    expect(post.request.url).toBe('/medication-catalog/m-azt/approval-request');
    expect(post.request.body).toEqual({ reason: 'Revue du formulaire à la mise en service' });
    post.flush({ ...zidovudine, pendingFormularyVersionId: 'v-3' });
    expect(toast.success).toHaveBeenCalledOnceWith('PHARMACY.CATALOG_APPROVAL.SUBMITTED_TOAST');

    http
      .expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog')
      .flush(page([{ ...zidovudine, pendingFormularyVersionId: 'v-3' }]));
    flushPendingCount(1);
    fixture.detectChanges();
    // The history is read again, and the entry is no longer offered for submission.
    http
      .expectOne((r) => r.method === 'GET' && r.url === '/medication-catalog/m-azt/versions')
      .flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-test="history-submit-as-is"]')).toBeNull();
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
      'listCatalogVersions',
    ]);
    svc.listCatalogVersions.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 200, number: 0, last: true }),
    );
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
        {
          provide: RoleContextService,
          useValue: { hasAnyActiveRoleExactly: () => true, hasAnyActiveRole: () => true },
        },
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
        {
          provide: RoleContextService,
          useValue: { hasAnyActiveRoleExactly: () => false, hasAnyActiveRole: () => false },
        },
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
    const svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'searchMedications',
      'listCatalogVersions',
    ]);
    svc.listCatalogVersions.and.returnValue(
      of({ content: [], totalElements: 0, totalPages: 0, size: 200, number: 0, last: true }),
    );
    svc.searchMedications.and.returnValues(old, latest);
    TestBed.configureTestingModule({
      imports: [MedicationCatalogComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        {
          provide: RoleContextService,
          useValue: { hasAnyActiveRoleExactly: () => true, hasAnyActiveRole: () => true },
        },
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
