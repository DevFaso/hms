import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { AnalyticsComponent } from './analytics';
import { DashboardService, PlatformAnalytics } from '../services/dashboard.service';

/**
 * The eight stat cards carried their English text inline (`label: 'Total
 * Patients'`) and the template rendered it verbatim, so the analytics
 * dashboard was English in every locale while the charts beside it were
 * translated. These cases pin the shape that fixed it: a card carries an i18n
 * KEY, never prose.
 */
function analytics(overrides: Partial<PlatformAnalytics> = {}): PlatformAnalytics {
  return {
    totalPatients: 12,
    totalEncounters: 7,
    totalAppointments: 30,
    totalInvoices: 4,
    totalLabOrders: 9,
    totalPrescriptions: 5,
    totalUsers: 21,
    activeHospitals: 3,
    appointmentTrend: [],
    encounterTrend: [],
    patientRegistrationTrend: [],
    appointmentsByStatus: { SCHEDULED: 2, COMPLETED: 1 },
    encountersByStatus: { IN_PROGRESS: 1 },
    invoicesByStatus: { PAID: 1 },
    departmentUtilization: [],
    hospitalMetrics: [],
    ...overrides,
  };
}

describe('AnalyticsComponent', () => {
  let component: AnalyticsComponent;
  let dashboard: jasmine.SpyObj<DashboardService>;

  beforeEach(() => {
    dashboard = jasmine.createSpyObj<DashboardService>('DashboardService', ['getAnalytics']);
    dashboard.getAnalytics.and.returnValue(of(analytics()));

    TestBed.configureTestingModule({
      imports: [AnalyticsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: DashboardService, useValue: dashboard },
      ],
    });
    component = TestBed.createComponent(AnalyticsComponent).componentInstance;
    component.ngOnInit();
  });

  it('every stat card carries an i18n key, not English text', () => {
    const cards = component.statCards();
    expect(cards.length).toBe(8);
    for (const card of cards) {
      // A key, not prose: uppercase segments separated by dots.
      expect(card.labelKey).toMatch(/^[A-Z][A-Z0-9_]*(\.[A-Z][A-Z0-9_]*)+$/);
    }
  });

  it('the cards read the figures the API sent', () => {
    const byKey = new Map(component.statCards().map((c) => [c.labelKey, c.value]));
    expect(byKey.get('ANALYTICS.CARD.TOTAL_PATIENTS')).toBe(12);
    expect(byKey.get('ANALYTICS.CARD.ACTIVE_HOSPITALS')).toBe(3);
    expect(byKey.get('ANALYTICS.APPOINTMENTS')).toBe(30);
  });

  it('keeps the raw enum value on the by-status rows so the pipe and the colour agree', () => {
    // The template renders `item.label | enumLabel: 'appointmentStatus'` and
    // passes the same `item.label` to statusColor(); translating it here would
    // break the colour lookup and the pipe at once.
    expect(component.appointmentsByStatus().map((r) => r.label)).toEqual([
      'SCHEDULED',
      'COMPLETED',
    ]);
    expect(component.encountersByStatus().map((r) => r.label)).toEqual(['IN_PROGRESS']);
    expect(component.invoicesByStatus().map((r) => r.label)).toEqual(['PAID']);
  });

  it('renders no cards before the API answers', () => {
    dashboard.getAnalytics.and.returnValue(of(null as unknown as PlatformAnalytics));
    const fresh = TestBed.createComponent(AnalyticsComponent).componentInstance;
    expect(fresh.statCards()).toEqual([]);
  });
});
