import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';

import { ImagingComponent } from './imaging';
import {
  ImagingDuplicateMatch,
  ImagingService,
  ImagingOrderResponse,
  ImagingReportResponse,
} from '../services/imaging.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import en from '../../assets/i18n/en.json';
import es from '../../assets/i18n/es.json';
import fr from '../../assets/i18n/fr.json';

/**
 * QA r53 imaging (D1921 to D1923, D1926): the duplicate warning, the order's
 * contrast, schedule and pre-authorisation, whose report it is, and the
 * Spanish wording.
 */
function order(overrides: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse {
  return {
    id: 'o1',
    patientId: 'p1',
    patientDisplayName: 'Salamata Ouedraogo',
    patientMrn: 'CHW6076',
    hospitalId: 'h1',
    modality: 'XRAY',
    studyType: 'Radiographie bassin',
    bodyRegion: 'Bassin',
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
    patientId: 'p1',
    patientDisplayName: 'Salamata Ouedraogo',
    patientMrn: 'CHW6076',
    modality: 'XRAY',
    reportStatus: 'FINAL',
    reportTitle: 'Radiographie bassin UI',
    signed: true,
    impression: 'Normal.',
    criticalFinding: false,
    statusHistory: [],
    ...overrides,
  } as ImagingReportResponse;
}

const MATCH: ImagingDuplicateMatch = {
  orderId: 'o-earlier',
  modality: 'XRAY',
  bodyRegion: 'Bassin',
  studyType: 'Radiographie bassin',
  status: 'ORDERED',
  orderedAt: '2026-11-14T13:00:00',
};

describe('ImagingComponent — r53 imaging', () => {
  let fixture: ComponentFixture<ImagingComponent>;
  let component: ImagingComponent;
  let imaging: jasmine.SpyObj<ImagingService>;
  let toast: jasmine.SpyObj<ToastService>;

  beforeAll(() => registerLocaleData(localeFr));

  function setUp(
    roles: string[],
    opts: { lang?: 'fr' | 'es' | 'en'; orders?: ImagingOrderResponse[]; link?: string } = {},
  ): void {
    imaging = jasmine.createSpyObj('ImagingService', [
      'getAllOrders',
      'getOrder',
      'createOrder',
      'updateOrder',
      'checkDuplicates',
      'getReportsForOrder',
      'getReportsByHospital',
      'recordAuthorization',
    ]);
    const list = opts.orders ?? [order()];
    imaging.getAllOrders.and.returnValue(of(list));
    imaging.getOrder.and.callFake((id: string) =>
      of(list.find((o) => o.id === id) as ImagingOrderResponse),
    );
    imaging.getReportsForOrder.and.returnValue(of([]));
    imaging.getReportsByHospital.and.returnValue(of([report()]));
    imaging.checkDuplicates.and.returnValue(of([]));
    imaging.createOrder.and.returnValue(of(order({ id: 'o-new' })));
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
    toast = jasmine.createSpyObj('ToastService', ['success', 'error', 'warning']);

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
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap(opts.link ? { order: opts.link } : {}),
            },
          },
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
    const lang = opts.lang ?? 'fr';
    translate.setTranslation(lang, { fr, es, en }[lang]);
    translate.use(lang);
    fixture = TestBed.createComponent(ImagingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  const q = (sel: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(sel);

  function fillNewOrder(): void {
    component.openCreate();
    component.onPatientPicked({ id: 'p1', firstName: 'Salamata', lastName: 'Ouedraogo' } as never);
    component.form.studyType = 'Radiographie bassin';
    component.form.bodyRegion = 'Bassin';
    component.form.hospitalId = 'h1';
    fixture.detectChanges();
  }

  describe('D1921: the duplicate warning before the order is sent', () => {
    it('D1971: Escape asks visibly before discarding an Other note and keeps it until confirmed', fakeAsync(() => {
      setUp(['ROLE_DOCTOR']);
      imaging.checkDuplicates.and.returnValue(of([MATCH]));
      fillNewOrder();
      component.submitForm();
      fixture.detectChanges();
      tick(0);
      (q('[data-testid="imaging-duplicate-reason-OTHER"]') as HTMLInputElement).click();
      fixture.detectChanges();
      const note = q('[data-testid="imaging-duplicate-note"]') as HTMLTextAreaElement;
      note.focus();
      note.value = 'Clinical reason typed by the doctor';
      note.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      note.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      fixture.detectChanges();
      expect(q('[data-testid="inline-discard-ask"]')).not.toBeNull();
      expect(component.overrideNote()).toBe('Clinical reason typed by the doctor');
      expect(component.showModal()).toBeTrue();
      (q('[data-testid="inline-keep"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(q('[data-testid="inline-discard-ask"]')).toBeNull();
      expect(note.value).toBe('Clinical reason typed by the doctor');
      note.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      fixture.detectChanges();
      (q('[data-testid="inline-discard"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      tick(0);
      expect(q('[data-testid="imaging-duplicate-warning"]')).toBeNull();
      expect(component.showModal()).toBeTrue();
      expect(imaging.createOrder).not.toHaveBeenCalled();
      fixture.destroy();
      tick(0);
    }));

    it('asks the scoped preview, shows the recent order, and sends nothing until "order anyway"', () => {
      setUp(['ROLE_DOCTOR']);
      imaging.checkDuplicates.and.returnValue(of([MATCH]));
      fillNewOrder();

      component.submitForm();
      fixture.detectChanges();

      expect(imaging.checkDuplicates).toHaveBeenCalledOnceWith('p1', 'XRAY', 'Bassin');
      expect(imaging.createOrder).not.toHaveBeenCalled();
      const warning = q('[data-testid="imaging-duplicate-warning"]')!;
      expect(warning.textContent).toContain('Demande possiblement en double');
      expect(warning.textContent).toContain('Radiographie bassin');
      expect(warning.textContent).toContain('Revenir à la demande');

      // Provisional rule 2 (2026-11-15): « Commander quand même » names its reason.
      (q('[data-testid="imaging-duplicate-reason-CLINICAL_CHANGE"]') as HTMLInputElement).click();
      (q('[data-testid="imaging-duplicate-order-anyway"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(imaging.createOrder).toHaveBeenCalledTimes(1);
      // Acknowledged: no second warning after the save.
      expect(toast.warning).not.toHaveBeenCalled();
      expect(component.showModal()).toBeFalse();
    });

    it('"back" returns to the form without ordering', () => {
      setUp(['ROLE_DOCTOR']);
      imaging.checkDuplicates.and.returnValue(of([MATCH]));
      fillNewOrder();
      component.submitForm();
      component.dismissDuplicateWarning();
      fixture.detectChanges();
      expect(q('[data-testid="imaging-duplicate-warning"]')).toBeNull();
      expect(imaging.createOrder).not.toHaveBeenCalled();
      expect(component.showModal()).toBeTrue();
    });

    it('a changed form is asked again rather than acknowledged', () => {
      setUp(['ROLE_DOCTOR']);
      imaging.checkDuplicates.and.returnValue(of([MATCH]));
      fillNewOrder();
      component.submitForm();
      component.form.bodyRegion = 'Thorax';
      imaging.checkDuplicates.and.returnValue(of([]));
      component.orderAnyway();
      expect(imaging.checkDuplicates).toHaveBeenCalledTimes(2);
      expect(imaging.checkDuplicates.calls.mostRecent().args).toEqual(['p1', 'XRAY', 'Thorax']);
      expect(imaging.createOrder).toHaveBeenCalledTimes(1);
    });

    it('Rex #1169: closing during a pending preview cancels it and never blocks the next order', () => {
      setUp(['ROLE_DOCTOR']);
      const pending = new Subject<ImagingDuplicateMatch[]>();
      imaging.checkDuplicates.and.returnValue(pending);
      fillNewOrder();
      component.submitForm();
      expect(component.saving()).toBeTrue();

      component.closeModal();
      expect(component.saving()).toBeFalse();
      expect(pending.observed).toBeFalse();
      // A late answer to the cancelled preview places nothing.
      pending.next([]);
      expect(imaging.createOrder).not.toHaveBeenCalled();

      // The next order goes through.
      imaging.checkDuplicates.and.returnValue(of([]));
      fillNewOrder();
      component.submitForm();
      expect(imaging.createOrder).toHaveBeenCalledTimes(1);
    });

    it('Rex #1169: a preview pending when the page is left never places the order', () => {
      setUp(['ROLE_DOCTOR']);
      const pending = new Subject<ImagingDuplicateMatch[]>();
      imaging.checkDuplicates.and.returnValue(pending);
      fillNewOrder();
      component.submitForm();

      fixture.destroy();
      expect(pending.observed).toBeFalse();
      pending.next([]);
      expect(imaging.createOrder).not.toHaveBeenCalled();
    });

    it('Rex #1169: a write that answers after its dialog closed never closes or toasts over a new one', () => {
      setUp(['ROLE_DOCTOR']);
      const write = new Subject<ImagingOrderResponse>();
      imaging.createOrder.and.returnValue(write);
      fillNewOrder();
      component.submitForm();
      component.closeModal();
      component.openCreate();
      write.next(order({ id: 'o-new' }));
      write.complete();
      expect(component.showModal()).toBeTrue();
      expect(toast.success).not.toHaveBeenCalled();
      expect(component.saving()).toBeFalse();
    });

    it('a failed preview never blocks the order; a duplicate the server found is then told', () => {
      setUp(['ROLE_DOCTOR']);
      imaging.checkDuplicates.and.returnValue(throwError(() => ({ status: 500 })));
      imaging.createOrder.and.returnValue(
        of(order({ id: 'o-new', duplicateOfRecentOrder: true, duplicateReferenceOrderId: 'o1' })),
      );
      fillNewOrder();
      component.submitForm();
      expect(imaging.createOrder).toHaveBeenCalledTimes(1);
      expect(toast.warning).toHaveBeenCalledOnceWith(
        (fr as { IMAGING: { DUPLICATE: { PLACED_ANYWAY: string } } }).IMAGING.DUPLICATE
          .PLACED_ANYWAY,
      );
    });

    it('QA D3903: a duplicate the server refused without a reason is shown with the reasons', () => {
      setUp(['ROLE_DOCTOR']);
      // The preview found nothing (an order placed since); the server finds it and asks for a reason.
      imaging.checkDuplicates.and.returnValues(of([]), of([MATCH]));
      imaging.createOrder.and.returnValue(
        throwError(() => ({
          status: 400,
          error: {
            message: 'Choisissez pourquoi…',
            fieldErrors: { duplicateOverrideReason: 'Choisissez pourquoi…' },
          },
        })),
      );
      fillNewOrder();
      component.submitForm();
      fixture.detectChanges();

      expect(imaging.createOrder).toHaveBeenCalledTimes(1);
      expect(imaging.checkDuplicates).toHaveBeenCalledTimes(2);
      expect(component.duplicateMatches()).toEqual([MATCH]);
      expect(component.showModal()).toBeTrue();
      expect(q('[data-testid="imaging-duplicate-warning"]')).not.toBeNull();
    });

    it('an edit is not previewed', () => {
      setUp(['ROLE_DOCTOR']);
      imaging.updateOrder.and.returnValue(of(order()));
      component.openEdit(order());
      component.submitForm();
      expect(imaging.checkDuplicates).not.toHaveBeenCalled();
      expect(imaging.updateOrder).toHaveBeenCalledTimes(1);
    });

    it('the drawer of a flagged order says so and names the earlier order', () => {
      const earlier = order({ id: 'o1', studyType: 'Radiographie bassin' });
      const flagged = order({
        id: 'o2',
        duplicateOfRecentOrder: true,
        duplicateReferenceOrderId: 'o1',
      });
      setUp(['ROLE_DOCTOR'], { orders: [earlier, flagged] });
      component.viewDetail(flagged);
      fixture.detectChanges();
      const notice = q('[data-testid="imaging-order-duplicate"]')!;
      expect(notice.textContent).toContain("Doublon possible d'une demande récente");
      expect(notice.textContent).toContain('Radiographie bassin');
    });
  });

  describe('D1922: contrast, allergy, schedule, location and pre-authorisation', () => {
    it('the form records them and sends them', fakeAsync(() => {
      setUp(['ROLE_DOCTOR']);
      fillNewOrder();
      component.form.modality = 'CT';
      component.form.contrastRequired = true;
      component.form.contrastType = '  iodé IV ';
      component.form.hasContrastAllergy = true;
      component.form.contrastAllergyDetails = 'Urticaire après injection iodée';
      component.form.scheduledDate = '2099-11-20';
      component.form.scheduledTime = '10:00';
      component.form.appointmentLocation = 'Salle 2';
      component.form.requiresAuthorization = true;
      component.form.authorizationNumber = 'PA-R53-IM-01';
      fixture.detectChanges();
      tick();
      fixture.detectChanges();
      for (const id of [
        'img-contrastType',
        'img-contrastAllergy',
        'img-scheduledDate',
        'img-scheduledTime',
        'img-location',
        'img-authNumber',
      ]) {
        expect(q(`#${id}`))
          .withContext(id)
          .not.toBeNull();
        expect(q(`label[for="${id}"]`)?.textContent?.trim())
          .withContext(id)
          .toBeTruthy();
      }

      component.submitForm();

      const payload = imaging.createOrder.calls.mostRecent().args[0];
      expect(payload).toEqual(
        jasmine.objectContaining({
          contrastRequired: true,
          contrastType: 'iodé IV',
          hasContrastAllergy: true,
          contrastAllergyDetails: 'Urticaire après injection iodée',
          scheduledDate: '2099-11-20',
          scheduledTime: '10:00',
          appointmentLocation: 'Salle 2',
          requiresAuthorization: true,
          authorizationNumber: 'PA-R53-IM-01',
        }),
      );
    }));

    it('a detail only stands with its box ticked', () => {
      setUp(['ROLE_DOCTOR']);
      fillNewOrder();
      component.form.contrastType = 'iodé';
      component.form.contrastAllergyDetails = 'urticaire';
      component.form.authorizationNumber = 'PA-1';
      component.submitForm();
      const payload = imaging.createOrder.calls.mostRecent().args[0];
      expect(payload.contrastType).toBeUndefined();
      expect(payload.contrastAllergyDetails).toBeUndefined();
      expect(payload.authorizationNumber).toBeUndefined();
    });

    it('a time with no day, or a past day, is refused before any request', () => {
      setUp(['ROLE_DOCTOR']);
      fillNewOrder();
      component.form.scheduledTime = '10:00';
      component.submitForm();
      expect(component.formError()).toBe("Indiquez la date de l'examen pour cette heure.");
      component.form.scheduledDate = '2001-01-01';
      component.submitForm();
      expect(component.formError()).toBe("La date de l'examen ne peut pas être dans le passé.");
      expect(imaging.checkDuplicates).not.toHaveBeenCalled();
      expect(imaging.createOrder).not.toHaveBeenCalled();
    });

    it('an edit loads them back', () => {
      setUp(['ROLE_DOCTOR']);
      component.openEdit(
        order({
          contrastRequired: true,
          contrastType: 'iodé',
          hasContrastAllergy: true,
          contrastAllergyDetails: 'Urticaire',
          scheduledDate: '2099-11-20',
          scheduledTime: '10:00',
          appointmentLocation: 'Salle 2',
          requiresAuthorization: true,
          authorizationNumber: 'PA-1',
        }),
      );
      expect(component.form).toEqual(
        jasmine.objectContaining({
          contrastRequired: true,
          contrastType: 'iodé',
          hasContrastAllergy: true,
          contrastAllergyDetails: 'Urticaire',
          scheduledDate: '2099-11-20',
          scheduledTime: '10:00',
          appointmentLocation: 'Salle 2',
          requiresAuthorization: true,
          authorizationNumber: 'PA-1',
        }),
      );
    });

    for (const roles of [['ROLE_DOCTOR'], ['ROLE_RADIOLOGIST'], ['ROLE_HOSPITAL_ADMIN']]) {
      it(`${roles[0]}: the drawer shows the allergy, the contrast, where, and a missing pre-authorisation number`, () => {
        const o1 = order({
          modality: 'CT',
          contrastRequired: true,
          contrastType: 'iodé IV',
          hasContrastAllergy: true,
          contrastAllergyDetails: 'Urticaire après injection iodée',
          appointmentLocation: 'Salle 2',
          workflowNotes: 'À jeun',
          requiresAuthorization: true,
          authorizationNumber: null,
        });
        setUp(roles, { orders: [o1] });
        component.viewDetail(o1);
        fixture.detectChanges();
        expect(q('[data-testid="imaging-order-allergy"]')!.textContent).toContain(
          'Urticaire après injection iodée',
        );
        expect(q('[data-testid="imaging-order-contrast"]')!.textContent).toContain('iodé IV');
        expect(q('[data-testid="imaging-order-location"]')!.textContent).toContain('Salle 2');
        expect(q('[data-testid="imaging-order-workflow-notes"]')!.textContent).toContain('À jeun');
        expect(q('[data-testid="imaging-order-authorization"]')!.textContent).toContain(
          'numéro pas encore enregistré',
        );
      });
    }
  });

  describe('D1923: every view of a report names its patient', () => {
    for (const roles of [['ROLE_DOCTOR'], ['ROLE_RADIOLOGIST'], ['ROLE_HOSPITAL_ADMIN']]) {
      it(`${roles[0]}: the results list and the report drawer show the name and the MRN`, () => {
        setUp(roles);
        component.setView('results');
        fixture.detectChanges();
        expect(q('[data-testid="imaging-report-patient-header"]')).not.toBeNull();
        const cell = q('[data-testid="imaging-report-patient"]')!;
        expect(cell.textContent).toContain('Salamata Ouedraogo');
        expect(cell.textContent).toContain('CHW6076');

        component.openReport(report());
        fixture.detectChanges();
        expect(q('[data-testid="imaging-report-drawer-patient"]')!.textContent).toContain(
          'Salamata Ouedraogo',
        );
        expect(q('[data-testid="imaging-report-drawer-mrn"]')!.textContent).toContain('CHW6076');
      });
    }

    it('the authoring form names the patient it writes for', () => {
      setUp(['ROLE_DOCTOR']);
      component.openEditReport(report({ signed: false, reportStatus: 'PRELIMINARY' }));
      fixture.detectChanges();
      const line = q('[data-testid="imaging-report-form-patient"]')!.textContent!;
      expect(line).toContain('Salamata Ouedraogo');
      expect(line).toContain('CHW6076');
      component.closeReportModal();
      component.openAuthorReport(order({ status: 'IN_PROGRESS' }));
      fixture.detectChanges();
      expect(q('[data-testid="imaging-report-form-patient"]')!.textContent).toContain('CHW6076');
    });
  });

  describe("D1924: the chart's link lands on the order and its reports", () => {
    it('?order=<id> opens that order’s drawer, for a nurse too', () => {
      setUp(['ROLE_NURSE'], { link: 'o1', orders: [order({ status: 'RESULTS_AVAILABLE' })] });
      expect(imaging.getOrder).toHaveBeenCalledOnceWith('o1');
      expect(component.selectedOrder()?.id).toBe('o1');
      expect(imaging.getReportsForOrder).toHaveBeenCalledWith('o1');
    });

    it('without a link nothing is opened', () => {
      setUp(['ROLE_DOCTOR']);
      expect(imaging.getOrder).not.toHaveBeenCalled();
      expect(component.selectedOrder()).toBeNull();
    });
  });

  describe('D1926: Spanish says « Solicitud » for an imaging order', () => {
    it('no Imagerie text says « orden »', () => {
      const offending: string[] = [];
      const walk = (node: unknown, path: string): void => {
        if (typeof node === 'string') {
          if (/\b(orden|órdenes)\b/i.test(node)) offending.push(`${path}: ${node}`);
        } else if (node && typeof node === 'object') {
          for (const [k, v] of Object.entries(node)) walk(v, `${path}.${k}`);
        }
      };
      const bundle = es as Record<string, unknown>;
      walk(bundle['IMAGING'], 'IMAGING');
      walk(bundle['CHART_REVIEW'], 'CHART_REVIEW');
      const auditTypes = (
        bundle['PORTAL'] as { ENUM: { AUDIT_EVENT_TYPE: Record<string, string> } }
      ).ENUM.AUDIT_EVENT_TYPE;
      for (const [k, v] of Object.entries(auditTypes)) {
        if (k.startsWith('IMAGING_')) walk(v, `AUDIT_EVENT_TYPE.${k}`);
      }
      expect(offending).toEqual([]);
    });
  });
  describe('Provisional rule 2 (2026-11-15): « Commander quand même » records a reason', () => {
    function warned(): void {
      setUp(['ROLE_DOCTOR']);
      imaging.checkDuplicates.and.returnValue(of([MATCH]));
      fillNewOrder();
      component.submitForm();
      fixture.detectChanges();
    }

    it('offers the four reasons, in French', () => {
      warned();
      const reasons = q('[data-testid="imaging-duplicate-reasons"]')!;
      expect(reasons.textContent).toContain('Pourquoi commander quand même ?');
      expect(reasons.textContent).toContain('Changement clinique');
      expect(reasons.textContent).toContain('Examen précédent inadéquat ou indisponible');
      expect(reasons.textContent).toContain('Région ou question clinique différente');
      expect(reasons.textContent).toContain('Autre');
    });

    it('a missing reason is refused, and nothing is sent', () => {
      warned();
      (q('[data-testid="imaging-duplicate-order-anyway"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(imaging.createOrder).not.toHaveBeenCalled();
      expect(q('[data-testid="imaging-duplicate-reason-error"]')!.textContent).toContain(
        'Choisissez pourquoi',
      );
      expect(q('#img-duplicate-reason-CLINICAL_CHANGE')!.getAttribute('aria-invalid')).toBe('true');
    });

    for (const reason of [
      'CLINICAL_CHANGE',
      'PRIOR_INADEQUATE_OR_UNAVAILABLE',
      'DIFFERENT_REGION_OR_QUESTION',
    ] as const) {
      it(`${reason} is sent with the order`, () => {
        warned();
        (q(`[data-testid="imaging-duplicate-reason-${reason}"]`) as HTMLInputElement).click();
        (q('[data-testid="imaging-duplicate-order-anyway"]') as HTMLButtonElement).click();
        expect(imaging.createOrder).toHaveBeenCalledTimes(1);
        const sent = imaging.createOrder.calls.mostRecent().args[0];
        expect(sent.duplicateOverride).toBeTrue();
        expect(sent.duplicateOverrideReason).toBe(reason);
        expect(sent.duplicateOverrideNote).toBeUndefined();
      });
    }

    it('« Autre » needs a note, then sends it', () => {
      warned();
      (q('[data-testid="imaging-duplicate-reason-OTHER"]') as HTMLInputElement).click();
      fixture.detectChanges();
      (q('[data-testid="imaging-duplicate-order-anyway"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(imaging.createOrder).not.toHaveBeenCalled();
      expect(q('[data-testid="imaging-duplicate-reason-error"]')!.textContent).toContain(
        'une note est obligatoire',
      );
      component.overrideNote.set('  Bilan préopératoire ');
      component.orderAnyway();
      const sent = imaging.createOrder.calls.mostRecent().args[0];
      expect(sent.duplicateOverrideReason).toBe('OTHER');
      expect(sent.duplicateOverrideNote).toBe('Bilan préopératoire');
    });

    it('"back" forgets the chosen reason', () => {
      warned();
      component.overrideReason.set('OTHER');
      component.overrideNote.set('x');
      component.dismissDuplicateWarning();
      expect(component.overrideReason()).toBeNull();
      expect(component.overrideNote()).toBe('');
    });

    it('the drawer shows the override: reason, note, who and when', () => {
      const overridden = order({
        id: 'o2',
        duplicateOfRecentOrder: true,
        duplicateOverride: true,
        duplicateOverrideReason: 'OTHER',
        duplicateOverrideNote: 'Bilan préopératoire',
        duplicateOverrideByName: 'Awa Traoré',
        duplicateOverrideAt: '2026-11-15T09:30:00',
      });
      setUp(['ROLE_DOCTOR'], { orders: [overridden] });
      component.viewDetail(overridden);
      fixture.detectChanges();
      const shown = q('[data-testid="imaging-order-duplicate-override"]')!;
      expect(shown.textContent).toContain('Commandée malgré une demande récente');
      expect(shown.textContent).toContain('Autre');
      expect(shown.textContent).toContain('Bilan préopératoire');
      expect(shown.textContent).toContain('Awa Traoré');
    });
  });

  describe('Provisional rule 1 (2026-11-15): « Préautorisation en attente »', () => {
    const pending = (): ImagingOrderResponse =>
      order({
        id: 'o3',
        priority: 'STAT',
        status: 'IN_PROGRESS',
        requiresAuthorization: true,
        authorizationNumber: null,
        authorizationPending: true,
        authorizationPendingSince: '2026-11-15T02:10:00',
      });

    it('the list and the drawer show the flag', () => {
      setUp(['ROLE_RADIOLOGIST'], { orders: [pending()] });
      fixture.detectChanges();
      expect(q('[data-testid="imaging-row-preauth-pending"]')!.textContent).toContain(
        'Préautorisation en attente',
      );
      component.viewDetail(pending());
      fixture.detectChanges();
      expect(q('[data-testid="imaging-order-preauth-pending"]')!.textContent).toContain(
        'Préautorisation en attente',
      );
    });

    it('a radiologist records the number; the flag clears', () => {
      setUp(['ROLE_RADIOLOGIST'], { orders: [pending()] });
      imaging.recordAuthorization.and.returnValue(
        of({ ...pending(), authorizationNumber: 'PA-1', authorizationPending: false }),
      );
      component.viewDetail(pending());
      fixture.detectChanges();
      (q('[data-testid="imaging-record-auth"]') as HTMLButtonElement).click();
      expect(imaging.recordAuthorization).not.toHaveBeenCalled();
      expect(q('#img-record-authNumber')!.getAttribute('aria-invalid')).toBe('true');

      component.authorizationNumberDraft.set(' PA-1 ');
      component.recordAuthorization(pending());
      fixture.detectChanges();
      expect(imaging.recordAuthorization).toHaveBeenCalledOnceWith('o3', 'PA-1');
      expect(q('[data-testid="imaging-order-preauth-pending"]')).toBeNull();
    });

    it('a nurse sees the flag but records nothing (the status endpoint roles only)', () => {
      setUp(['ROLE_NURSE'], { orders: [pending()] });
      component.viewDetail(pending());
      fixture.detectChanges();
      expect(q('[data-testid="imaging-order-preauth-pending"]')).not.toBeNull();
      expect(q('[data-testid="imaging-record-auth"]')).toBeNull();
    });

    it('a hospital administrator (read-only, #1163) records nothing', () => {
      setUp(['ROLE_HOSPITAL_ADMIN'], { orders: [pending()] });
      component.viewDetail(pending());
      fixture.detectChanges();
      expect(q('[data-testid="imaging-record-auth"]')).toBeNull();
    });
  });
});
