import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ImagingComponent, RELEASED_REPORT_STATUSES } from './imaging';
import {
  ImagingService,
  ImagingOrderResponse,
  ImagingReportResponse,
} from '../services/imaging.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { actionRoles } from '../core/action-permissions';
import fr from '../../assets/i18n/fr.json';

/**
 * User decision 2026-11-14: the admin roles read Imagerie — orders and
 * released reports — and never act on it; the super-admin reads every
 * hospital (« Tous les hôpitaux », the default) or one, by a filter.
 */
function order(overrides: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse {
  return {
    id: 'o1',
    patientId: 'p1',
    patientDisplayName: 'Seydou Traoré',
    patientMrn: 'MRN-0042',
    hospitalId: 'h1',
    hospitalName: 'CHU Yalgado',
    modality: 'XRAY',
    studyType: 'Radiographie thorax',
    bodyRegion: 'Thorax',
    priority: 'ROUTINE',
    status: 'ORDERED',
    laterality: 'NOT_APPLICABLE',
    orderedAt: '2026-11-14T13:19:00',
    ...overrides,
  } as ImagingOrderResponse;
}

const HOSPITALS = [
  { id: 'h1', name: 'CHU Yalgado' },
  { id: 'h2', name: 'CHR Koudougou' },
] as HospitalResponse[];

const ACTIONS = [
  '[data-testid^="imaging-start-"]',
  '[data-testid^="imaging-complete-"]',
  '[data-testid^="imaging-author-"]',
];

describe('ImagingComponent — administrators read, never act (decision 2026-11-14)', () => {
  let fixture: ComponentFixture<ImagingComponent>;
  let component: ImagingComponent;
  let imaging: jasmine.SpyObj<ImagingService>;

  beforeAll(() => registerLocaleData(localeFr));

  function setUp(roles: string[], superAdmin = false): void {
    imaging = jasmine.createSpyObj('ImagingService', [
      'getAllOrders',
      'getOrdersByHospital',
      'getReportsByHospital',
      'getReportsForOrder',
    ]);
    imaging.getAllOrders.and.returnValue(
      of([
        order(),
        order({ id: 'o2', hospitalId: 'h2', hospitalName: 'CHR Koudougou', status: 'IN_PROGRESS' }),
      ]),
    );
    imaging.getOrdersByHospital.and.returnValue(
      of([order({ id: 'o2', hospitalId: 'h2', hospitalName: 'CHR Koudougou' })]),
    );
    imaging.getReportsByHospital.and.callFake((hospitalId: string) =>
      of([{ id: 'r-' + hospitalId, hospitalId, reportStatus: 'FINAL' } as ImagingReportResponse]),
    );
    const hospital = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospital.list.and.returnValue(of(HOSPITALS));
    hospital.getMyHospitalAsResponse.and.returnValue(of(HOSPITALS[0]));
    const auth = jasmine.createSpyObj('AuthService', ['getHospitalId', 'getUserProfile']);
    auth.getHospitalId.and.returnValue('h1');
    auth.getUserProfile.and.returnValue({ id: 'u-me' } as never);

    TestBed.configureTestingModule({
      imports: [ImagingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'fr' },
        { provide: ImagingService, useValue: imaging },
        { provide: HospitalService, useValue: hospital },
        { provide: PatientService, useValue: jasmine.createSpyObj('PatientService', ['list']) },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: AuthService, useValue: auth },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => superAdmin,
            globalView: () => superAdmin,
            selectedHospitalId: () => null,
            activeHospitalId: superAdmin ? null : 'h1',
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

  const q = (sel: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(sel);

  it('no imaging write is offered to an admin role in the action registry', () => {
    for (const action of [
      'imagingOrderCreate',
      'imagingOrderUpdate',
      'imagingOrderStatus',
      'imagingReportCreate',
      'imagingReportStatus',
      'imagingCriticalAck',
    ] as const) {
      for (const admin of ['ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN']) {
        expect(actionRoles(action)).withContext(`${action} / ${admin}`).not.toContain(admin);
      }
    }
  });

  for (const role of ['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN']) {
    it(`${role}: reads the requests, with no action and released report statuses only`, () => {
      setUp([role], role === 'ROLE_SUPER_ADMIN');
      expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(2);
      expect(component.canCreateOrder()).toBeFalse();
      for (const sel of ACTIONS) expect(q(sel)).withContext(sel).toBeNull();
      expect(q('[data-testid="imaging-admin-reader-note"]')).not.toBeNull();
      expect(component.reportStatuses).toEqual([...RELEASED_REPORT_STATUSES]);
      expect(component.canSeeResults).toBeTrue();
    });
  }

  it('a super-admin who is also a doctor keeps the clinical actions through the doctor role', () => {
    setUp(['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR'], true);
    expect(component.isAdminReader).toBeFalse();
    expect(component.canCreateOrder()).toBeTrue();
    expect(q('[data-testid="imaging-admin-reader-note"]')).toBeNull();
  });

  it('a hospital admin has no hospital filter and no hospital column', () => {
    setUp(['ROLE_HOSPITAL_ADMIN']);
    expect(q('[data-testid="imaging-hospital-filter"]')).toBeNull();
    expect(q('[data-testid="imaging-hospital-header"]')).toBeNull();
    expect(imaging.getAllOrders).toHaveBeenCalled();
  });

  it('the super-admin lands on « Tous les hôpitaux », combined, each row naming its hospital', () => {
    setUp(['ROLE_SUPER_ADMIN'], true);
    const select = q('[data-testid="imaging-hospital-filter"]') as HTMLSelectElement;
    expect(select).not.toBeNull();
    expect(select.options[0].textContent?.trim()).toBe('Tous les hôpitaux');
    expect(imaging.getAllOrders).toHaveBeenCalled();
    const cells = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[data-testid="imaging-row-hospital"]',
      ),
    ).map((c) => c.textContent?.trim());
    expect(cells).toEqual(['CHU Yalgado', 'CHR Koudougou']);
  });

  it('the super-admin filters to one hospital: its orders only, no hospital column', () => {
    setUp(['ROLE_SUPER_ADMIN'], true);
    component.setHospitalFilter('h2');
    fixture.detectChanges();
    expect(imaging.getOrdersByHospital).toHaveBeenCalledWith('h2');
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(1);
    expect(q('[data-testid="imaging-hospital-header"]')).toBeNull();
  });

  it('the super-admin combined results gather every hospital, each row naming its hospital', () => {
    setUp(['ROLE_SUPER_ADMIN'], true);
    component.setView('results');
    fixture.detectChanges();
    expect(imaging.getReportsByHospital).toHaveBeenCalledWith('h1', { status: 'FINAL' });
    expect(imaging.getReportsByHospital).toHaveBeenCalledWith('h2', { status: 'FINAL' });
    const cells = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[data-testid="imaging-report-hospital"]',
      ),
    ).map((c) => c.textContent?.trim());
    expect(cells).toEqual(['CHU Yalgado', 'CHR Koudougou']);
  });
});
