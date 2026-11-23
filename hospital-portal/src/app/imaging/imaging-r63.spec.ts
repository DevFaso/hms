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
  ImagingReadingContext,
  ImagingReportResponse,
  ImagingService,
} from '../services/imaging.service';
import { ImagingReadingService } from '../services/imaging-reading.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { PRINT_ROOT_CLASS } from '../shared/print/print-only';
import fr from '../../assets/i18n/fr.json';

/**
 * QA r63 imaging completion: D2306 (the « À signer » list, the order's link to
 * its report, « Rédiger » reopening the saved read), D2307 (the addendum on a
 * signed report, read with it; no dead-end cancel), the signer and the date,
 * print, and the radiologist's reading context.
 */
function order(overrides: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse {
  return {
    id: 'o1',
    patientId: 'p1',
    patientDisplayName: 'Salamata Ouedraogo',
    patientMrn: 'CHW6076',
    hospitalId: 'h1',
    modality: 'XRAY',
    studyType: 'Radiographie thorax',
    bodyRegion: 'Thorax',
    priority: 'ROUTINE',
    status: 'COMPLETED',
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
    patientId: 'p1',
    patientDisplayName: 'Salamata Ouedraogo',
    patientMrn: 'CHW6076',
    modality: 'XRAY',
    reportStatus: 'FINAL',
    reportVersion: 1,
    reportTitle: 'Radiographie thorax',
    signed: true,
    signedByName: 'Dr Inoussa Belem',
    signedAt: '2026-11-15T09:00:00',
    impression: 'Thorax normal.',
    criticalFinding: false,
    statusHistory: [],
    ...overrides,
  } as ImagingReportResponse;
}

const CONTEXT: ImagingReadingContext = {
  imagingOrderId: 'o1',
  patientId: 'p1',
  dateOfBirth: '1990-01-01',
  ageYears: 36,
  sex: 'FEMALE',
  pregnancy: { estimatedDueDate: '2027-02-01', recordedAt: '2026-10-01T10:00:00', ongoing: true },
  requiresPregnancyTest: true,
  creatinine: {
    testName: 'Créatinine',
    value: '9,5',
    unit: 'mg/L',
    resultDate: '2026-11-10T08:00:00',
    corrected: false,
  },
  priorStudies: [
    {
      imagingOrderId: 'o-prior',
      modality: 'XRAY',
      studyType: 'Radiographie thorax',
      orderedAt: '2026-10-01T08:00:00',
      status: 'RESULTS_AVAILABLE',
      reportId: 'r-prior',
      reportStatus: 'FINAL',
      impression: 'Ancien cliché normal.',
    },
  ],
};

describe('ImagingComponent — r63 imaging completion', () => {
  let fixture: ComponentFixture<ImagingComponent>;
  let component: ImagingComponent;
  let imaging: jasmine.SpyObj<ImagingService>;
  let reading: jasmine.SpyObj<ImagingReadingService>;

  beforeAll(() => registerLocaleData(localeFr));

  function setUp(roles: string[], opts: { reports?: ImagingReportResponse[] } = {}): void {
    imaging = jasmine.createSpyObj('ImagingService', [
      'getAllOrders',
      'getOrder',
      'getReport',
      'getReportsForOrder',
      'getReportsByHospital',
      'createReport',
    ]);
    imaging.getAllOrders.and.returnValue(of([order()]));
    imaging.getOrder.and.returnValue(of(order()));
    imaging.getReport.and.callFake((id: string) => of(report({ id })));
    imaging.getReportsForOrder.and.returnValue(of(opts.reports ?? []));
    imaging.getReportsByHospital.and.returnValue(of([report()]));
    reading = jasmine.createSpyObj('ImagingReadingService', ['toSign', 'readingContext']);
    reading.toSign.and.returnValue(
      of([report({ id: 'r-draft', signed: false, reportStatus: 'PRELIMINARY' })]),
    );
    reading.readingContext.and.returnValue(of(CONTEXT));
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
    const toast = jasmine.createSpyObj('ToastService', ['success', 'error', 'warning']);

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
        { provide: ToastService, useValue: toast },
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

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const q = (sel: string): HTMLElement | null => host().querySelector(sel);

  describe('D2306: the reads waiting for my signature', () => {
    it('offers « À signer » with its count, and lists my unsigned reads', () => {
      setUp(['ROLE_RADIOLOGIST']);
      component.setView('results');
      fixture.detectChanges();
      const tab = q('[data-testid="imaging-to-sign-tab"]')!;
      expect(tab.textContent).toContain('À signer');
      expect(tab.textContent).toContain('1');
      tab.click();
      fixture.detectChanges();
      expect(component.toSignOnly()).toBeTrue();
      expect(component.reports().map((r) => r.id)).toEqual(['r-draft']);
    });

    it('is not offered to a role that writes no report', () => {
      setUp(['ROLE_NURSE']);
      component.setView('results');
      fixture.detectChanges();
      expect(q('[data-testid="imaging-to-sign-tab"]')).toBeNull();
      expect(reading.toSign).not.toHaveBeenCalled();
    });

    it('« Rédiger » reopens the saved, unsigned read instead of a blank second report', () => {
      setUp(['ROLE_RADIOLOGIST'], {
        reports: [report({ id: 'r-draft', signed: false, reportStatus: 'PRELIMINARY' })],
      });
      component.authorForOrder(order());
      expect(component.showReportModal()).toBeTrue();
      expect(component.reportEditId()).toBe('r-draft');
    });

    it('the order drawer links each report to its drawer', () => {
      setUp(['ROLE_RADIOLOGIST'], { reports: [report()] });
      component.viewDetail(order());
      fixture.detectChanges();
      (q('[data-testid="imaging-open-report-r1"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(imaging.getReport).toHaveBeenCalledWith('r1');
      expect(component.selectedReport()?.id).toBe('r1');
    });
  });

  for (const status of ['ADDENDUM', 'CORRECTED', 'AMENDED'] as const) {
    it(`preserves the unsigned ${status} kind when reopening its revision form`, () => {
      setUp(['ROLE_RADIOLOGIST']);
      component.openEditReport(report({ signed: false, reportStatus: status }));
      expect(component.reportForm.reportStatus).toBe(status);
      expect(component.authorableStatuses).toEqual(['ADDENDUM', 'CORRECTED', 'AMENDED']);
      component.closeReportModal();
      component.openAuthorReport(order());
      expect(component.authorableStatuses).toEqual(['DRAFT', 'PRELIMINARY']);
    });
  }

  for (const status of ['DRAFT', 'PRELIMINARY'] as const) {
    it(`editing an unsigned ${status} read keeps revision kinds out of its picker`, () => {
      setUp(['ROLE_RADIOLOGIST']);
      component.openEditReport(report({ signed: false, reportStatus: status }));
      expect(component.reportForm.reportStatus).toBe(status);
      expect(component.authorableStatuses).toEqual(['DRAFT', 'PRELIMINARY']);
    });
  }

  it('shows the latest signed source when an addendum is opened from its study', () => {
    setUp(['ROLE_RADIOLOGIST'], {
      reports: [
        report({ id: 'first', reportVersion: 1, findings: 'First signed text' }),
        report({ id: 'latest', reportVersion: 2, findings: 'Latest signed text' }),
      ],
    });
    component.authorForOrder(order());
    fixture.detectChanges();
    expect(component.addendumSource()?.id).toBe('latest');
    expect(q('[data-testid="addendum-original-report"]')?.textContent).toContain(
      'Latest signed text',
    );
    expect(component.reportForm.reportStatus).toBe('ADDENDUM');
    expect(component.reportForm.findings).toBe('');
    expect(component.authorableStatuses).toEqual(['ADDENDUM', 'CORRECTED', 'AMENDED']);
  });

  describe('D2307: the addendum to a signed report', () => {
    for (const entryPoint of ['report', 'study'] as const) {
      for (const status of ['ADDENDUM', 'CORRECTED', 'AMENDED'] as const) {
        it(`offers and submits ${status} from the signed ${entryPoint} without editing its source`, async () => {
          setUp(['ROLE_RADIOLOGIST'], { reports: [report()] });
          imaging.createReport.and.returnValue(
            of(report({ id: 'revision', signed: false, reportStatus: status })),
          );
          if (entryPoint === 'report') component.openAddendum(report());
          else component.authorForOrder(order());
          fixture.detectChanges();
          const picker = q('#rep-status') as HTMLSelectElement;
          expect(Array.from(picker.options, (option) => option.value)).toEqual([
            'ADDENDUM',
            'CORRECTED',
            'AMENDED',
          ]);
          picker.value = status;
          picker.dispatchEvent(new Event('change'));
          await fixture.whenStable();
          component.reportForm.findings = 'Documented revision';
          component.submitReport();
          expect(imaging.createReport).toHaveBeenCalledWith(
            jasmine.objectContaining({ imagingOrderId: 'o1', reportStatus: status }),
          );
          expect(component.reportEditId()).toBeNull();
        });
      }
    }

    it('D2653: names the revision, shows its signed source and offers every revision kind', () => {
      setUp(['ROLE_RADIOLOGIST']);
      component.openAddendum(
        report({ signed: true, findings: 'Original findings', impression: 'Original conclusion' }),
      );
      fixture.detectChanges();
      expect(q('#imaging-dialog-2-title')?.textContent).toContain('Ajouter un addendum');
      expect(q('[data-testid="addendum-original-report"]')?.textContent).toContain(
        'Original findings',
      );
      expect(q('[data-testid="addendum-original-report"]')?.textContent).toContain(
        'Original conclusion',
      );
      expect(component.authorableStatuses).toEqual(['ADDENDUM', 'CORRECTED', 'AMENDED']);
      component.closeReportModal();
      component.openAuthorReport(order());
      expect(component.authorableStatuses).toEqual(['DRAFT', 'PRELIMINARY']);
    });
    it('« Rédiger » on a signed study opens an addendum, never an edit of the signed read', () => {
      setUp(['ROLE_RADIOLOGIST'], { reports: [report()] });
      component.authorForOrder(order());
      expect(component.reportEditId()).toBeNull();
      expect(component.reportForm.reportStatus).toBe('ADDENDUM');
    });

    it('the signed report offers « Ajouter un addendum », shows who signed and when', () => {
      setUp(['ROLE_RADIOLOGIST']);
      component.openReport(report());
      fixture.detectChanges();
      expect(q('[data-testid="imaging-add-addendum"]')?.textContent).toContain(
        'Ajouter un addendum',
      );
      expect(q('[data-testid="imaging-report-signed-by-on"]')?.textContent).toContain(
        'Signé par Dr Inoussa Belem le',
      );
      (q('[data-testid="imaging-add-addendum"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(component.showReportModal()).toBeTrue();
      expect(component.reportEditId()).toBeNull();
      expect(component.reportOrderId()).toBe('o1');
      expect(component.reportForm.reportStatus).toBe('ADDENDUM');
    });

    it('an administrator reads the signed report but is offered no addendum', () => {
      setUp(['ROLE_HOSPITAL_ADMIN']);
      component.openReport(report());
      fixture.detectChanges();
      expect(q('[data-testid="imaging-add-addendum"]')).toBeNull();
    });

    it('the addendum is read with the report it completes', () => {
      setUp(['ROLE_DOCTOR'], {
        reports: [
          report(),
          report({
            id: 'r2',
            reportStatus: 'ADDENDUM',
            reportVersion: 2,
            findings: 'Addendum : comparaison superposable.',
          }),
        ],
      });
      component.openReport(report());
      fixture.detectChanges();
      const versions = q('[data-testid="imaging-report-versions"]')!;
      expect(versions.textContent).toContain('Addendum : comparaison superposable.');
      expect(q('[data-testid="imaging-report-version-r1"]')).toBeNull();
    });

    it('a signed row offers no « Mettre à jour le statut » (the server refuses it)', () => {
      setUp(['ROLE_RADIOLOGIST']);
      component.setView('results');
      fixture.detectChanges();
      const statusButtons = Array.from(host().querySelectorAll('.action-link')).filter((b) =>
        (b.getAttribute('aria-label') ?? '').includes(fr.IMAGING.UPDATE_STATUS),
      );
      expect(statusButtons.length).toBe(0);
    });
  });

  it('prints the open report alone', () => {
    setUp(['ROLE_DOCTOR']);
    component.openReport(report());
    fixture.detectChanges();
    const seen: (string | null)[] = [];
    spyOn(window, 'print').and.callFake(() => {
      seen.push(q('.' + PRINT_ROOT_CLASS)?.getAttribute('data-testid') ?? null);
    });
    (q('[data-testid="imaging-report-print"]') as HTMLButtonElement).click();
    expect(window.print).toHaveBeenCalledTimes(1);
    expect(seen).toEqual(['imaging-report-panel']);
    window.dispatchEvent(new Event('afterprint'));
  });

  it('D3354: browser printing isolates the open report and clears isolation afterwards', () => {
    setUp(['ROLE_DOCTOR']);
    component.openReport(report());
    fixture.detectChanges();
    window.dispatchEvent(new Event('beforeprint'));
    expect(q('.' + PRINT_ROOT_CLASS)?.getAttribute('data-testid')).toBe('imaging-report-panel');
    expect(document.body.classList.contains('print-isolated')).toBeTrue();
    window.dispatchEvent(new Event('afterprint'));
    expect(q('.' + PRINT_ROOT_CLASS)).toBeNull();
    expect(document.body.classList.contains('print-isolated')).toBeFalse();
  });

  it('QA D2642: the open report says whom it is about and what was asked, on screen and on paper', () => {
    setUp(['ROLE_RADIOLOGIST']);
    component.openReport(
      report({
        hospitalName: 'CHU Yalgado',
        hospitalAddress: 'Avenue Kwame Nkrumah, Ouagadougou',
        patientDateOfBirth: '1949-05-01',
        patientGender: 'MALE',
        orderingProviderName: 'Dr Awa Sawadogo',
        clinicalQuestion: 'Toux persistante',
        performedAt: '2026-11-15T08:30:00',
      }),
    );
    fixture.detectChanges();
    const identity = q('[data-testid="print-identity"]')!.textContent ?? '';
    expect(identity).toContain('CHU Yalgado');
    expect(identity).toContain('Avenue Kwame Nkrumah');
    expect(identity).toContain('Salamata Ouedraogo');
    expect(identity).toContain('CHW6076');
    expect(identity).toContain('Dr Awa Sawadogo');
    expect(identity).toContain('1949');
    expect(q('[data-testid="imaging-report-drawer-prescriber"]')?.textContent).toContain(
      'Dr Awa Sawadogo',
    );
    expect(q('[data-testid="imaging-report-drawer-clinical-question"]')?.textContent).toContain(
      'Toux persistante',
    );
    expect(q('[data-testid="imaging-report-drawer-dob"]')?.textContent).toContain('1949');
    // The identity block is inside the element printed alone.
    expect(q('[data-testid="imaging-report-panel"] [data-testid="print-identity"]')).not.toBeNull();
  });

  it('QA D3153: on paper the title and the identity are printed once, in the print header', () => {
    setUp(['ROLE_RADIOLOGIST']);
    component.openReport(
      report({
        patientDateOfBirth: '1949-05-01',
        orderingProviderName: 'Dr Awa Sawadogo',
        clinicalQuestion: 'Toux persistante',
      }),
    );
    fixture.detectChanges();
    expect(q('[data-testid="imaging-report-panel"] [data-testid="print-identity"]')).not.toBeNull();
    // The drawer's own title and identity fields stay on screen and off paper.
    expect(q('#imaging-panel-1-title')!.classList).toContain('no-print');
    for (const id of ['patient', 'mrn', 'dob', 'prescriber', 'exam-date']) {
      expect(q(`[data-testid="imaging-report-drawer-${id}"]`)!.classList)
        .withContext(id)
        .toContain('no-print');
    }
    // What the header does not carry still prints from the drawer.
    expect(q('[data-testid="imaging-report-drawer-clinical-question"]')!.classList).not.toContain(
      'no-print',
    );
  });

  describe('the reading context beside the order', () => {
    it('shows age, sex, the recorded pregnancy, the creatinine and the prior study', () => {
      setUp(['ROLE_RADIOLOGIST']);
      component.viewDetail(order());
      fixture.detectChanges();
      expect(reading.readingContext).toHaveBeenCalledWith('o1');
      const ctx = q('[data-testid="imaging-reading-context"]')!.textContent ?? '';
      expect(ctx).toContain('36 ans');
      expect(ctx).toContain('Grossesse enregistrée');
      expect(ctx).toContain('Test de grossesse demandé');
      expect(ctx).toContain('9,5 mg/L');
      expect(ctx).toContain('Ancien cliché normal.');
      (q('[data-testid="imaging-context-open-prior-o-prior"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(imaging.getReport).toHaveBeenCalledWith('r-prior');
    });

    it("QA D2640: shows the laboratory's grade and range beside the creatinine", () => {
      setUp(['ROLE_RADIOLOGIST']);
      reading.readingContext.and.returnValue(
        of({
          ...CONTEXT,
          creatinine: {
            ...CONTEXT.creatinine!,
            value: '118',
            unit: 'µmol/L',
            severityFlag: 'HIGH',
            referenceRange: '62 - 106 µmol/L',
          },
        }),
      );
      component.viewDetail(order());
      fixture.detectChanges();
      const flag = q('[data-testid="imaging-context-creatinine-flag"]');
      expect(flag).not.toBeNull();
      expect(flag!.textContent!.trim().length).toBeGreaterThan(0);
      expect(q('[data-testid="imaging-context-creatinine-range"]')?.textContent).toContain(
        '62 – 106 µmol/L',
      );
    });

    it('is not shown to an administrator (labs and pregnancy are clinical data)', () => {
      setUp(['ROLE_HOSPITAL_ADMIN']);
      component.viewDetail(order());
      fixture.detectChanges();
      expect(q('[data-testid="imaging-reading-context"]')).toBeNull();
      expect(reading.readingContext).not.toHaveBeenCalled();
    });
  });
});
