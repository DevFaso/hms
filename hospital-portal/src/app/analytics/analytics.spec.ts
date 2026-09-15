import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { AnalyticsComponent } from './analytics';
import { DashboardService, PlatformAnalytics } from '../services/dashboard.service';

/**
 * The eight stat cards carried their English inline (`label: 'Total
 * Patients'`) and the template rendered it verbatim, so this dashboard was
 * English in every locale while the charts beside it were translated; the
 * by-status breakdowns rendered the backend enum key itself.
 *
 * The fix has two halves — a key on the model AND a pipe in the template — so
 * these cases assert through the rendered DOM. An earlier version of this spec
 * read the signals off the class and stayed green when `| translate` was
 * deleted, which is half a test for a two-part fix.
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
  let fixture: ComponentFixture<AnalyticsComponent>;
  let component: AnalyticsComponent;
  let dashboard: jasmine.SpyObj<DashboardService>;
  let translate: TranslateService;

  const textOf = (selector: string) =>
    fixture.debugElement
      .queryAll(By.css(selector))
      .map((el) => el.nativeElement.textContent.trim());

  beforeEach(() => {
    dashboard = jasmine.createSpyObj<DashboardService>('DashboardService', [
      'getAnalytics',
      'getKpiDashboard',
    ]);
    dashboard.getAnalytics.and.returnValue(of(analytics()));
    // The page embeds <app-kpi-cards>, which fetches on its own.
    dashboard.getKpiDashboard.and.returnValue(throwError(() => new Error('not under test')));

    TestBed.configureTestingModule({
      imports: [AnalyticsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: DashboardService, useValue: dashboard },
      ],
    });
    translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    // Only the values under test; ngx-translate echoes the key for the rest,
    // which is exactly what a missing key does on screen.
    translate.setTranslation('fr', {
      ANALYTICS: {
        APPOINTMENTS: 'Rendez-vous',
        CARD: { TOTAL_PATIENTS: 'Total des patients', ACTIVE_HOSPITALS: 'Hôpitaux actifs' },
      },
      PORTAL: {
        ENUM: {
          APPOINTMENT_STATUS: { SCHEDULED: 'Planifié', COMPLETED: 'Terminé' },
          ENCOUNTER_STATUS: { IN_PROGRESS: 'En cours' },
          INVOICE_STATUS: { PAID: 'Payée' },
        },
      },
    });

    fixture = TestBed.createComponent(AnalyticsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders the card labels in French, not the key and not English', () => {
    const labels = textOf('.stat-label');
    expect(labels.length).toBe(8);
    expect(labels).toContain('Total des patients');
    expect(labels).toContain('Hôpitaux actifs');
    expect(labels).toContain('Rendez-vous');
    // The two halves of the regression: raw English, or an unpiped key path.
    expect(labels).not.toContain('Total Patients');
    expect(labels).not.toContain('ANALYTICS.CARD.TOTAL_PATIENTS');
  });

  it('every stat card carries an i18n key, never prose', () => {
    for (const card of component.statCards()) {
      expect(card.labelKey).toMatch(/^[A-Z][A-Z0-9_]*(\.[A-Z][A-Z0-9_]*)+$/);
    }
  });

  it('the cards show the figures the API sent', () => {
    expect(textOf('.stat-value')).toContain('12');
    expect(textOf('.stat-value')).toContain('3');
  });

  it('runs the by-status rows through the pipe while the colour keeps the raw value', () => {
    expect(textOf('.status-label')).toEqual(['Planifié', 'Terminé', 'En cours', 'Payée']);
    // statusColor() is handed the untranslated token; translating it upstream
    // would break the pill colour and the pipe in one move.
    expect(component.appointmentsByStatus().map((r) => r.label)).toEqual([
      'SCHEDULED',
      'COMPLETED',
    ]);
  });

  it('renders no cards when the analytics call fails', () => {
    dashboard.getAnalytics.and.returnValue(throwError(() => new Error('boom')));
    const failed = TestBed.createComponent(AnalyticsComponent);
    failed.detectChanges();
    expect(failed.componentInstance.statCards()).toEqual([]);
    expect(failed.componentInstance.loading()).toBeFalse();
  });
});
