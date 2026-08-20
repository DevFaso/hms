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
      'getProxyLabResults',
      'getProxyBilling',
      'getProxyRecords',
    ]);
    portal.getProxyAppointments.and.returnValue(of([]));
    portal.getProxyMedications.and.returnValue(of([]));
    portal.getProxyLabResults.and.returnValue(of([]));
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

    expect(portal.getProxyLabResults).toHaveBeenCalledTimes(1);
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
});
