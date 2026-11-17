import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { resetStoredLangForTests } from '../../shared/i18n/app-locale';
import fr from '../../../assets/i18n/fr.json';
import { PriceListComponent } from './price-list.component';
import { PriceListItem, PriceListLinkOptions } from '../../services/price-list.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { roleContextStub } from '../../testing/role-context.stub';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

const BASE = '/billing/price-list';

const CS_GEN: PriceListItem = {
  id: 'item-1',
  hospitalId: 'hosp-1',
  code: 'CS-GEN',
  label: 'Consultation générale (médecin généraliste)',
  category: 'CONSULTATION',
  linkType: 'SYSTEM',
  linkKey: 'CONSULTATION_DEFAULT',
  linkLabel: 'CONSULTATION_DEFAULT',
  active: true,
  currentPrice: {
    id: 'v1',
    unitPrice: 2000,
    effectiveFrom: '2026-12-01',
    status: 'IN_FORCE',
  },
  nextPrice: {
    id: 'v2',
    unitPrice: 2500,
    effectiveFrom: '2027-01-01',
    status: 'SCHEDULED',
  },
};

const CS_GEN_DETAIL: PriceListItem = {
  ...CS_GEN,
  history: [
    {
      id: 'v2',
      unitPrice: 2500,
      effectiveFrom: '2027-01-01',
      status: 'SCHEDULED',
      changeReason: 'Nouvelle convention',
      createdByName: 'Test Comptable 1',
      createdAt: '2026-12-02T09:00:00',
    },
    {
      id: 'v1',
      unitPrice: 2000,
      effectiveFrom: '2026-12-01',
      effectiveTo: '2026-12-31',
      status: 'IN_FORCE',
      changeReason: null,
      createdByName: 'Test Admin 1',
      createdAt: '2026-12-01T08:00:00',
    },
  ],
};

const OPTIONS: PriceListLinkOptions = {
  systemKeys: ['CONSULTATION_DEFAULT', 'BED_DAY', 'DELIVERY_VAGINAL', 'DELIVERY_CAESAREAN'],
  transfusionProducts: ['WHOLE_BLOOD', 'PACKED_RED_CELLS'],
  imagingModalities: ['XRAY', 'ULTRASOUND'],
  labTests: [{ id: 'lab-1', code: 'TWP-TDR-PALU', name: 'TDR paludisme' }],
  treatments: [],
};

