import { HttpErrorResponse } from '@angular/common/http';
import { resetStoredLangForTests } from '../../../shared/i18n/app-locale';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ProxyDataViewerComponent } from './proxy-data-viewer.component';
import { PatientPortalService, ProxyResponse } from '../../../services/patient-portal.service';

describe('ProxyDataViewerComponent', () => {
  let fixture: ComponentFixture<ProxyDataViewerComponent>;
  let component: ProxyDataViewerComponent;
  let portal: jasmine.SpyObj<PatientPortalService>;

  function grant(permissions: string): ProxyResponse {
    return {
      id: 'g-1',
      grantorPatientId: 'p-1',
      grantorName: 'Awa Diallo',
      proxyUserId: 'u-2',
      proxyUsername: 'caregiver.sam',
      proxyDisplayName: 'Sam Kabore',
      relationship: 'CAREGIVER',
      status: 'ACTIVE',
      permissions,
      expiresAt: null,
      revokedAt: null,
      notes: null,
      createdAt: '2026-08-01T10:00:00',
    };
  }

  beforeEach(async () => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getMyProxyAccess',
      'getProxyAppointments',
      'getProxyMedications',
      'getProxyLabResultsPage',
      'getProxyBilling',
      'getProxyRecords',
    ]);
    portal.getProxyAppointments.and.returnValue(of([]));
    portal.getProxyMedications.and.returnValue(of([]));
    portal.getProxyLabResultsPage.and.returnValue(of({ items: [], hasMore: false }));
    portal.getProxyBilling.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [ProxyDataViewerComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: PatientPortalService, useValue: portal },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ patientId: 'p-1' }) } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProxyDataViewerComponent);
    component = fixture.componentInstance;
  });

  it('shows the no-access state when no grant covers this patient', () => {
    portal.getMyProxyAccess.and.returnValue(of([{ ...grant('ALL'), grantorPatientId: 'other' }]));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="proxy-no-access"]')).not.toBeNull();
  });

  it('ALL scope exposes all five tabs and auto-loads the first', () => {
    portal.getMyProxyAccess.and.returnValue(of([grant('ALL')]));
    fixture.detectChanges();

    expect(component.tabs().map((t) => t.key)).toEqual([
      'appointments',
      'medications',
      'labResults',
      'billing',
      'records',
    ]);
    expect(component.activeTab()).toBe('appointments');
    expect(portal.getProxyAppointments).toHaveBeenCalledWith('p-1');
  });

  it('normalizes a legacy un-prefixed scope into its tab', () => {
    portal.getMyProxyAccess.and.returnValue(of([grant('MEDICATIONS')]));
    fixture.detectChanges();

    expect(component.tabs().map((t) => t.key)).toEqual(['medications']);
    expect(portal.getProxyMedications).toHaveBeenCalledWith('p-1');
    expect(portal.getProxyAppointments).not.toHaveBeenCalled();
  });

  it('scoped grant only exposes the granted tabs', () => {
    portal.getMyProxyAccess.and.returnValue(of([grant('VIEW_LAB_RESULTS,VIEW_BILLING')]));
    fixture.detectChanges();

    expect(component.tabs().map((t) => t.key)).toEqual(['labResults', 'billing']);
  });

  it('renders the error state when a tab load fails, with retry', () => {
    portal.getMyProxyAccess.and.returnValue(of([grant('VIEW_BILLING')]));
    portal.getProxyBilling.and.returnValue(throwError(() => new Error('403')));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="proxy-tab-error"]')).not.toBeNull();

    portal.getProxyBilling.and.returnValue(of([]));
    component.retryActiveTab();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="proxy-tab-error"]')).toBeNull();
  });

  it('switching tabs loads that domain lazily and only once', () => {
    portal.getMyProxyAccess.and.returnValue(of([grant('ALL')]));
    fixture.detectChanges();

    component.selectTab('labResults');
    component.selectTab('appointments');
    component.selectTab('labResults');

    expect(portal.getProxyLabResultsPage).toHaveBeenCalledTimes(1);
    expect(portal.getProxyAppointments).toHaveBeenCalledTimes(1);
  });

  it('records tab renders the health summary cards', () => {
    portal.getMyProxyAccess.and.returnValue(of([grant('VIEW_RECORDS')]));
    portal.getProxyRecords.and.returnValue(
      of({
        profile: {} as never,
        recentLabResults: [],
        currentMedications: [],
        latestVitals: [],
        immunizations: [],
        allergies: ['Penicillin'],
        activeDiagnoses: [],
      }),
    );
    fixture.detectChanges();

    expect(portal.getProxyRecords).toHaveBeenCalledWith('p-1');
    const records = fixture.nativeElement.querySelector('[data-testid="proxy-records"]');
    expect(records).not.toBeNull();
    expect(records.textContent).toContain('Penicillin');
  });

  /**
   * The Labs tab learned that an unreleased result carries no value, but the
   * Summary card's "Recent labs" list kept printing `result unit` — so a
   * pending row read as "Hemoglobin:" with nothing after it.
   */
  it('summary card shows a pending lab as pending, not as a blank value', () => {
    portal.getMyProxyAccess.and.returnValue(of([grant('VIEW_RECORDS')]));
    portal.getProxyRecords.and.returnValue(
      of({
        profile: {} as never,
        recentLabResults: [
          {
            id: 'l1',
            testName: 'Hemoglobin',
            result: '',
            referenceRange: '',
            status: 'PENDING',
            collectedDate: '2026-09-20T08:00:00',
            released: false,
            isPending: true,
            isAbnormal: false,
            unit: '',
            orderedBy: '',
            performedBy: '',
            category: '',
            notes: '',
            resultedAt: '',
          },
        ],
        currentMedications: [],
        latestVitals: [],
        immunizations: [],
        allergies: [],
        activeDiagnoses: [],
      }),
    );
    fixture.detectChanges();

    const records = fixture.nativeElement.querySelector('[data-testid="proxy-records"]');
    expect(records.textContent).toContain('Hemoglobin');
    expect(records.textContent).toContain('PORTAL.LAB_RESULTS.PENDING_RESULT');
  });
  it('QA D1116: a released lab value and its range read in the reader notation', () => {
    const previousLang = localStorage.getItem('lang');
    localStorage.setItem('lang', 'fr');
    resetStoredLangForTests();
    try {
      portal.getMyProxyAccess.and.returnValue(of([grant('VIEW_RECORDS')]));
      portal.getProxyRecords.and.returnValue(
        of({
          profile: {} as never,
          recentLabResults: [
            {
              id: 'l2',
              testName: 'Kaliémie',
              result: '2.5',
              referenceRange: '3.5 - 5 mmol/L',
              status: 'COMPLETED',
              collectedDate: '2026-09-20T08:00:00',
              released: true,
              isPending: false,
              isAbnormal: true,
              unit: 'mmol/L',
              orderedBy: '',
              performedBy: '',
              category: '',
              notes: '',
              resultedAt: '',
            },
          ],
          currentMedications: [],
          latestVitals: [],
          immunizations: [],
          allergies: [],
          activeDiagnoses: [],
        }),
      );
      fixture.detectChanges();
      const records: HTMLElement = fixture.nativeElement.querySelector(
        '[data-testid="proxy-records"]',
      );
      expect(records.textContent).toContain('2,5');
      expect(records.textContent).not.toContain('2.5');
    } finally {
      if (previousLang === null) localStorage.removeItem('lang');
      else localStorage.setItem('lang', previousLang);
      resetStoredLangForTests();
    }
  });

  /**
   * QA D1212 (round 23): a proxy opening a grantor registered at another
   * hospital got 404 "Patient introuvable…" and read only the generic
   * "Impossible de charger cette section". The server's reason is shown.
   */
  it("QA D1212: a tab the server refuses shows the server's reason, not a bare generic line", () => {
    portal.getMyProxyAccess.and.returnValue(of([grant('VIEW_APPOINTMENTS')]));
    portal.getProxyAppointments.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 404,
            error: { message: 'Patient introuvable pour cet accès.' },
          }),
      ),
    );
    fixture.detectChanges();
    const reason = fixture.nativeElement.querySelector('[data-testid="proxy-tab-error-reason"]');
    expect(reason?.textContent).toContain('Patient introuvable pour cet accès.');
  });

  it('QA D1212: a 5xx body never reaches the proxy; the generic line stays', () => {
    portal.getMyProxyAccess.and.returnValue(of([grant('VIEW_APPOINTMENTS')]));
    portal.getProxyAppointments.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 500, error: { message: 'NullPointerException' } }),
      ),
    );
    fixture.detectChanges();
    const reason = fixture.nativeElement.querySelector('[data-testid="proxy-tab-error-reason"]');
    expect(reason?.textContent).toContain('PORTAL.FAMILY.VIEWER.LOAD_FAILED');
    expect(reason?.textContent).not.toContain('NullPointerException');
  });

  it('QA D1212: grants that could not be read are a failure with its reason, not "no access"', () => {
    portal.getMyProxyAccess.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 403, error: { message: 'Session expirée.' } }),
      ),
    );
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="proxy-no-access"]')).toBeNull();
    const failed = fixture.nativeElement.querySelector('[data-testid="proxy-grant-failed"]');
    expect(failed?.textContent).toContain('Session expirée.');
  });

  it("QA D1213 (sibling): the Labs tab pages the grantor's older results", () => {
    const row = (id: string) =>
      ({ id, testName: id, result: '1', unit: '', isPending: false }) as never;
    portal.getMyProxyAccess.and.returnValue(of([grant('VIEW_LAB_RESULTS')]));
    portal.getProxyLabResultsPage.and.callFake((_id: string, offset: number) =>
      of(
        offset === 0
          ? { items: [row('a'), row('b')], hasMore: true }
          : { items: [row('c')], hasMore: false },
      ),
    );
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('[data-testid="proxy-labs-table"] tbody tr').length).toBe(2);
    el.querySelector<HTMLButtonElement>('[data-testid="proxy-labs-load-more"]')!.click();
    fixture.detectChanges();
    expect(portal.getProxyLabResultsPage).toHaveBeenCalledWith('p-1', 2);
    expect(el.querySelectorAll('[data-testid="proxy-labs-table"] tbody tr').length).toBe(3);
    expect(el.querySelector('[data-testid="proxy-labs-load-more"]')).toBeNull();
  });
});
