import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { HOSPITAL_CODE_PATTERN, HospitalListComponent } from './hospital-list';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import {
  HOSPITAL_CURRENCIES,
  HospitalRequest,
  HospitalResponse,
  HospitalService,
} from '../services/hospital.service';
import { OrganizationPage, OrganizationService } from '../services/organization.service';
import { expectFieldsMarked, fieldErrorsResponse } from '../testing/form-refusal-behaviour';

/**
 * QA r65 D2500 / D2501: the super-admin chooses a hospital's code, currency
 * and time zone on the hospital form.
 */
describe('HospitalListComponent — code, currency and time zone (QA r65 D2500 / D2501)', () => {
  let hospitalService: jasmine.SpyObj<HospitalService>;
  let toast: jasmine.SpyObj<ToastService>;

  const stored: HospitalResponse = {
    id: 'h-1',
    name: 'Centre Médical Test',
    code: 'CMT-C5F20',
    address: 'Rue 1',
    city: 'Ouagadougou',
    state: '',
    zipCode: '',
    country: 'BF',
    province: '',
    region: '',
    sector: '',
    poBox: '',
    phoneNumber: '+22625300000',
    email: '',
    website: '',
    organizationId: '',
    organizationName: '',
    organizationCode: '',
    active: true,
    lifecycleState: 'ACTIVE',
    createdAt: '',
    updatedAt: '',
    currencyCode: 'XAF',
    timeZone: 'Pacific/Auckland',
    effectiveTimeZone: 'Pacific/Auckland',
  };

  function render(): ComponentFixture<HospitalListComponent> {
    hospitalService = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'create',
      'update',
    ]);
    hospitalService.list.and.returnValue(of([stored]));
    hospitalService.create.and.returnValue(of(stored));
    hospitalService.update.and.returnValue(of(stored));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    const orgService = jasmine.createSpyObj<OrganizationService>('OrganizationService', ['list']);
    orgService.list.and.returnValue(of({ content: [] } as unknown as OrganizationPage));
    TestBed.configureTestingModule({
      imports: [HospitalListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: HospitalService, useValue: hospitalService },
        { provide: OrganizationService, useValue: orgService },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: { isSuperAdmin: signal(true) } },
      ],
    });
    const fixture = TestBed.createComponent(HospitalListComponent);
    document.body.appendChild(fixture.nativeElement);
    attached.push(fixture.nativeElement as HTMLElement);
    fixture.detectChanges();
    return fixture;
  }

  function fillRequired(component: HospitalListComponent): void {
    Object.assign(component.form, {
      name: 'Clinique Test D',
      city: 'Ouagadougou',
      country: 'BF',
      phoneNumber: '+22625300000',
      address: 'Rue 2',
    });
  }

  const field = (fixture: ComponentFixture<unknown>, id: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(`[data-testid="${id}"]`);

  /** The fixtures attached to the document (a marked field is focused), removed after each test. */
  const attached: HTMLElement[] = [];

  afterEach(() => {
    attached.splice(0).forEach((host) => host.remove());
    TestBed.resetTestingModule();
  });

  it('the create form offers the code, the currencies and « celui de l’organisation » for the zone', () => {
    const fixture = render();
    fixture.componentInstance.openCreate();
    fixture.detectChanges();

    expect(field(fixture, 'hospital-code')).not.toBeNull();
    const currencies = Array.from(field(fixture, 'hospital-currency')!.querySelectorAll('option'));
    expect(currencies.length).toBe(HOSPITAL_CURRENCIES.length);
    const zones = Array.from(field(fixture, 'hospital-time-zone')!.querySelectorAll('option'));
    expect(zones[0].textContent).toContain('PLATFORM_SETUP.HOSPITAL.TIME_ZONE_INHERIT');
    // Each zone with its offset today, so two zones on one clock are told apart.
    const labels = zones.map((o) => o.textContent?.trim() ?? '');
    expect(labels.some((label) => /^Africa\/Ouagadougou \(GMT(\+0)?\)$/.test(label)))
      .withContext(labels.join(' | '))
      .toBeTrue();
    expect(labels.some((label) => /^Africa\/Nairobi \(GMT\+3\)$/.test(label))).toBeTrue();
  });

  it('create: a typed code goes out in capitals, with the currency and the zone', () => {
    const component = render().componentInstance;
    component.openCreate();
    fillRequired(component);
    component.form.code = ' twp ';
    component.form.currencyCode = 'XAF';
    component.form.timeZone = 'Africa/Douala';

    component.submitForm();

    const sent = hospitalService.create.calls.mostRecent().args[0] as HospitalRequest;
    expect(sent.code).toBe('TWP');
    expect(sent.currencyCode).toBe('XAF');
    expect(sent.timeZone).toBe('Africa/Douala');
  });

  it('create: no code typed sends none (the server generates one), XOF, and an empty zone', () => {
    const component = render().componentInstance;
    component.openCreate();
    fillRequired(component);

    component.submitForm();

    const sent = hospitalService.create.calls.mostRecent().args[0] as HospitalRequest;
    expect('code' in sent).toBeFalse();
    expect(sent.currencyCode).toBe('XOF');
    expect(sent.timeZone).toBe('');
  });

  it('a malformed code is refused on its field before anything is sent', () => {
    const fixture = render();
    const component = fixture.componentInstance;
    component.openCreate();
    fillRequired(component);
    fixture.detectChanges(); // the dialog is on screen before anything is refused

    for (const bad of ['T W P', 'A', '-TWP', 'TWP/1', 'ABCDEFGHIJKLMNOPQRSTU']) {
      component.form.code = bad;
      component.submitForm();
    }
    fixture.detectChanges();

    expect(hospitalService.create).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('PLATFORM_SETUP.HOSPITAL.CODE_INVALID');
    expect(field(fixture, 'hospital-code')!.getAttribute('aria-invalid')).toBe('true');
    // Said under the field, not only in a toast.
    expectFieldsMarked(fixture, { hCode: 'PLATFORM_SETUP.HOSPITAL.CODE_INVALID' });
  });

  it('a code the server refuses (taken, or locked by a first invoice) is marked on the code field', () => {
    const fixture = render();
    const component = fixture.componentInstance;
    component.openEdit(stored);
    fixture.detectChanges();
    hospitalService.update.and.returnValue(
      throwError(() =>
        fieldErrorsResponse({
          code: 'Cet hôpital a déjà émis des factures : le code ne peut plus être modifié.',
        }),
      ),
    );

    component.form.code = 'TWP';
    component.submitForm();
    fixture.detectChanges();

    expectFieldsMarked(fixture, {
      hCode: 'Cet hôpital a déjà émis des factures : le code ne peut plus être modifié.',
    });
    expect(component.saving()).toBeFalse();
    expect(component.showModal()).toBeTrue();
  });

  // Platform admin rules 2026-11-20, rule 3: one currency per facility, locked once it has billed.
  it('each currency is named with its ISO code, and the form says when the currency locks', () => {
    const fixture = render();
    fixture.componentInstance.openCreate();
    fixture.detectChanges();

    const select = field(fixture, 'hospital-currency')!;
    const labels = Array.from(select.querySelectorAll('option')).map(
      (option) => option.textContent?.trim() ?? '',
    );
    expect(labels.map((label) => label.split(' · ').pop())).toEqual([...HOSPITAL_CURRENCIES]);
    expect(HOSPITAL_CURRENCIES).toEqual(['XOF', 'XAF']);
    const hint = (fixture.nativeElement as HTMLElement).querySelector('#hCurrencyHint');
    expect(hint?.textContent).toContain('PLATFORM_SETUP.HOSPITAL.CURRENCY_HINT');
    expect(select.getAttribute('aria-describedby')).toContain('hCurrencyHint');
  });

  it('a currency the server refuses (locked by a first invoice or price) is marked on the currency field', () => {
    const fixture = render();
    const component = fixture.componentInstance;
    component.openEdit(stored);
    fixture.detectChanges();
    const refusal =
      'Cet hôpital a déjà une facture ou un tarif dans sa devise actuelle : la devise ne peut plus être modifiée.';
    hospitalService.update.and.returnValue(
      throwError(() => fieldErrorsResponse({ currencyCode: refusal })),
    );

    component.form.currencyCode = 'XOF';
    component.submitForm();
    fixture.detectChanges();

    expectFieldsMarked(fixture, { hCurrency: refusal });
    expect(component.saving()).toBeFalse();
    expect(component.showModal()).toBeTrue();
  });

  it('the pattern is the server’s: letters, digits and hyphens, 2 to 20, alphanumeric at both ends', () => {
    for (const good of ['TWP', 'CMT-C5F20', 'A1', 'ABCDEFGHIJKLMNOPQRST']) {
      expect(HOSPITAL_CODE_PATTERN.test(good)).withContext(good).toBeTrue();
    }
    for (const bad of ['A', 'AB-', '-AB', 'A B', 'É1', 'ABCDEFGHIJKLMNOPQRSTU']) {
      expect(HOSPITAL_CODE_PATTERN.test(bad)).withContext(bad).toBeFalse();
    }
  });

  it('edit: the form opens on the stored code, currency and zone, and keeps a zone outside the list', () => {
    const fixture = render();
    const component = fixture.componentInstance;
    component.openEdit(stored);
    fixture.detectChanges();

    expect(component.form.code).toBe('CMT-C5F20');
    expect(component.form.currencyCode).toBe('XAF');
    expect(component.form.timeZone).toBe('Pacific/Auckland');
    // The hospital's own zone is offered even though the short list does not hold it.
    expect(component.timeZones()).toContain('Pacific/Auckland');

    component.form.timeZone = '';
    component.submitForm();

    const [id, sent] = hospitalService.update.calls.mostRecent().args as [string, HospitalRequest];
    expect(id).toBe('h-1');
    expect(sent.code).toBe('CMT-C5F20');
    // An empty zone is sent as such: back to the organisation's.
    expect(sent.timeZone).toBe('');
  });
});
