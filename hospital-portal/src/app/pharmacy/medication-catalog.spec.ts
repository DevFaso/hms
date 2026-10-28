import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';
import { of } from 'rxjs';

import { MedicationCatalogComponent } from './medication-catalog';
import { PharmacyService, MedicationCatalogItemResponse } from '../services/pharmacy.service';
import { RoleContextService } from '../core/role-context.service';

const item = {
  id: 'm-1',
  nameFr: 'Amoxicilline',
  form: 'TABLET',
  active: true,
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

  it('QA D935: the paediatric ceiling is carried into the edit form and starts empty on a new item', () => {
    render(['ROLE_HOSPITAL_ADMIN']);
    const component = TestBed.createComponent(MedicationCatalogComponent).componentInstance;
    component.openEdit({
      id: 'cat-1',
      nameFr: 'Paracétamol sirop',
      genericName: 'paracetamol',
      essentialList: true,
      controlled: false,
      active: true,
      pediatricMaxDoseMgPerKg: 15,
    } as never);
    expect(component.form.pediatricMaxDoseMgPerKg).toBe(15);
    component.openCreate();
    expect(component.form.pediatricMaxDoseMgPerKg).toBeNull();
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
