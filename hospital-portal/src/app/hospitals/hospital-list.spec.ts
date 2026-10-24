import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { HospitalListComponent } from './hospital-list';
import { HospitalResponse, HospitalService } from '../services/hospital.service';
import { OrganizationPage, OrganizationService } from '../services/organization.service';
import { RoleContextService } from '../core/role-context.service';

const hospital: HospitalResponse = {
  id: 'h-1',
  name: 'Hôpital A',
  code: 'HA',
  address: '',
  city: 'Ouagadougou',
  state: '',
  zipCode: '',
  country: 'BF',
  province: '',
  region: '',
  sector: '',
  poBox: '',
  phoneNumber: '',
  email: '',
  website: '',
  organizationId: '',
  organizationName: '',
  organizationCode: '',
  active: true,
  lifecycleState: 'ACTIVE',
  createdAt: '',
  updatedAt: '',
};

describe('HospitalListComponent (QA D36)', () => {
  function render(superAdmin: boolean): HTMLElement {
    const hospitalService = jasmine.createSpyObj<HospitalService>('HospitalService', ['list']);
    hospitalService.list.and.returnValue(of([hospital]));
    const orgService = jasmine.createSpyObj<OrganizationService>('OrganizationService', ['list']);
    orgService.list.and.returnValue(of({ content: [] } as unknown as OrganizationPage));
    TestBed.configureTestingModule({
      imports: [HospitalListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: HospitalService, useValue: hospitalService },
        { provide: OrganizationService, useValue: orgService },
        { provide: RoleContextService, useValue: { isSuperAdmin: signal(superAdmin) } },
      ],
    });
    const fixture = TestBed.createComponent(HospitalListComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('hides create, edit and delete from anyone but the super-admin', () => {
    const el = render(false);
    expect(el.querySelector('.page-header .btn-primary')).toBeNull();
    expect(el.querySelector('[data-test="hospital-edit-h-1"]')).toBeNull();
    expect(el.querySelector('[data-test="hospital-delete-h-1"]')).toBeNull();
    expect(el.textContent).toContain('Hôpital A');
  });

  it('offers create, edit and delete to the super-admin', () => {
    const el = render(true);
    expect(el.querySelector('.page-header .btn-primary')).not.toBeNull();
    expect(el.querySelector('[data-test="hospital-edit-h-1"]')).not.toBeNull();
    expect(el.querySelector('[data-test="hospital-delete-h-1"]')).not.toBeNull();
  });

  it('QA D435: a Burkina Faso hospital needs an address or a P.O. box, and is told so before saving', () => {
    const hospitalService = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'create',
    ]);
    hospitalService.list.and.returnValue(of([]));
    const orgService = jasmine.createSpyObj<OrganizationService>('OrganizationService', ['list']);
    orgService.list.and.returnValue(of({ content: [] } as unknown as OrganizationPage));
    TestBed.configureTestingModule({
      imports: [HospitalListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: HospitalService, useValue: hospitalService },
        { provide: OrganizationService, useValue: orgService },
        { provide: RoleContextService, useValue: { isSuperAdmin: signal(true) } },
      ],
    });
    const fixture = TestBed.createComponent(HospitalListComponent);
    fixture.detectChanges();
    const cmp = fixture.componentInstance;
    Object.assign(cmp.form, {
      name: 'CMA Pissy',
      city: 'Ouagadougou',
      country: 'BF',
      phoneNumber: '+22670000000',
      address: '  ',
      poBox: '',
    });

    cmp.submitForm();

    expect(hospitalService.create).not.toHaveBeenCalled();
    const label = (fixture.nativeElement as HTMLElement).querySelector('label[for="hAddress"]');
    expect(label === null || label.textContent!.includes('*')).toBeTrue();
  });

  it('QA D734/D747: the street is always marked and enforced, a P.O. box does not replace it', async () => {
    const hospitalService = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'create',
    ]);
    hospitalService.list.and.returnValue(of([]));
    hospitalService.create.and.returnValue(of({ id: 'h-9' } as HospitalResponse));
    const orgService = jasmine.createSpyObj<OrganizationService>('OrganizationService', ['list']);
    orgService.list.and.returnValue(of({ content: [] } as unknown as OrganizationPage));
    TestBed.configureTestingModule({
      imports: [HospitalListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: HospitalService, useValue: hospitalService },
        { provide: OrganizationService, useValue: orgService },
        { provide: RoleContextService, useValue: { isSuperAdmin: signal(true) } },
      ],
    });
    const fixture = TestBed.createComponent(HospitalListComponent);
    fixture.detectChanges();
    const cmp = fixture.componentInstance;
    cmp.openCreate();
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const label = (id: string) => el.querySelector(`label[for="${id}"]`)!.textContent!;
    const required = (id: string) => (el.querySelector(`#${id}`) as HTMLInputElement).required;

    // Round 10 re-test (hosp2.js): a P.O. box and an empty or blank street
    // were saved. The street is required whatever the P.O. box holds.
    expect(label('hAddress')).toContain('*');
    expect(label('hPoBox')).not.toContain('*');
    for (const street of ['', '   ']) {
      Object.assign(cmp.form, {
        name: 'Centre Médical Saint-Camille de Koudougou',
        city: 'Koudougou',
        country: 'Burkina Faso',
        phoneNumber: '+22625441122',
        address: street,
        poBox: '01 BP 1234',
      });
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(required('hAddress')).toBeTrue();
      cmp.submitForm();
    }
    expect(hospitalService.create).not.toHaveBeenCalled();

    cmp.form.address = 'Avenue de la Nation';
    cmp.submitForm();
    expect(hospitalService.create).toHaveBeenCalled();
  });
});
