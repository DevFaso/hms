import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { resetStoredLangForTests } from '../../shared/i18n/app-locale';
import fr from '../../../assets/i18n/fr.json';
import { PayersComponent } from './payers.component';
import { Payer, linkablePlanOptions } from '../../services/payer-catalogue.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { roleContextStub } from '../../testing/role-context.stub';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

const BASE = '/billing/payers';

/** The test clinic's fictional payer (docs/plan/test-hospital/payeurs-test.csv): never a real insurer. */
const MUT_A: Payer = {
  id: 'payer-a',
  hospitalId: 'hosp-1',
  code: 'MUT-TEST-A',
  name: 'Mutuelle Test A (FICTIVE)',
  payerType: 'MUTUELLE',
  active: true,
  plans: [
    {
      id: 'plan-a',
      payerId: 'payer-a',
      code: 'MTA-ESS',
      name: 'Formule Test Essentielle',
      defaultCoveragePercent: 75,
      perInvoiceCeiling: 30000,
      annualCeilingPerBeneficiary: 150000,
      effectiveFrom: '2026-12-01',
      effectiveTo: null,
      status: 'SCHEDULED',
      linkable: true,
      rules: [
        {
          id: 'rule-1',
          planId: 'plan-a',
          category: 'IMAGING',
          coveragePercent: 50,
          excluded: false,
          effectiveFrom: '2026-12-01',
          status: 'SCHEDULED',
        },
        {
          id: 'rule-2',
          planId: 'plan-a',
          priceListItemId: 'item-widal',
          priceListItemCode: 'LAB-WIDAL',
          priceListItemLabel: 'Sérodiagnostic de Widal',
          excluded: true,
          effectiveFrom: '2026-12-01',
          status: 'SCHEDULED',
        },
      ],
    },
  ],
};

