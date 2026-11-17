import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalService } from '../services/hospital.service';
import { PharmacyService } from '../services/pharmacy.service';
import { PharmacyRegistryComponent } from './pharmacy-registry';

describe('PharmacyRegistryComponent (QA D336)', () => {
  let roles: string[];

  function create(): PharmacyRegistryComponent {
    TestBed.configureTestingModule({
      imports: [PharmacyRegistryComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: PharmacyService,
          useValue: { listPharmacies: () => of({ data: { content: [], totalPages: 0 } }) },
        },
        { provide: HospitalService, useValue: { getHospitals: () => of([]) } },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => roles.includes('ROLE_SUPER_ADMIN'),
            hasAnyActiveRoleExactly: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
            activeHospitalId: 'h1',
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
        },
      ],
    });
    return TestBed.createComponent(PharmacyRegistryComponent).componentInstance;
  }

  it('does not offer create, edit or delete to a pharmacist', () => {
    roles = ['ROLE_PHARMACIST'];
    expect(create().canManage()).toBeFalse();
  });

  it('offers them to a hospital admin', () => {
    roles = ['ROLE_HOSPITAL_ADMIN'];
    expect(create().canManage()).toBeTrue();
  });

  it('sends no empty string for an unselected type or mode', () => {
    roles = ['ROLE_HOSPITAL_ADMIN'];
    const c = create();
    c.form = { ...c.form, name: 'P', hospitalId: 'h1', pharmacyType: '', fulfillmentMode: '' };
    const req = c.toRequest();
    expect(req.pharmacyType).toBeUndefined();
    expect(req.fulfillmentMode).toBeUndefined();
  });
});
