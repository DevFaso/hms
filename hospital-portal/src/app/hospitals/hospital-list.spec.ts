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
});
