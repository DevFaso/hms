import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { MaternityComponent } from './maternity';
import { MaternityService } from '../services/maternity.service';
import { ObgynReferralService } from '../services/obgyn-referral.service';
import { PatientService } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { RoleContextStub, roleContextStub } from '../testing/role-context.stub';

/**
 * The maternity page's tab strip: which tabs a role sees, where it lands and
 * what it loads on open. Asserted through the rendered DOM in French (the #661
 * shape), so a tab label only counts once it reads translated.
 */
describe('MaternityComponent — tabs', () => {
  let fixture: ComponentFixture<MaternityComponent>;
  let maternity: jasmine.SpyObj<MaternityService>;
  let referrals: jasmine.SpyObj<ObgynReferralService>;
  let roleCtx: RoleContextStub;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  /** The page's own tab strip: the first filter bar (the board has a second). */
  const tabButtons = (): HTMLElement[] =>
    Array.from(host().querySelector('.filter-bar')?.querySelectorAll('.tab-btn') ?? []);
  const tabs = (): string[] => tabButtons().map((el) => el.textContent?.trim() ?? '');
  const activeTab = (): string =>
    tabButtons()
      .find((el) => el.classList.contains('active'))
      ?.textContent?.trim() ?? '';

  function create(roles: string[]): void {
    roleCtx.set({ roles });
    fixture = TestBed.createComponent(MaternityComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    const emptyPage = of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    maternity = jasmine.createSpyObj<MaternityService>('MaternityService', [
      'highRisk',
      'pendingReview',
      'specialistReferral',
      'psychosocialConcerns',
      'search',
    ]);
    maternity.highRisk.and.returnValue(emptyPage);
    referrals = jasmine.createSpyObj<ObgynReferralService>('ObgynReferralService', [
      'summary',
      'byHospital',
      'assignedTo',
    ]);
    const patients = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patients.list.and.returnValue(of([]));
    const auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getHospitalId',
      'getUserProfile',
    ]);
    roleCtx = roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles: [] });

    TestBed.configureTestingModule({
      imports: [MaternityComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: MaternityService, useValue: maternity },
        { provide: ObgynReferralService, useValue: referrals },
        { provide: PatientService, useValue: patients },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: jasmine.createSpyObj('ToastService', ['error']) },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      MATERNITY: {
        TITLE: 'Maternité',
        BOARD_TAB: 'Tableau',
        REFERRALS_TAB: 'Références',
        ULTRASOUND_TAB: 'Échographies',
        BIRTH_PLANS_TAB: 'Projets de naissance',
        PRENATAL_TAB: 'Prénatal',
        LABOR_TAB: 'Travail',
        POSTPARTUM_TAB: 'Post-partum',
      },
    });
  });

  it('gives a receptionist the prenatal tab alone, and loads nothing it would be refused', () => {
    create(['ROLE_RECEPTIONIST']);
    expect(host().querySelector('h1')?.textContent?.trim()).toBe('Maternité');
    expect(tabs()).toEqual(['Prénatal']);
    expect(activeTab()).toBe('Prénatal');
    expect(host().querySelector('app-prenatal-tab')).not.toBeNull();
    expect(maternity.highRisk).not.toHaveBeenCalled();
    expect(referrals.summary).not.toHaveBeenCalled();
  });

  it('gives a nurse every tab but ultrasound, landing on the board', () => {
    create(['ROLE_NURSE']);
    expect(tabs()).toEqual([
      'Tableau',
      'Références',
      'Projets de naissance',
      'Prénatal',
      'Travail',
      'Post-partum',
    ]);
    expect(activeTab()).toBe('Tableau');
    expect(maternity.highRisk).toHaveBeenCalledWith('h-1', 0);
    // Referral data waits for its tab.
    expect(referrals.summary).not.toHaveBeenCalled();
  });

  it('gives a midwife the ultrasound tab and renders it on click', () => {
    create(['ROLE_MIDWIFE']);
    expect(tabs()).toContain('Échographies');
    const ultrasound = tabButtons().find((b) => b.textContent?.trim() === 'Échographies')!;
    ultrasound.click();
    fixture.detectChanges();
    expect(activeTab()).toBe('Échographies');
    expect(host().querySelector('app-ultrasound-tab')).not.toBeNull();
  });
});
