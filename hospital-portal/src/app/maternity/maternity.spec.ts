import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import fr from '../../assets/i18n/fr.json';

import { MaternityComponent, historyFormProblem } from './maternity';
import { MaternityService, MaternalHistoryResponse } from '../services/maternity.service';
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

/**
 * QA D826 (round-13 re-test): "Nouvel historique" for a patient who already
 * has a maternal history ended on the server's English 400. The form now finds
 * her current history when she is picked and offers to open it, and a 409 from
 * a race shows the French reason and the same offer.
 */
describe('MaternityComponent — new history for a patient who already has one (QA D826)', () => {
  let fixture: ComponentFixture<MaternityComponent>;
  let maternity: jasmine.SpyObj<MaternityService>;
  let toast: jasmine.SpyObj<ToastService>;
  const existing = {
    id: 'mh-1',
    patientId: 'p-fatimata',
    hospitalId: 'h-1',
    obstetricHistory: { gravida: 2, para: 1 },
  } as unknown as MaternalHistoryResponse;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const notice = (): HTMLElement | null => host().querySelector('[data-testid="history-exists"]');
  const heldElsewhere = (): HTMLElement | null =>
    host().querySelector('[data-testid="history-held-elsewhere"]');
  const saveButton = (): HTMLButtonElement =>
    host().querySelector('.modal-actions .btn-primary') as HTMLButtonElement;

  beforeEach(() => {
    const emptyPage = of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    maternity = jasmine.createSpyObj<MaternityService>('MaternityService', [
      'highRisk',
      'currentForPatient',
      'create',
    ]);
    maternity.highRisk.and.returnValue(emptyPage);
    const patients = jasmine.createSpyObj<PatientService>('PatientService', [
      'list',
      'search',
      'lookup',
    ]);
    patients.list.and.returnValue(of([]));
    patients.search.and.returnValue(of([]));
    patients.lookup.and.returnValue(of([]));
    const auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getHospitalId',
      'getUserProfile',
    ]);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['error', 'success']);
    TestBed.configureTestingModule({
      imports: [MaternityComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: MaternityService, useValue: maternity },
        {
          provide: ObgynReferralService,
          useValue: jasmine.createSpyObj('ObgynReferralService', ['summary']),
        },
        { provide: PatientService, useValue: patients },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h-1',
            roles: ['ROLE_MIDWIFE'],
          }),
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    fixture = TestBed.createComponent(MaternityComponent);
    fixture.detectChanges();
    fixture.componentInstance.openCreateHistory();
    fixture.detectChanges();
  });

  it('picking a patient who has a history offers to open it, in French, and holds the create', () => {
    maternity.currentForPatient.and.returnValue(of(existing));

    fixture.componentInstance.onHistoryPatientPicked({ id: 'p-fatimata' } as never);
    fixture.detectChanges();

    expect(maternity.currentForPatient).toHaveBeenCalledWith('p-fatimata');
    expect(notice()?.textContent).toContain('Cette patiente a déjà un historique maternel');
    expect(notice()?.textContent).toContain("Ouvrir et mettre à jour l'historique");
    expect(saveButton().disabled).toBeTrue();

    (notice()!.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.editingHistoryId()).toBe('mh-1');
    expect(fixture.componentInstance.historyForm.obstetricHistory?.gravida).toBe(2);
    expect(notice()).toBeNull();
  });

  it('QA D1005: the due date follows the LMP and the gestational age is shown', () => {
    const page = fixture.componentInstance;
    // Through the field, as the midwife types it (the page is OnPush).
    const lmp = host().querySelector('#history-lmp') as HTMLInputElement;
    lmp.value = '2026-05-20';
    lmp.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(page.historyForm.menstrualHistory?.estimatedDueDate).toBe('2027-02-24');
    expect(host().querySelector('[data-testid="history-ga"]')).not.toBeNull();
    // A due date the midwife typed herself is kept when the LMP changes.
    page.historyForm.menstrualHistory!.estimatedDueDate = '2027-03-01';
    page.onLmpChange('2026-05-21');
    expect(page.historyForm.menstrualHistory?.estimatedDueDate).toBe('2027-03-01');
  });

  it('a patient with no history (404) keeps the create open', () => {
    maternity.currentForPatient.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );

    fixture.componentInstance.onHistoryPatientPicked({ id: 'p-new' } as never);
    fixture.detectChanges();

    expect(notice()).toBeNull();
    expect(saveButton().disabled).toBeFalse();
  });

  it('a 409 on save shows the French reason, never the English server text, and the offer', () => {
    maternity.currentForPatient.and.returnValues(
      throwError(() => new HttpErrorResponse({ status: 404 })),
      of(existing),
    );
    maternity.create.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { message: 'Maternal history already exists for patient.', field: 'patientId' },
          }),
      ),
    );
    fixture.componentInstance.onHistoryPatientPicked({ id: 'p-fatimata' } as never);

    fixture.componentInstance.submitHistory();
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledWith(
      jasmine.stringContaining('Cette patiente a déjà un historique maternel'),
    );
    expect(toast.error).not.toHaveBeenCalledWith(jasmine.stringContaining('already exists'));
    expect(notice()).not.toBeNull();
  });

  it('a current history held by another facility is never offered for edit: notice, Save disabled', () => {
    maternity.currentForPatient.and.returnValue(of({ ...existing, hospitalId: 'h-2' }));

    fixture.componentInstance.onHistoryPatientPicked({ id: 'p-fatimata' } as never);
    fixture.detectChanges();

    expect(notice()).toBeNull();
    expect(heldElsewhere()?.textContent).toContain('détenu par un autre établissement');
    expect(heldElsewhere()?.querySelector('button')).toBeNull();
    expect(saveButton().disabled).toBeTrue();

    fixture.componentInstance.submitHistory();
    expect(maternity.create).not.toHaveBeenCalled();
  });

  it('a 409 whose lookup finds nothing readable (404) says the history is held elsewhere, and holds Save', () => {
    maternity.currentForPatient.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );
    maternity.create.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { message: 'Maternal history already exists for patient.', field: 'patientId' },
          }),
      ),
    );
    fixture.componentInstance.onHistoryPatientPicked({ id: 'p-fatimata' } as never);
    fixture.detectChanges();
    expect(saveButton().disabled).toBeFalse();

    fixture.componentInstance.submitHistory();
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledWith(
      jasmine.stringContaining('détenu par un autre établissement'),
    );
    expect(toast.error).not.toHaveBeenCalledWith(jasmine.stringContaining('Ouvrez-le'));
    expect(notice()).toBeNull();
    expect(heldElsewhere()).not.toBeNull();
    expect(saveButton().disabled).toBeTrue();

    fixture.componentInstance.submitHistory();
    expect(maternity.create).toHaveBeenCalledTimes(1);
  });

  it('a 409 whose lookup finds a record of another facility says it is held elsewhere, not "open it"', () => {
    maternity.currentForPatient.and.returnValues(
      throwError(() => new HttpErrorResponse({ status: 404 })),
      of({ ...existing, hospitalId: 'h-2' }),
    );
    maternity.create.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409 })));
    fixture.componentInstance.onHistoryPatientPicked({ id: 'p-fatimata' } as never);

    fixture.componentInstance.submitHistory();
    fixture.detectChanges();

    expect(toast.error).toHaveBeenCalledWith(
      jasmine.stringContaining('détenu par un autre établissement'),
    );
    expect(notice()).toBeNull();
    expect(heldElsewhere()).not.toBeNull();
    expect(saveButton().disabled).toBeTrue();
  });
});