describe('PriceListComponent (billing-money plan PR 2)', () => {
  let fixture: ComponentFixture<PriceListComponent>;
  let http: HttpTestingController;
  let previousLang: string | null;

  beforeEach(() => {
    previousLang = localStorage.getItem('lang');
    localStorage.setItem('lang', 'fr');
    resetStoredLangForTests();
  });

  function setup(roles: string[], superAdmin = false): HTMLElement {
    TestBed.configureTestingModule({
      imports: [PriceListComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin, hospitalId: 'hosp-1', roles }),
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'info', 'warning']),
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PriceListComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([CS_GEN]);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => {
    if (previousLang === null) localStorage.removeItem('lang');
    else localStorage.setItem('lang', previousLang);
    resetStoredLangForTests();
    http?.verify();
    (fixture?.nativeElement as HTMLElement | undefined)?.remove();
  });

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function openDetail(): void {
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-row-CS-GEN"] button')!.click();
    http.expectOne(`${BASE}/item-1`).flush(CS_GEN_DETAIL);
    fixture.detectChanges();
  }

  function type(id: string, value: string): void {
    const input = el().querySelector<HTMLInputElement | HTMLTextAreaElement>(`#${id}`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function choose(id: string, index: number): void {
    const select = el().querySelector<HTMLSelectElement>(`#${id}`)!;
    select.selectedIndex = index;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('shows the list in French with the price in force, the planned one and the label as typed', () => {
    const host = setup(['ROLE_BILLING_SPECIALIST']);
    expect(host.textContent).toContain('Grille tarifaire');
    expect(host.textContent).toContain('Prix unitaire (F CFA)');
    expect(host.textContent).toContain('En vigueur depuis');
    const row = host.querySelector('[data-testid="price-list-row-CS-GEN"]')!;
    expect(row.textContent).toContain('Consultation générale (médecin généraliste)');
    expect(row.textContent).toMatch(/2\s000/);
    expect(row.textContent).toMatch(/2\s500/);
    expect(row.textContent).toContain('Consultation par défaut');
  });

  it('opens the item with its price history: who, when, why and the status', () => {
    setup(['ROLE_RECEPTIONIST']);
    openDetail();
    const history = el().querySelector('[data-testid="price-list-history"]')!;
    expect(el().textContent).toContain('Historique des prix');
    const rows = history.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Prévu');
    expect(rows[0].textContent).toContain('Nouvelle convention');
    expect(rows[0].textContent).toContain('Test Comptable 1');
    expect(rows[1].textContent).toContain('En vigueur');
    expect(rows[1].textContent).toContain('Test Admin 1');
  });

  for (const role of ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT']) {
    it(`${role} is offered every write`, () => {
      setup([role]);
      expect(el().querySelector('[data-testid="price-list-new"]')).not.toBeNull();
      // PR 4: the CSV import opens its dialog; the export is there too.
      expect(el().querySelector('[data-testid="price-list-export"]')).not.toBeNull();
      el().querySelector<HTMLButtonElement>('[data-testid="price-list-import"]')!.click();
      fixture.detectChanges();
      expect(el().querySelector('[data-testid="price-list-import-dialog"]')).not.toBeNull();
      openDetail();
      expect(el().querySelector('[data-testid="price-list-add-price"]')).not.toBeNull();
      expect(el().querySelector('[data-testid="price-list-edit"]')).not.toBeNull();
      expect(el().querySelector('[data-testid="price-list-deactivate"]')).not.toBeNull();
    });
  }

  for (const role of [
    'ROLE_BILLING_SPECIALIST',
    'ROLE_RECEPTIONIST',
    'ROLE_CLAIMS_REVIEWER',
    'ROLE_SUPER_ADMIN',
  ]) {
    it(`${role} reads the list but is offered no write (owner decision D-1)`, () => {
      setup([role], role === 'ROLE_SUPER_ADMIN');
      expect(el().querySelector('[data-testid="price-list-table"]')).not.toBeNull();
      expect(el().querySelector('[data-testid="price-list-new"]')).toBeNull();
      // PR 4: whoever reads the list may export it; only the maintainers import.
      expect(el().querySelector('[data-testid="price-list-import"]')).toBeNull();
      expect(el().querySelector('[data-testid="price-list-export"]')).not.toBeNull();
      openDetail();
      expect(el().querySelector('[data-testid="price-list-add-price"]')).toBeNull();
      expect(el().querySelector('[data-testid="price-list-edit"]')).toBeNull();
      expect(el().querySelector('[data-testid="price-list-deactivate"]')).toBeNull();
    });
  }

  it('an empty new item is refused on every required field, and nothing is sent', async () => {
    setup(['ROLE_ACCOUNTANT']);
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-new"]')!.click();
    fixture.detectChanges();
    http.expectOne(`${BASE}/link-options`).flush(OPTIONS);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="price-list-item-save"]',
      notMarked: ['pl-effective-from'],
    });
    expect(marked).toEqual(
      jasmine.arrayContaining(['pl-code', 'pl-label', 'pl-category', 'pl-unit-price']),
    );
    http.expectNone((r) => r.method === 'POST');
  });

  it('the server refusal marks the fields it names with its own reason', async () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-new"]')!.click();
    fixture.detectChanges();
    http.expectOne(`${BASE}/link-options`).flush(OPTIONS);
    fixture.detectChanges();
    await fixture.whenStable();
    type('pl-code', 'CS-GEN');
    type('pl-label', 'Consultation générale');
    choose('pl-category', 1);
    type('pl-unit-price', '2000.5');
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-item-save"]')!.click();
    fixture.detectChanges();
    const post = http.expectOne((r) => r.method === 'POST' && r.url === BASE);
    expect(post.request.body.code).toBe('CS-GEN');
    expect(post.request.body.category).toBe('CONSULTATION');
    // Untouched, the date is left to the server's business day (Africa/Ouagadougou).
    expect(post.request.body.effectiveFrom).toBeNull();
    post.flush(
      fieldErrorsResponse({
        code: 'Le code CS-GEN existe déjà dans la grille tarifaire de l’établissement.',
        unitPrice: 'Saisissez le prix unitaire en francs CFA entiers, sans décimales.',
      }).error,
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expectFieldsMarked(fixture, {
      'pl-code': 'Le code CS-GEN existe déjà',
      'pl-unit-price': 'francs CFA entiers',
    });
  });

  it('links an imaging item by modality and folded study type', async () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-new"]')!.click();
    fixture.detectChanges();
    http.expectOne(`${BASE}/link-options`).flush(OPTIONS);
    fixture.detectChanges();
    await fixture.whenStable();
    type('pl-code', 'IMG-RX-THX');
    type('pl-label', 'Radiographie du thorax (face)');
    choose('pl-category', 3);
    choose('pl-link-type', 3); // IMAGING_STUDY
    await fixture.whenStable();
    fixture.detectChanges();
    choose('pl-link-modality', 1); // XRAY
    type('pl-link-key', 'radiographie thorax face');
    type('pl-unit-price', '7500');
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-item-save"]')!.click();
    const post = http.expectOne((r) => r.method === 'POST' && r.url === BASE);
    expect(post.request.body.linkType).toBe('IMAGING_STUDY');
    expect(post.request.body.linkKey).toBe('XRAY:radiographie thorax face');
    expect(post.request.body.unitPrice).toBe(7500);
    post.flush({ ...CS_GEN, id: 'item-2', code: 'IMG-RX-THX' });
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([CS_GEN]);
  });

  it('an empty new price is refused on the price and the reason', async () => {
    setup(['ROLE_ACCOUNTANT']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-add-price"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="price-list-price-save"]',
      notMarked: ['pp-effective-from'],
    });
    expect(marked).toEqual(jasmine.arrayContaining(['pp-unit-price', 'pp-change-reason']));
    http.expectNone((r) => r.method === 'POST');
  });

  it('asks before replacing a planned price, then sends the confirmation', async () => {
    setup(['ROLE_ACCOUNTANT']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-add-price"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    type('pp-unit-price', '3000');
    type('pp-change-reason', 'Correction de la convention');
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-price-save"]')!.click();
    const first = http.expectOne(`${BASE}/item-1/prices`);
    expect(first.request.body.replaceScheduled).toBeFalse();
    const question =
      'Un prix de 2 500 F CFA est déjà prévu à partir du 1 janv. 2027. Confirmez son remplacement pour enregistrer celui-ci.';
    first.flush(fieldErrorsResponse({ replaceScheduled: question }).error, {
      status: 400,
      statusText: 'Bad Request',
    });
    fixture.detectChanges();
    expect(
      el().querySelector('[data-testid="price-list-replace-question"]')?.textContent,
    ).toContain(question);
    expect(el().textContent).toContain('Remplacer le prix prévu');
    await fixture.whenStable();
    const box = el().querySelector<HTMLInputElement>('#pp-replace-scheduled')!;
    box.click();
    fixture.detectChanges();
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-price-save"]')!.click();
    const second = http.expectOne(`${BASE}/item-1/prices`);
    expect(second.request.body.replaceScheduled).toBeTrue();
    expect(second.request.body.unitPrice).toBe(3000);
    second.flush(CS_GEN_DETAIL);
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([CS_GEN]);
    fixture.detectChanges();
    expect(el().querySelector('#pp-unit-price')).toBeNull();
  });

  it('a date the user picks is sent, and picking another resets the replacement question', async () => {
    setup(['ROLE_ACCOUNTANT']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-add-price"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    type('pp-unit-price', '3000');
    type('pp-change-reason', 'Correction de la convention');
    type('pp-effective-from', '2027-01-01');
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-price-save"]')!.click();
    const first = http.expectOne(`${BASE}/item-1/prices`);
    expect(first.request.body.effectiveFrom).toBe('2027-01-01');
    first.flush(fieldErrorsResponse({ replaceScheduled: 'Un prix est déjà prévu.' }).error, {
      status: 400,
      statusText: 'Bad Request',
    });
    fixture.detectChanges();
    await fixture.whenStable();
    el().querySelector<HTMLInputElement>('#pp-replace-scheduled')!.click();
    fixture.detectChanges();
    type('pp-effective-from', '2027-02-01');
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="price-list-replace-question"]')).toBeNull();
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-price-save"]')!.click();
    const second = http.expectOne(`${BASE}/item-1/prices`);
    expect(second.request.body.effectiveFrom).toBe('2027-02-01');
    expect(second.request.body.replaceScheduled).toBeFalse();
    second.flush(CS_GEN_DETAIL);
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([CS_GEN]);
  });

  it('asks why before deactivating, sends nothing when cancelled, then sends the reason', () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="price-list-deactivate"]')!.click();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="reason-dialog"]')).not.toBeNull();
    el().querySelector<HTMLButtonElement>('[data-testid="reason-dialog-cancel"]')!.click();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="reason-dialog"]')).toBeNull();
    http.expectNone(`${BASE}/item-1/deactivate`);

    el().querySelector<HTMLButtonElement>('[data-testid="price-list-deactivate"]')!.click();
    fixture.detectChanges();
    const reason = el().querySelector<HTMLTextAreaElement>('[data-testid="reason-dialog-reason"]')!;
    reason.value = 'Acte retiré de la grille';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el().querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
    const post = http.expectOne(`${BASE}/item-1/deactivate`);
    expect(post.request.body).toEqual({ reason: 'Acte retiré de la grille' });
    post.flush({ ...CS_GEN_DETAIL, active: false });
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([]);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="reason-dialog"]')).toBeNull();
  });
});
