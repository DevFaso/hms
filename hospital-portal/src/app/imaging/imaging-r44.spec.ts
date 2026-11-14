import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ImagingComponent } from './imaging';
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
import fr from '../../assets/i18n/fr.json';

/**
 * r44 imaging review: the Imagerie list, the request drawer and the report
 * drawer, rendered in French with the real French bundle.
 */
function order(overrides: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse {
  return {
    id: 'o1',
    patientId: 'p1',
    patientDisplayName: 'Seydou Traoré',
    patientMrn: 'MRN-0042',
    hospitalId: 'h1',
    modality: 'XRAY',
    studyType: 'Radiographie du thorax',
    bodyRegion: 'Thorax',
    priority: 'ROUTINE',
    status: 'RESULTS_AVAILABLE',
    laterality: '',
    orderedAt: '2026-11-13T07:00:00',
    completedAt: '2026-11-13T08:07:00',
    ...overrides,
  } as ImagingOrderResponse;
}

function signedReport(overrides: Partial<ImagingReportResponse> = {}): ImagingReportResponse {
  return {
    id: 'r1',
    imagingOrderId: 'o1',
    modality: 'XRAY',
    reportStatus: 'FINAL',
    reportTitle: 'Radiographie du thorax',
    signed: true,
    signedByName: 'Dr Ouédraogo',
    signedAt: '2026-11-13T08:07:00',
    signatureValue: '434169309f97e0ae0123456789abcdef0123456789abcdef0123456789abcdef',
    findings: 'Pas de foyer.',
    impression: 'Radiographie normale.',
    statusHistory: [
      {
        id: 'h1',
        status: 'PRELIMINARY',
        eventCode: 'AUTHORED',
        statusReason: 'Report authored',
        changedAt: '2026-11-13T08:00:00',
        changedByName: 'Dr Ouédraogo',
        notes: '',
      },
      {
        id: 'h2',
        status: 'FINAL',
        eventCode: 'SIGNED',
        statusReason: 'Report signed',
        changedAt: '2026-11-13T08:07:00',
        changedByName: 'Dr Ouédraogo',
        notes: '',
      },
      {
        id: 'h3',
        status: 'CANCELLED',
        eventCode: null,
        statusReason: 'Doublon',
        changedAt: '2026-11-13T08:09:00',
        changedByName: 'Dr Ouédraogo',
        notes: '',
      },
    ],
    ...overrides,
  } as ImagingReportResponse;
}

describe('ImagingComponent — r44 imaging review (French)', () => {
  let fixture: ComponentFixture<ImagingComponent>;
  let component: ImagingComponent;
  let imagingSpy: jasmine.SpyObj<ImagingService>;
  let orders: ImagingOrderResponse[];

  beforeAll(() => registerLocaleData(localeFr));

  beforeEach(async () => {
    orders = [
      order(),
      order({
        id: 'o2',
        patientDisplayName: 'Awa Kaboré',
        patientMrn: 'MRN-0077',
        studyType: 'Échographie abdominale',
        status: 'CANCELLED',
        orderedAt: '2026-11-01T09:00:00',
        completedAt: null,
      }),
      order({
        id: 'o3',
        patientDisplayName: 'Ali Sawadogo',
        patientMrn: 'MRN-0099',
        studyType: 'Scanner cérébral',
        status: 'DRAFT',
        orderedAt: '2026-11-10T09:00:00',
        completedAt: null,
      }),
    ];
    imagingSpy = jasmine.createSpyObj('ImagingService', [
      'getAllOrders',
      'getReportsForOrder',
      'getReportsByHospital',
      'getLatestReportByOrder',
    ]);
    imagingSpy.getAllOrders.and.callFake(() => of(orders));
    imagingSpy.getReportsForOrder.and.returnValue(of([signedReport()]));
    imagingSpy.getReportsByHospital.and.returnValue(of([]));
    const hospitalSpy = jasmine.createSpyObj('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitalSpy.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'CHU' } as HospitalResponse),
    );
    const authSpy = jasmine.createSpyObj('AuthService', ['getHospitalId', 'getUserProfile']);
    authSpy.getHospitalId.and.returnValue('h1');
    authSpy.getUserProfile.and.returnValue({ id: 'u1' } as never);
    const patientSpy = jasmine.createSpyObj('PatientService', ['list']);
    patientSpy.list.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [ImagingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: LOCALE_ID, useValue: 'fr' },
        { provide: ImagingService, useValue: imagingSpy },
        { provide: HospitalService, useValue: hospitalSpy },
        { provide: PatientService, useValue: patientSpy },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: AuthService, useValue: authSpy },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => false,
            activeHospitalId: 'h1',
            hasAnyActiveRole: () => true,
          } as unknown as RoleContextService,
        },
      ],
    }).compileComponents();

    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    fixture = TestBed.createComponent(ImagingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  const text = (): string =>
    (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ').trim();
  const q = (sel: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(sel);

  it('item 4: the list has a localized Statut column, right after the patient', () => {
    const headers = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('thead th'),
    ).map((th) => th.textContent!.trim());
    expect(headers[1]).toBe('Statut');
    const firstRowCells = (fixture.nativeElement as HTMLElement).querySelectorAll(
      'tbody tr:first-child td',
    );
    expect(firstRowCells[1].textContent!.trim()).toBe('Résultats disponibles');
  });

  it('item 4: every card counts exactly the rows its tab lists, and opens that tab', () => {
    const value = (id: string) => q(`[data-testid="${id}"] .card-value`)!.textContent!.trim();
    expect(value('imaging-card-ordered')).toBe('1'); // the DRAFT order is active work
    expect(value('imaging-card-completed')).toBe('1');
    expect(value('imaging-card-cancelled')).toBe('1');
    expect(value('imaging-card-all')).toBe('3');
    for (const [card, tab] of [
      ['imaging-card-ordered', 'ordered'],
      ['imaging-card-completed', 'completed'],
      ['imaging-card-cancelled', 'cancelled'],
      ['imaging-card-all', 'all'],
    ] as const) {
      q(`[data-testid="${card}"]`)!.click();
      fixture.detectChanges();
      expect(component.activeTab()).toBe(tab);
      expect(String(component.filtered().length)).toBe(value(card));
      expect((fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr').length).toBe(
        component.filtered().length,
      );
    }
  });

  it('item 5: the shared "First Last" name, the MRN below it', () => {
    const cell = q('tbody tr:first-child td')!;
    expect(cell.querySelector('.cell-primary')!.textContent!.trim()).toBe('Seydou Traoré');
    expect(cell.querySelector('.cell-secondary')!.textContent!.trim()).toBe('MRN-0042');
    expect(text()).not.toContain('Traoré, Seydou');
  });

  it('item 9: search by MRN, and the ordered-on date range; the cards follow', () => {
    component.searchTerm = 'mrn-0077';
    component.applyFilter();
    expect(component.filtered().map((o) => o.id)).toEqual(['o2']);
    component.searchTerm = '';
    component.dateFrom = '2026-11-05';
    component.applyFilter();
    fixture.detectChanges();
    expect(component.filtered().map((o) => o.id)).toEqual(['o1', 'o3']);
    expect(q('[data-testid="imaging-card-all"] .card-value')!.textContent!.trim()).toBe('2');
    expect(q('[data-testid="imaging-card-cancelled"] .card-value')!.textContent!.trim()).toBe('0');
    component.dateTo = '2026-11-12';
    component.applyFilter();
    expect(component.filtered().map((o) => o.id)).toEqual(['o3']);
    component.clearDates();
    expect(component.filtered().length).toBe(3);
    expect(q('#imaging-date-from')).not.toBeNull();
    expect(q('#imaging-date-to')).not.toBeNull();
  });

  it('item 3: the request drawer shows when a resulted order was completed, in the app format', () => {
    component.viewDetail(orders[0]);
    fixture.detectChanges();
    const completed = q('[data-testid="imaging-order-completed"]')!;
    expect(completed.textContent).toContain('Terminée le');
    expect(completed.textContent).toMatch(/13 nov\. 2026, \d{2}:07/);
    expect(completed.textContent).not.toContain('—');
  });

  it('item 8: "Terminée le" and "Planifié" are absent where they do not apply; a laterality marked n/a is hidden', () => {
    component.viewDetail(
      order({ status: 'ORDERED', completedAt: null, laterality: 'NOT_APPLICABLE' }),
    );
    fixture.detectChanges();
    expect(q('[data-testid="imaging-order-completed"]')).toBeNull();
    expect(q('[data-testid="imaging-order-scheduled"]')).toBeNull();
    expect(q('[data-testid="imaging-order-laterality"]')).toBeNull();
  });

  it('item 1/8: a recorded laterality is translated, never the raw code; a missing one says so', () => {
    component.viewDetail(order({ laterality: 'LEFT' }));
    fixture.detectChanges();
    expect(q('[data-testid="imaging-order-laterality"]')!.textContent).toContain('Gauche');
    expect(text()).not.toContain('LEFT');
    component.viewDetail(order({ laterality: '' }));
    fixture.detectChanges();
    expect(q('[data-testid="imaging-order-laterality"]')!.textContent).toContain('Non renseignée');
  });

  it('item 2: "Planifié" carries its time when one exists, the date alone otherwise', () => {
    component.viewDetail(order({ scheduledDate: '2026-11-14', scheduledTime: '09:30' }));
    fixture.detectChanges();
    expect(q('[data-testid="imaging-order-scheduled"]')!.textContent).toContain(
      '14 nov. 2026, 09:30',
    );
    component.viewDetail(order({ scheduledDate: '2026-11-14', scheduledTime: '' }));
    fixture.detectChanges();
    const sched = q('[data-testid="imaging-order-scheduled"]')!.textContent!;
    expect(sched).toContain('14 nov. 2026');
    expect(sched).not.toContain(',');
  });

  it('item 6: the request drawer reads Constatations, then Conclusion, as the report drawer does', () => {
    component.viewDetail(orders[0]);
    fixture.detectChanges();
    const card = q('[data-testid="imaging-report-r1"]')!;
    const labels = Array.from(card.querySelectorAll('.field-label')).map((l) =>
      l.textContent!.trim(),
    );
    expect(labels).toEqual(['Constatations', 'Conclusion']);
    expect(card.querySelector('.status-badge')!.className).toContain('status-completed');
  });

  it('items 1, 2 and 7: the signed report drawer is French, app-formatted, the digest behind a toggle', () => {
    component.openReport(signedReport());
    fixture.detectChanges();
    const t = text();
    // item 1: the history events are translated; a typed reason shows as typed.
    expect(t).toContain('Compte rendu rédigé');
    expect(t).toContain('Compte rendu signé');
    expect(t).toContain('Doublon');
    expect(t).not.toContain('Report signed');
    expect(t).not.toContain('Report authored');
    // item 2: no numeric short dates.
    expect(t).not.toMatch(/\d{2}\/\d{2}\/\d{4}/);
    expect(t).toMatch(/Signé par\s*Dr Ouédraogo \(13 nov\. 2026, \d{2}:07\)/);
    // item 7: "Signé et verrouillé" stays; the fingerprint is in a closed toggle.
    const banner = q('.signed-banner')!;
    expect(banner.textContent).toContain('Signé et verrouillé');
    const details = banner.querySelector<HTMLDetailsElement>(
      '[data-testid="imaging-signature-details"]',
    )!;
    expect(details).not.toBeNull();
    expect(details.open).toBeFalse();
    expect(banner.querySelector(':scope > span')!.textContent).not.toContain('434169309f97e0ae');
  });
});
