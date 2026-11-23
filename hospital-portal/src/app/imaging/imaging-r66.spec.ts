import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ImagingComponent } from './imaging';
import {
  ImagingOrderResponse,
  ImagingReportResponse,
  ImagingService,
} from '../services/imaging.service';
import { ImagingReadingService } from '../services/imaging-reading.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { resetStoredLangForTests } from '../shared/i18n/app-locale';
import fr from '../../assets/i18n/fr.json';

/**
 * QA r66 on the imaging page:
 * D2912 (the signed report, on screen and on paper, shows who performed the exam and carries the
 * contrast and the dose recorded at the exam), D2913 (the dose in the reader's number format),
 * D2914 (a technician is not offered what the server refuses them).
 */
function order(overrides: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse {
  return {
    id: 'o1',
    patientId: 'p1',
    patientDisplayName: 'Patient Test 04',
    patientMrn: 'CHW0004',
    hospitalId: 'h1',
    modality: 'XRAY',
    studyType: 'Radiographie thorax',
    bodyRegion: 'Thorax',
    priority: 'ROUTINE',
    status: 'ORDERED',
    laterality: 'NOT_APPLICABLE',
    orderedAt: '2026-11-20T08:00:00',
    completedAt: null,
    ...overrides,
  } as ImagingOrderResponse;
}

function report(overrides: Partial<ImagingReportResponse> = {}): ImagingReportResponse {
  return {
    id: 'r1',
    imagingOrderId: 'o1',
    patientId: 'p1',
    patientDisplayName: 'Patient Test 04',
    patientMrn: 'CHW0004',
    reportStatus: 'FINAL',
    reportVersion: 1,
    reportTitle: 'Radiographie thorax',
    signed: true,
    signedByName: 'Test Radiologue 1',
    interpretingProviderName: 'Test Radiologue 1',
    performedByName: 'Test Manipulateur 3',
    signedAt: '2026-11-20T10:00:00',
    impression: 'Thorax normal.',
    criticalFinding: false,
    statusHistory: [],
    ...overrides,
  } as ImagingReportResponse;
}

describe('ImagingComponent — r66 (D2912, D2913, D2914)', () => {
  let fixture: ComponentFixture<ImagingComponent>;
  let component: ImagingComponent;

  beforeAll(() => registerLocaleData(localeFr));

  afterEach(() => {
    localStorage.removeItem('lang');
    resetStoredLangForTests();
  });

  function setUp(
    roles: string[],
    opts: { orders?: ImagingOrderResponse[]; reports?: ImagingReportResponse[] } = {},
  ): void {
    const imaging = jasmine.createSpyObj('ImagingService', [
      'getAllOrders',
      'getOrder',
      'getReport',
      'getReportsForOrder',
      'getReportsByHospital',
    ]);
    imaging.getAllOrders.and.returnValue(of(opts.orders ?? [order()]));
    imaging.getOrder.and.returnValue(of(order()));
    imaging.getReport.and.callFake((id: string) => of(report({ id })));
    imaging.getReportsForOrder.and.returnValue(of(opts.reports ?? []));
    imaging.getReportsByHospital.and.returnValue(of([report()]));
    const reading = jasmine.createSpyObj('ImagingReadingService', ['toSign', 'readingContext']);
    reading.toSign.and.returnValue(of([]));
    reading.readingContext.and.returnValue(of(null));
    const hospital = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospital.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'CHU' } as HospitalResponse),
    );
    hospital.list.and.returnValue(of([{ id: 'h1', name: 'CHU' } as HospitalResponse]));
    const auth = jasmine.createSpyObj('AuthService', ['getHospitalId', 'getUserProfile']);
    auth.getHospitalId.and.returnValue('h1');
    auth.getUserProfile.and.returnValue({ id: 'u-me' } as never);
    const patients = jasmine.createSpyObj('PatientService', ['list']);
    patients.list.and.returnValue(of([]));

    TestBed.configureTestingModule({
      imports: [ImagingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'fr' },
        { provide: ImagingService, useValue: imaging },
        { provide: ImagingReadingService, useValue: reading },
        { provide: HospitalService, useValue: hospital },
        { provide: PatientService, useValue: patients },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'warning']),
        },
        { provide: AuthService, useValue: auth },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => false,
            globalView: () => false,
            selectedHospitalId: () => 'h1',
            activeHospitalId: 'h1',
            hasAnyActiveRole: (wanted: string[]) => roles.some((r) => wanted.includes(r)),
            // V356: the « provisoire » label reads the acting hospital; none here, the screen's wording stays.
            effectiveHospitalIdForRequest: () => null,
          } as unknown as RoleContextService,
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    fixture = TestBed.createComponent(ImagingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  const q = (testid: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testid}"]`);

  describe('D2912: the signed report shows the exam as it was performed', () => {
    it('names who performed the exam beside who interpreted it, inside the printed panel', () => {
      setUp(['ROLE_RADIOLOGIST']);
      component.openReport(report());
      fixture.detectChanges();

      const performedBy = q('imaging-report-performed-by');
      expect(performedBy?.textContent).toContain('Réalisé par');
      expect(performedBy?.textContent).toContain('Test Manipulateur 3');
      // What is printed is the report panel: the line is inside it.
      expect(q('imaging-report-panel')?.contains(performedBy)).toBeTrue();
      expect(q('imaging-report-panel')?.textContent).toContain('Test Radiologue 1');
    });

    it('falls back on the performer stamped on the order, and says so when nobody is recorded', () => {
      setUp(['ROLE_DOCTOR']);
      component.openReport(
        report({ performedByName: null as never, examPerformedByName: 'Awa Kone' }),
      );
      fixture.detectChanges();
      expect(q('imaging-report-performed-by')?.textContent).toContain('Awa Kone');

      component.openReport(report({ performedByName: null as never }));
      fixture.detectChanges();
      expect(q('imaging-report-performed-by')?.textContent).toContain('—');
    });

    it('carries the contrast and the dose recorded at the exam, as recorded data with the report', () => {
      setUp(['ROLE_RADIOLOGIST']);
      component.openReport(
        report({
          examContrastAdministered: true,
          examContrastDetails: 'Iohexol 80 mL IV',
          examRadiationDoseMgy: 0.15,
          // The report's own fields are what its author submitted: nothing here.
          contrastAdministered: null,
        }),
      );
      fixture.detectChanges();

      const block = q('imaging-report-exam-record');
      expect(block?.textContent).toContain("Enregistré lors de l'examen");
      expect(q('imaging-report-exam-contrast')?.textContent).toContain('Oui');
      expect(q('imaging-report-exam-contrast')?.textContent).toContain('Iohexol 80 mL IV');
      // D2913: French number format.
      expect(q('imaging-report-exam-dose')?.textContent).toContain('0,15 mGy');
      expect(q('imaging-report-panel')?.contains(block)).toBeTrue();
    });

    it('a recorded « no contrast » is shown; a study with no exam record shows no block', () => {
      setUp(['ROLE_DOCTOR']);
      component.openReport(report({ examContrastAdministered: false }));
      fixture.detectChanges();
      expect(q('imaging-report-exam-contrast')?.textContent).toContain('Non');
      expect(q('imaging-report-exam-dose')).toBeNull();

      component.openReport(report());
      fixture.detectChanges();
      expect(q('imaging-report-exam-record')).toBeNull();
    });

    it('D2913: the dose reads in the English format for an English reader', () => {
      localStorage.setItem('lang', 'en');
      resetStoredLangForTests();
      setUp(['ROLE_RADIOLOGIST']);
      component.openReport(report({ examRadiationDoseMgy: 0.15 }));
      fixture.detectChanges();
      expect(q('imaging-report-exam-dose')?.textContent).toContain('0.15 mGy');
    });
  });

  describe('D2914: a technician is offered nothing the server refuses', () => {
    const mine = order({ id: 'mine', status: 'RESULTS_AVAILABLE', performedByUserId: 'u-me' });
    const theirs = order({ id: 'theirs', status: 'RESULTS_AVAILABLE', performedByUserId: 'u-x' });

    it('« Voir le compte rendu » only on a resulted exam they performed', () => {
      setUp(['ROLE_IMAGING_TECHNICIAN'], {
        orders: [
          mine,
          theirs,
          order({ id: 'done', status: 'COMPLETED', performedByUserId: 'u-me' }),
        ],
      });
      expect(q('imaging-view-report-mine')).not.toBeNull();
      // X2: an exam somebody else performed, with a signed report: the click answered nothing.
      expect(q('imaging-view-report-theirs')).toBeNull();
      // Their own exam, not yet signed: there is no released report to read.
      expect(q('imaging-view-report-done')).toBeNull();
      expect(component.canViewReportOfOrder(order({ status: 'RESULTS_AVAILABLE' }))).toBeFalse();
    });

    it('everyone else keeps the report link on a completed or resulted study', () => {
      for (const role of ['ROLE_DOCTOR', 'ROLE_RADIOLOGIST', 'ROLE_NURSE', 'ROLE_HOSPITAL_ADMIN']) {
        TestBed.resetTestingModule();
        setUp([role], { orders: [theirs, order({ id: 'done', status: 'COMPLETED' })] });
        expect(q('imaging-view-report-theirs')).withContext(role).not.toBeNull();
        expect(q('imaging-view-report-done')).withContext(role).not.toBeNull();
        expect(component.canViewReportOfOrder(order({ status: 'ORDERED' })))
          .withContext(role)
          .toBeFalse();
      }
    });

    it('no image drop zone on a study that has results, even when they cannot read its report', () => {
      // The technician reads no report of an exam somebody else performed: the list is empty.
      setUp(['ROLE_IMAGING_TECHNICIAN'], { orders: [theirs], reports: [] });
      expect(component.canAddStudyImages(theirs)).toBeFalse();
      expect(component.canAddStudyImages(order({ status: 'IN_PROGRESS' }))).toBeTrue();
      expect(component.canAddStudyImages(order({ status: 'COMPLETED' }))).toBeTrue();
    });

    it("no « Démarrer l'examen » on a routine study that waits for its pre-authorisation number", () => {
      // X7: start answered 400 « enregistrez son numéro avant de démarrer », and a technician cannot record it.
      const waiting = order({ id: 'x7', requiresAuthorization: true, authorizationNumber: null });
      setUp(['ROLE_IMAGING_TECHNICIAN'], { orders: [waiting] });
      expect(component.canStartStudy(waiting)).toBeFalse();
      expect(q('imaging-start-x7')).toBeNull();

      // The drawer says why, and who records the number.
      component.selectedOrder.set(waiting);
      fixture.detectChanges();
      expect(q('imaging-drawer-start')).toBeNull();
      expect(q('imaging-technician-awaits-preauth')?.textContent).toContain(
        "un médecin ou un radiologue l'enregistre",
      );

      // Numbered, urgent or STAT (provisional rule 1): the exam starts.
      expect(component.canStartStudy({ ...waiting, authorizationNumber: 'PA-1' })).toBeTrue();
      expect(component.canStartStudy({ ...waiting, priority: 'URGENT' })).toBeTrue();
      expect(component.canStartStudy({ ...waiting, priority: 'STAT' })).toBeTrue();
      expect(component.canStartStudy(order())).toBeTrue();
    });

    it('a doctor or a radiologist, who records the number, is still offered the start', () => {
      const waiting = order({ id: 'x7', requiresAuthorization: true, authorizationNumber: null });
      for (const role of ['ROLE_DOCTOR', 'ROLE_RADIOLOGIST']) {
        TestBed.resetTestingModule();
        setUp([role], { orders: [waiting] });
        expect(component.canStartStudy(waiting)).withContext(role).toBeTrue();
        expect(component.technicianAwaitsAuthorizationNumber(waiting))
          .withContext(role)
          .toBeFalse();
      }
    });
  });
});