describe('historyFormProblem (QA D809, D821)', () => {
  const now = new Date(2026, 9, 25, 12, 0);
  const form = (over: Record<string, unknown>) =>
    ({
      patientId: 'p1',
      hospitalId: 'h1',
      recordedDate: '2026-10-25T11:30',
      obstetricHistory: {},
      ...over,
    }) as never;

  it('refuses G1 P3, as the server does', () => {
    expect(historyFormProblem(form({ obstetricHistory: { gravida: 1, para: 3 } }), now)).toBe(
      'MATERNITY.PARA_ABOVE_GRAVIDA',
    );
  });

  it('refuses births plus abortions above gravidity', () => {
    expect(
      historyFormProblem(form({ obstetricHistory: { gravida: 3, para: 1, abortions: 3 } }), now),
    ).toBe('MATERNITY.OUTCOMES_ABOVE_GRAVIDA');
  });

  it('refuses a record dated in the future (29 Oct seen on 25 Oct)', () => {
    expect(historyFormProblem(form({ recordedDate: '2026-10-29T12:00' }), now)).toBe(
      'MATERNITY.RECORDED_FUTURE',
    );
  });

  it('accepts possible counts, blank counts and a time within the clock allowance', () => {
    expect(
      historyFormProblem(form({ obstetricHistory: { gravida: 3, para: 2, abortions: 1 } }), now),
    ).toBeNull();
    expect(historyFormProblem(form({ obstetricHistory: { para: 2 } }), now)).toBeNull();
    expect(historyFormProblem(form({ recordedDate: '2026-10-25T12:05' }), now)).toBeNull();
  });
});
