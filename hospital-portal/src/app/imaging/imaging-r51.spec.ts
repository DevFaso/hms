import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';

import { ImagingComponent } from './imaging';
import {
  ImagingService,
  ImagingOrderResponse,
  ImagingOrderStatus,
  ImagingReportResponse,
} from '../services/imaging.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { actionRoles } from '../core/action-permissions';
import en from '../../assets/i18n/en.json';
import es from '../../assets/i18n/es.json';
import fr from '../../assets/i18n/fr.json';

/**
 * QA r51 (D1820, D1821, D1823, D1826): the Imagerie screen could not start
 * or complete a study, so a new order could not be reported from the UI;
 * and the same screen named one report status two ways.
 */
function order(overrides: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse {
  return {
    id: 'o1',
    patientId: 'p1',
    patientDisplayName: 'Seydou Traoré',
    patientMrn: 'MRN-0042',
    hospitalId: 'h1',
    modality: 'XRAY',
    studyType: 'Radiographie thorax',
    bodyRegion: 'Thorax',
    priority: 'ROUTINE',
    status: 'ORDERED',
    laterality: 'NOT_APPLICABLE',
    orderedAt: '2026-11-14T13:19:00',
    completedAt: null,
    ...overrides,
  } as ImagingOrderResponse;
}

function report(overrides: Partial<ImagingReportResponse> = {}): ImagingReportResponse {
  return {
    id: 'r1',
    imagingOrderId: 'o1',
    modality: 'XRAY',
    reportStatus: 'PRELIMINARY',
    reportTitle: 'Radiographie thorax',
    signed: false,
    findings: 'Pas de foyer.',
    impression: 'Normal.',
    criticalFinding: false,
    statusHistory: [],
    ...overrides,
  } as ImagingReportResponse;
}

const ALL_STATUSES: ImagingOrderStatus[] = [
  'DRAFT',
  'ORDERED',
  'PENDING_AUTHORIZATION',
  'SCHEDULED',
  'IN_PROGRESS',
  'COMPLETED',
  'RESULTS_AVAILABLE',
  'CANCELLED',
];

interface Setup {
  role: string;
  orders: ImagingOrderResponse[];
  lang?: 'fr' | 'es' | 'en';
}

describe('ImagingComponent — r51 study workflow and labels', () => {
  let fixture: ComponentFixture<ImagingComponent>;
  let component: ImagingComponent;
  let imaging: jasmine.SpyObj<ImagingService>;
  let toast: jasmine.SpyObj<ToastService>;
  let orders: ImagingOrderResponse[];

  beforeAll(() => registerLocaleData(localeFr));

  function setUp({ role, orders: list, lang }: Setup): void {
    orders = list;
    imaging = jasmine.createSpyObj('ImagingService', [
      'getAllOrders',
      'updateOrderStatus',
      'getReportsForOrder',
      'getReportsByHospital',
      'getLatestReportByOrder',
      'createReport',
      'updateReport',
      'signReport',
    ]);
    imaging.getAllOrders.and.callFake(() => of(orders));
    imaging.getReportsForOrder.and.returnValue(of([]));
    imaging.getReportsByHospital.and.returnValue(of([]));
    const hospital = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospital.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'CHU' } as HospitalResponse),
    );
    const auth = jasmine.createSpyObj('AuthService', ['getHospitalId', 'getUserProfile']);
    auth.getHospitalId.and.returnValue('h1');
    auth.getUserProfile.and.returnValue({ id: 'u-me' } as never);
    const patients = jasmine.createSpyObj('PatientService', ['list']);
    patients.list.and.returnValue(of([]));
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);

    TestBed.configureTestingModule({
      imports: [ImagingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'fr' },
        { provide: ImagingService, useValue: imaging },
        { provide: HospitalService, useValue: hospital },
        { provide: PatientService, useValue: patients },
        { provide: ToastService, useValue: toast },
        { provide: AuthService, useValue: auth },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => false,
            globalView: () => false,
            activeHospitalId: 'h1',
            hasAnyActiveRole: (roles: string[]) => roles.includes(role),
          } as unknown as RoleContextService,
        },
      ],
    });
    if (lang) {
      const translate = TestBed.inject(TranslateService);
      translate.setTranslation(lang, { fr, es, en }[lang]);
      translate.use(lang);
    }
    fixture = TestBed.createComponent(ImagingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  const q = (sel: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(sel);

  describe('D1821: who is offered start and complete, and when', () => {
    // The roles PUT /imaging/orders/{id}/status admits, read from the one registry.
    const statusRoles = actionRoles('imagingOrderStatus');

    it('the registry still names exactly the endpoint roles (no widening)', () => {
      // User decision 2026-11-14: SUPER_ADMIN no longer acts on a study.
      expect([...statusRoles].sort()).toEqual(['ROLE_DOCTOR', 'ROLE_RADIOLOGIST']);
    });

    for (const role of ['ROLE_DOCTOR', 'ROLE_RADIOLOGIST']) {
      it(`${role}: start on an ordered or scheduled order, complete only once in progress`, () => {
        setUp({ role, orders: ALL_STATUSES.map((status) => order({ id: status, status })) });
        expect(component.activeTab()).toBe('all');
        expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(ALL_STATUSES.length);
        for (const status of ALL_STATUSES) {
          expect(q(`[data-testid="imaging-start-${status}"]`) !== null)
            .withContext(`start on ${status}`)
            .toBe(status === 'ORDERED' || status === 'SCHEDULED');
          expect(q(`[data-testid="imaging-complete-${status}"]`) !== null)
            .withContext(`complete on ${status}`)
            .toBe(status === 'IN_PROGRESS');
        }
      });
    }

    for (const role of [
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
      'ROLE_RECEPTIONIST',
      'ROLE_SUPER_ADMIN',
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_ADMIN',
    ]) {
      it(`${role}: neither start nor complete, on any status`, () => {
        setUp({ role, orders: ALL_STATUSES.map((status) => order({ id: status, status })) });
        expect(q('[data-testid^="imaging-start-"]')).toBeNull();
        expect(q('[data-testid^="imaging-complete-"]')).toBeNull();
      });
    }

    it('the request drawer offers the same actions, and the report once in progress', () => {
      setUp({ role: 'ROLE_DOCTOR', orders: [order()], lang: 'fr' });
      component.viewDetail(order());
      fixture.detectChanges();
      expect(q('[data-testid="imaging-drawer-start"]')!.textContent).toContain("Démarrer l'examen");
      expect(q('[data-testid="imaging-drawer-complete"]')).toBeNull();
      expect(q('[data-testid="imaging-drawer-author"]')).toBeNull();

      component.viewDetail(order({ status: 'IN_PROGRESS' }));
      fixture.detectChanges();
      expect(q('[data-testid="imaging-drawer-start"]')).toBeNull();
      expect(q('[data-testid="imaging-drawer-complete"]')!.textContent).toContain(
        "Terminer l'examen",
      );
      expect(q('[data-testid="imaging-drawer-author"]')!.textContent).toContain(
        'Rédiger le compte rendu',
      );
    });

    it('a nurse opening the drawer sees no workflow action', () => {
      setUp({ role: 'ROLE_NURSE', orders: [order({ status: 'IN_PROGRESS' })] });
      component.viewDetail(order({ status: 'IN_PROGRESS' }));
      fixture.detectChanges();
      expect(q('[data-testid="imaging-drawer-actions"]')).toBeNull();
    });
  });

  describe('D1821: order, start, report, sign — end to end with stubs', () => {
    it('a new order is started, completed, reported and signed from the UI', async () => {
      setUp({ role: 'ROLE_DOCTOR', orders: [order()] });
      const confirm = spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
      // No report action before the study is performed.
      expect(q('[data-testid="imaging-author-o1"]')).toBeNull();

      // Start: the status alone travels (QA D1822: nothing else to erase).
      imaging.updateOrderStatus.and.callFake((_id, body) => {
        orders = [order({ status: body['status'] as ImagingOrderStatus })];
        return of(orders[0]);
      });
      q('[data-testid="imaging-start-o1"]')!.click();
      fixture.detectChanges();
      expect(imaging.updateOrderStatus).toHaveBeenCalledWith('o1', { status: 'IN_PROGRESS' });
      expect(toast.success).toHaveBeenCalledWith('IMAGING.STUDY_STARTED');

      // In progress: « Rédiger le compte rendu » is reachable.
      expect(q('[data-testid="imaging-start-o1"]')).toBeNull();
      expect(q('[data-testid="imaging-author-o1"]')).not.toBeNull();

      // Complete asks first (a resulted order is not reopened).
      q('[data-testid="imaging-complete-o1"]')!.click();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(confirm).toHaveBeenCalled();
      expect(imaging.updateOrderStatus).toHaveBeenCalledWith('o1', { status: 'COMPLETED' });
      expect(toast.success).toHaveBeenCalledWith('IMAGING.STUDY_COMPLETED');

      // Report.
      q('[data-testid="imaging-author-o1"]')!.click();
      fixture.detectChanges();
      expect(component.showReportModal()).toBeTrue();
      component.reportForm.findings = 'Pas de foyer.';
      component.reportForm.impression = 'Normal.';
      imaging.createReport.and.returnValue(of(report()));
      component.submitReport();
      expect(imaging.createReport).toHaveBeenCalledWith(
        jasmine.objectContaining({ imagingOrderId: 'o1', impression: 'Normal.' }),
      );
      expect(component.selectedReport()?.id).toBe('r1');

      // Sign.
      imaging.signReport.and.returnValue(of(report({ reportStatus: 'FINAL', signed: true })));
      await component.signReport(component.selectedReport()!);
      expect(imaging.signReport).toHaveBeenCalledWith('r1');
      expect(component.selectedReport()?.reportStatus).toBe('FINAL');
    });

    it('declining the confirm completes nothing', async () => {
      setUp({ role: 'ROLE_DOCTOR', orders: [order({ status: 'IN_PROGRESS' })] });
      spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);
      await component.completeStudy(orders[0]);
      expect(imaging.updateOrderStatus).not.toHaveBeenCalled();
    });

    it('a refused start says why and leaves the order as it was', () => {
      setUp({ role: 'ROLE_DOCTOR', orders: [order()] });
      imaging.updateOrderStatus.and.returnValue(
        throwError(() => ({ status: 403, error: { message: 'Refusé' } })),
      );
      component.startStudy(orders[0]);
      expect(toast.error).toHaveBeenCalled();
      expect(component.studyStatusSavingId()).toBeNull();
    });

    for (const outcome of ['success', 'error'] as const) {
      it(`disables every row during a status write and enables them after ${outcome}`, () => {
        setUp({
          role: 'ROLE_DOCTOR',
          orders: [order(), order({ id: 'o2', status: 'IN_PROGRESS' })],
        });
        const pending = new Subject<ImagingOrderResponse>();
        imaging.updateOrderStatus.and.returnValue(pending);
        q('[data-testid="imaging-start-o1"]')!.click();
        fixture.detectChanges();

        const start = q('[data-testid="imaging-start-o1"]') as HTMLButtonElement;
        const complete = q('[data-testid="imaging-complete-o2"]') as HTMLButtonElement;
        expect(start.disabled).toBeTrue();
        expect(complete.disabled).toBeTrue();
        complete.click();
        expect(imaging.updateOrderStatus).toHaveBeenCalledTimes(1);

        if (outcome === 'success') {
          pending.next(order({ status: 'IN_PROGRESS' }));
          pending.complete();
        } else {
          pending.error({ status: 403 });
        }
        fixture.detectChanges();
        expect((q('[data-testid="imaging-start-o1"]') as HTMLButtonElement).disabled).toBeFalse();
        expect(
          (q('[data-testid="imaging-complete-o2"]') as HTMLButtonElement).disabled,
        ).toBeFalse();
      });
    }

    it('preserves contrast details, accession and comparison when revising a report', () => {
      setUp({ role: 'ROLE_DOCTOR', orders: [order({ status: 'IN_PROGRESS' })] });
      const existing = report({
        contrastDetails: 'Iohexol 50 ml',
        accessionNumber: 'ACC-42',
        comparisonStudies: 'Prior chest study',
      });
      component.openEditReport(existing);
      imaging.updateReport.and.returnValue(of(existing));
      component.submitReport();
      expect(imaging.updateReport).toHaveBeenCalledWith(
        'r1',
        jasmine.objectContaining({
          contrastDetails: 'Iohexol 50 ml',
          accessionNumber: 'ACC-42',
          comparisonStudies: 'Prior chest study',
        }),
      );
    });
  });

  describe('D1820, D1823, D1826: the words', () => {
    it('D1820: the request drawer and the report drawer say "Définitif" for a signed report', () => {
      setUp({ role: 'ROLE_DOCTOR', orders: [order({ status: 'RESULTS_AVAILABLE' })], lang: 'fr' });
      imaging.getReportsForOrder.and.returnValue(
        of([report({ reportStatus: 'FINAL', signed: true })]),
      );
      component.viewDetail(orders[0]);
      fixture.detectChanges();
      const badge = q('[data-testid="imaging-report-r1"] .status-badge')!.textContent!.trim();
      expect(badge).toBe('Définitif');
      component.closeDetail();
      component.openReport(report({ reportStatus: 'FINAL', signed: true }));
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Définitif');
      expect((fixture.nativeElement as HTMLElement).textContent).not.toMatch(/\bFinal\b/);
    });

    it('D1826: the completed card is plural in French and Spanish', () => {
      setUp({ role: 'ROLE_DOCTOR', orders: [order()], lang: 'fr' });
      expect(q('[data-testid="imaging-card-completed"] .card-label')!.textContent!.trim()).toBe(
        'Terminées',
      );
      expect(es.IMAGING.COMPLETED_ORDERS).toBe('Completadas');
      expect(en.IMAGING.COMPLETED_ORDERS).toBe('Completed');
    });

    it('D1823: ES "Solicitado el" before a date, EN "Completed at"', () => {
      setUp({ role: 'ROLE_DOCTOR', orders: [order()], lang: 'es' });
      component.viewDetail(orders[0]);
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Solicitado el');
      expect(es.IMAGING.ORDERED_AT).not.toContain('a las');
      expect(en.IMAGING.COMPLETED_AT).toBe('Completed at');
    });
  });
});