describe('PayersComponent (billing-money plan PR 6)', () => {
  let fixture: ComponentFixture<PayersComponent>;
  let http: HttpTestingController;
  let previousLang: string | null;

  beforeEach(() => {
    previousLang = localStorage.getItem('lang');
    localStorage.setItem('lang', 'fr');
    resetStoredLangForTests();
  });

  function setup(roles: string[], superAdmin = false): HTMLElement {
    TestBed.configureTestingModule({
      imports: [PayersComponent, TranslateModule.forRoot()],
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
    fixture = TestBed.createComponent(PayersComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([MUT_A]);
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
    el().querySelector<HTMLButtonElement>('[data-testid="payers-row-MUT-TEST-A"] button')!.click();
    http.expectOne(`${BASE}/payer-a`).flush(MUT_A);
    fixture.detectChanges();
  }

  function type(id: string, value: string): void {
    const input = el().querySelector<HTMLInputElement>(`#${id}`)!;
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

  it('shows the payers in French, the name as typed and the type translated', () => {
    const host = setup(['ROLE_BILLING_SPECIALIST']);
    expect(host.textContent).toContain('Organismes payeurs');
    const row = host.querySelector('[data-testid="payers-row-MUT-TEST-A"]')!;
    expect(row.textContent).toContain('Mutuelle Test A (FICTIVE)');
    expect(row.textContent).toContain('Mutuelle');
    expect(row.textContent).toContain('Formule Test Essentielle (75 %)');
  });

  it('opens a payer with its plans, ceilings and rules in French', () => {
    setup(['ROLE_RECEPTIONIST']);
    openDetail();
    const plan = el().querySelector('[data-testid="payers-plan-MTA-ESS"]')!;
    expect(plan.textContent).toContain('Taux de prise en charge (%)');
    expect(plan.textContent).toContain('Plafond par facture (F CFA)');
    expect(plan.textContent).toContain('Plafond annuel par bénéficiaire (F CFA)');
    expect(plan.textContent).toMatch(/30\s000/);
    expect(plan.textContent).toMatch(/150\s000/);
    expect(plan.textContent).toContain('Prévue');
    const rules = el().querySelectorAll('[data-testid="payers-rules-MTA-ESS"] tbody tr');
    expect(rules.length).toBe(2);
    expect(rules[0].textContent).toContain('Imagerie');
    expect(rules[0].textContent).toContain('50 %');
    expect(rules[1].textContent).toContain('LAB-WIDAL — Sérodiagnostic de Widal');
    expect(rules[1].textContent).toContain('Exclu');
  });

  for (const role of ['ROLE_HOSPITAL_ADMIN', 'ROLE_ACCOUNTANT']) {
    it(`${role} is offered every write`, () => {
      setup([role]);
      expect(el().querySelector('[data-testid="payers-new"]')).not.toBeNull();
      openDetail();
      expect(el().querySelector('[data-testid="payers-new-plan"]')).not.toBeNull();
      expect(el().querySelector('[data-testid="payers-edit"]')).not.toBeNull();
      expect(el().querySelector('[data-testid="payers-deactivate"]')).not.toBeNull();
      expect(el().querySelector('[data-testid="payers-new-rule-MTA-ESS"]')).not.toBeNull();
      expect(el().querySelector('[data-testid="payers-end-rule-rule-1"]')).not.toBeNull();
    });
  }

  for (const role of [
    'ROLE_BILLING_SPECIALIST',
    'ROLE_RECEPTIONIST',
    'ROLE_CLAIMS_REVIEWER',
    'ROLE_SUPER_ADMIN',
  ]) {
    it(`${role} reads the catalogue but is offered no write (owner decision D-1)`, () => {
      setup([role], role === 'ROLE_SUPER_ADMIN');
      expect(el().querySelector('[data-testid="payers-table"]')).not.toBeNull();
      expect(el().querySelector('[data-testid="payers-new"]')).toBeNull();
      openDetail();
      expect(el().querySelector('[data-testid="payers-new-plan"]')).toBeNull();
      expect(el().querySelector('[data-testid="payers-edit"]')).toBeNull();
      expect(el().querySelector('[data-testid="payers-deactivate"]')).toBeNull();
      expect(el().querySelector('[data-testid="payers-new-rule-MTA-ESS"]')).toBeNull();
      expect(el().querySelector('[data-testid="payers-end-rule-rule-1"]')).toBeNull();
    });
  }

  it('an empty new payer is refused on every required field, and nothing is sent', async () => {
    setup(['ROLE_ACCOUNTANT']);
    el().querySelector<HTMLButtonElement>('[data-testid="payers-new"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, { button: '[data-testid="payers-save"]' });
    expect(marked).toEqual(jasmine.arrayContaining(['py-code', 'py-name', 'py-type']));
    http.expectNone((r) => r.method === 'POST');
  });

  it('an empty new plan is refused on code, name and rate, and nothing is sent', async () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="payers-new-plan"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="payers-plan-save"]',
      notMarked: ['pp-from'],
    });
    expect(marked).toEqual(jasmine.arrayContaining(['pp-code', 'pp-name', 'pp-percent']));
    http.expectNone((r) => r.method === 'POST');
  });

  it('an empty new rule is refused on its target, and nothing is sent', async () => {
    setup(['ROLE_ACCOUNTANT']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="payers-new-rule-MTA-ESS"]')!.click();
    fixture.detectChanges();
    http.expectOne((r) => r.method === 'GET' && r.url === '/billing/price-list').flush([]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="payers-rule-save"]',
      notMarked: ['pr-from'],
    });
    expect(marked).toEqual(jasmine.arrayContaining(['pr-category']));
    http.expectNone((r) => r.method === 'POST');
  });

  it('a plan rate over 100 % or a decimal ceiling: the server refusal marks the fields it names', async () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="payers-new-plan"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    type('pp-code', 'AMU-BASE');
    type('pp-name', 'Formule Base');
    type('pp-percent', '101');
    type('pp-annual-ceiling', '150000.5');
    el().querySelector<HTMLButtonElement>('[data-testid="payers-plan-save"]')!.click();
    fixture.detectChanges();
    const post = http.expectOne((r) => r.method === 'POST' && r.url === `${BASE}/payer-a/plans`);
    expect(post.request.body.code).toBe('AMU-BASE');
    expect(post.request.body.defaultCoveragePercent).toBe(101);
    // Untouched, the start is left to the server's business day (Africa/Ouagadougou).
    expect(post.request.body.effectiveFrom).toBeNull();
    post.flush(
      fieldErrorsResponse({
        defaultCoveragePercent: 'Le taux est compris entre 0 et 100 %.',
        annualCeilingPerBeneficiary: 'Saisissez le plafond en francs CFA entiers, sans décimales.',
      }).error,
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expectFieldsMarked(fixture, {
      'pp-percent': 'entre 0 et 100',
      'pp-annual-ceiling': 'francs CFA entiers',
    });
  });

  it('an exclusion on a category is sent without a rate; a planned rule asks for confirmation', async () => {
    setup(['ROLE_ACCOUNTANT']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="payers-new-rule-MTA-ESS"]')!.click();
    fixture.detectChanges();
    http.expectOne((r) => r.method === 'GET' && r.url === '/billing/price-list').flush([]);
    fixture.detectChanges();
    await fixture.whenStable();
    choose('pr-category', 2); // LAB_TEST
    const excluded = el().querySelector<HTMLInputElement>('#pr-excluded')!;
    excluded.click();
    fixture.detectChanges();
    expect(el().querySelector('#pr-percent')).toBeNull();
    el().querySelector<HTMLButtonElement>('[data-testid="payers-rule-save"]')!.click();
    fixture.detectChanges();
    const post = http.expectOne(
      (r) => r.method === 'POST' && r.url === `${BASE}/payer-a/plans/plan-a/rules`,
    );
    expect(post.request.body).toEqual(
      jasmine.objectContaining({
        category: 'LAB_TEST',
        priceListItemId: null,
        excluded: true,
        coveragePercent: null,
        perActCeiling: null,
        replaceScheduled: false,
      }),
    );
    post.flush(
      fieldErrorsResponse({
        replaceScheduled: 'Une règle est déjà prévue pour cette cible à partir du 01/12/2026.',
      }).error,
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="payers-replace-question"]')?.textContent).toContain(
      '01/12/2026',
    );
  });

  it('edits a payer: the code stays, the name and type are sent, the answer is shown', async () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="payers-edit"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el().querySelector<HTMLInputElement>('#py-code')!.disabled).toBeTrue();
    type('py-name', 'Mutuelle Test A');
    el().querySelector<HTMLButtonElement>('[data-testid="payers-save"]')!.click();
    fixture.detectChanges();
    const put = http.expectOne((r) => r.method === 'PUT' && r.url === `${BASE}/payer-a`);
    expect(put.request.body).toEqual({ name: 'Mutuelle Test A', payerType: 'MUTUELLE' });
    put.flush({ ...MUT_A, name: 'Mutuelle Test A' });
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([MUT_A]);
    fixture.detectChanges();
    expect(fixture.componentInstance.payerDialog()).toBeNull();
    expect(el().querySelector('#py-detail-title')!.textContent).toContain('MUT-TEST-A');
  });

  it('edits a plan: name and dates only, never its rate or ceilings (review of #1202)', async () => {
    setup(['ROLE_ACCOUNTANT']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="payers-edit-plan-MTA-ESS"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el().querySelector('#pp-percent')).toBeNull();
    type('pp-name', 'Formule Essentielle');
    el().querySelector<HTMLButtonElement>('[data-testid="payers-plan-save"]')!.click();
    fixture.detectChanges();
    const put = http.expectOne(
      (r) => r.method === 'PUT' && r.url === `${BASE}/payer-a/plans/plan-a`,
    );
    expect(put.request.body).toEqual({
      name: 'Formule Essentielle',
      effectiveFrom: null,
      effectiveTo: null,
    });
    put.flush(MUT_A);
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([MUT_A]);
    fixture.detectChanges();
    expect(fixture.componentInstance.planDialog()).toBeNull();
  });

  it('new terms are a dated version; a planned one asks for confirmation (review of #1202)', async () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="payers-new-terms-MTA-ESS"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const component = fixture.componentInstance;
    expect(component.termsForm.defaultCoveragePercent).toBe(75);
    type('pt-percent', '70');
    component.termsDateChanged('2027-01-01');
    el().querySelector<HTMLButtonElement>('[data-testid="payers-terms-save"]')!.click();
    fixture.detectChanges();
    const post = http.expectOne(
      (r) => r.method === 'POST' && r.url === `${BASE}/payer-a/plans/plan-a/terms`,
    );
    expect(post.request.body).toEqual(
      jasmine.objectContaining({
        defaultCoveragePercent: 70,
        perInvoiceCeiling: 30000,
        effectiveFrom: '2027-01-01',
        replaceScheduled: false,
      }),
    );
    post.flush(
      fieldErrorsResponse({
        replaceScheduled: 'Des taux et plafonds sont déjà prévus à partir du 1 janv. 2027.',
      }).error,
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="payers-terms-question"]')?.textContent).toContain(
      'janv. 2027',
    );
    component.termsForm.replaceScheduled = true;
    component.saveTerms();
    const again = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/terms'));
    expect(again.request.body.replaceScheduled).toBeTrue();
    again.flush(MUT_A);
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([MUT_A]);
    expect(component.termsDialog()).toBeNull();
    component.closeTermsDialog();
  });

  it('an empty new terms form is refused on the rate, and nothing is sent', async () => {
    setup(['ROLE_ACCOUNTANT']);
    openDetail();
    el().querySelector<HTMLButtonElement>('[data-testid="payers-new-terms-MTA-ESS"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    type('pt-percent', '');
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="payers-terms-save"]',
      notMarked: ['pt-from'],
    });
    expect(marked).toEqual(jasmine.arrayContaining(['pt-percent']));
    http.expectNone((r) => r.method === 'POST');
  });

  it('a rule on a price list item sends the item, a rate and a ceiling per act', async () => {
    setup(['ROLE_ACCOUNTANT']);
    openDetail();
    const component = fixture.componentInstance;
    component.openRule(MUT_A.plans[0]);
    http
      .expectOne((r) => r.method === 'GET' && r.url === '/billing/price-list')
      .flush([
        {
          id: 'item-cesar',
          hospitalId: 'hosp-1',
          code: 'ACC-CESAR',
          label: 'Césarienne',
          category: 'DELIVERY',
          active: true,
        },
      ]);
    fixture.detectChanges();
    component.pickTarget('ITEM');
    component.pickExcluded(true);
    component.pickExcluded(false);
    component.ruleForm.priceListItemId = 'item-cesar';
    component.ruleForm.coveragePercent = 90;
    component.ruleForm.perActCeiling = 120000;
    component.ruleDateChanged('2026-12-01');
    component.saveRule();
    const post = http.expectOne(
      (r) => r.method === 'POST' && r.url === `${BASE}/payer-a/plans/plan-a/rules`,
    );
    expect(post.request.body).toEqual(
      jasmine.objectContaining({
        category: null,
        priceListItemId: 'item-cesar',
        coveragePercent: 90,
        perActCeiling: 120000,
        excluded: false,
        effectiveFrom: '2026-12-01',
      }),
    );
    post.flush(MUT_A);
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([MUT_A]);
    expect(component.ruleDialog()).toBeNull();
    component.closeRuleDialog();
  });

  it('deactivating a payer and ending a rule each send the reason given', () => {
    setup(['ROLE_HOSPITAL_ADMIN']);
    openDetail();
    const component = fixture.componentInstance;
    component.askDeactivate(MUT_A);
    expect(component.deactivating()).toBe(MUT_A);
    component.deactivate(MUT_A, 'Convention résiliée');
    const off = http.expectOne(
      (r) => r.method === 'POST' && r.url === `${BASE}/payer-a/deactivate`,
    );
    expect(off.request.body).toEqual({ reason: 'Convention résiliée' });
    off.flush({ ...MUT_A, active: false });
    http.expectOne((r) => r.method === 'GET' && r.url === BASE).flush([MUT_A]);
    expect(component.deactivating()).toBeNull();

    const plan = MUT_A.plans[0];
    const rule = plan.rules[0];
    expect(component.canEnd(rule)).toBeTrue();
    expect(component.canEnd({ ...rule, status: 'ENDED' })).toBeFalse();
    component.askEndRule(plan, rule);
    component.endRule(plan, rule, 'Avenant 2026');
    const end = http.expectOne(
      (r) => r.method === 'POST' && r.url === `${BASE}/payer-a/plans/plan-a/rules/rule-1/end`,
    );
    expect(end.request.body).toEqual({ reason: 'Avenant 2026' });
    end.error(new ProgressEvent('error'), { status: 400, statusText: 'Bad Request' });
    expect(component.endBusy()).toBeFalse();
    expect(component.ending()).not.toBeNull();
  });

  it('shows deactivated payers on request, a failed load, and closes the detail', () => {
    setup(['ROLE_CLAIMS_REVIEWER']);
    const component = fixture.componentInstance;
    component.toggleInactive(true);
    const reload = http.expectOne((r) => r.method === 'GET' && r.url === BASE);
    expect(reload.request.params.get('includeInactive')).toBe('true');
    reload.error(new ProgressEvent('error'), { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(component.failed()).toBeTrue();
    expect(el().textContent).toContain('Les organismes payeurs n’ont pas pu être chargés.');
    component.selected.set(MUT_A);
    component.closeDetail();
    expect(component.selected()).toBeNull();
    component.openCreate();
    component.closePayerDialog();
    component.openNewPlan();
    component.planDateChanged('2026-12-15');
    expect(component.planForm.effectiveFromChanged).toBeTrue();
    component.closePlanDialog();
    expect(component.planDialog()).toBeNull();
  });

  it('only the linkable plans of active payers are offered to the Coverage tab picker', () => {
    const inactive: Payer = { ...MUT_A, id: 'payer-x', active: false };
    const ended: Payer = {
      ...MUT_A,
      id: 'payer-y',
      name: 'Assurance Test B (FICTIVE)',
      plans: [{ ...MUT_A.plans[0], id: 'plan-y', linkable: false }],
    };
    expect(linkablePlanOptions([MUT_A, inactive, ended])).toEqual([
      { id: 'plan-a', label: 'Mutuelle Test A (FICTIVE) — Formule Test Essentielle' },
    ]);
  });
});
