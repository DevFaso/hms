import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { MedicationCatalogComponent } from './medication-catalog';
import { PharmacyService, MedicationCatalogItemResponse } from '../services/pharmacy.service';
import { RoleContextService } from '../core/role-context.service';

const item = { id: 'm-1', nameFr: 'Amoxicilline', active: true } as MedicationCatalogItemResponse;

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

  it('offers the hospital admin create, edit and delete', () => {
    const el = render(['ROLE_HOSPITAL_ADMIN']);
    expect(el.querySelector('.page-header .btn-primary')).not.toBeNull();
    expect(el.querySelector('[data-test="catalog-edit"]')).not.toBeNull();
    expect(el.querySelector('[data-test="catalog-delete"]')).not.toBeNull();
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
