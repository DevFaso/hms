import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { AdminComponent } from './admin';
import { AuthService } from '../auth/auth.service';
import { DashboardService, HospitalAdminSummary } from '../services/dashboard.service';

/**
 * The hospital-admin dashboard had no spec at all, which is how eight English
 * stat labels, five English Quick Access tiles and a "System Health: Online"
 * card that reported green whatever happened all sat on the same screen.
 *
 * Assertions go through the DOM: the fix is a key on the model AND a pipe in
 * the template, and a class-level assertion only covers the first half.
 */
function summary(): HospitalAdminSummary {
  return {
    hospitalId: 'h1',
    asOfDate: '2026-09-14',
    appointments: { todayTotal: 17 },
    admissions: {},
    consultations: {},
    staffing: { activeStaff: 42, onShiftToday: 9 },
    billing: {},
    recentAuditEvents: [],
  } as unknown as HospitalAdminSummary;
}

describe('AdminComponent', () => {
  let fixture: ComponentFixture<AdminComponent>;
  let dashboard: jasmine.SpyObj<DashboardService>;

  const textOf = (selector: string) =>
    fixture.debugElement
      .queryAll(By.css(selector))
      .map((el) => el.nativeElement.textContent.trim());

  function build() {
    fixture = TestBed.createComponent(AdminComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    dashboard = jasmine.createSpyObj<DashboardService>('DashboardService', [
      'getHospitalAdminSummary',
    ]);
    dashboard.getHospitalAdminSummary.and.returnValue(of(summary()));

    TestBed.configureTestingModule({
      imports: [AdminComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: DashboardService, useValue: dashboard },
        {
          provide: AuthService,
          useValue: { getUserProfile: () => ({ firstName: 'Awa', lastName: 'Traoré' }) },
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      ADMIN: {
        STAT: {
          ACTIVE_STAFF: 'Personnel actif',
          ON_SHIFT_TODAY: 'En poste aujourd’hui',
          TODAY_APPOINTMENTS: 'Rendez-vous du jour',
          SYSTEM_HEALTH: 'État du système',
          ONLINE: 'En ligne',
          UNAVAILABLE: 'Indisponible',
        },
        SECTION: { USERS_TITLE: 'Gestion des utilisateurs', USERS_DESC: 'Gérer les comptes' },
      },
    });
  });

  it('renders the stat labels in French, not English and not the key', () => {
    build();
    const labels = textOf('.stat-label');
    expect(labels).toContain('Personnel actif');
    expect(labels).toContain('Rendez-vous du jour');
    expect(labels).not.toContain('Active Staff');
    expect(labels).not.toContain('ADMIN.STAT.ACTIVE_STAFF');
  });

  it('renders a word-valued card through the pipe and a figure as-is', () => {
    build();
    const values = textOf('.stat-value');
    expect(values).toContain('42');
    expect(values).toContain('17');
    expect(values).toContain('En ligne');
    expect(values).not.toContain('Online');
  });

  it('translates the Quick Access tiles', () => {
    build();
    expect(textOf('.card-title')).toContain('Gestion des utilisateurs');
    expect(textOf('.card-desc')).toContain('Gérer les comptes');
  });

  it('reports the system as unavailable when the summary call fails', () => {
    // It used to say "Online" here — a constant, shown green precisely when
    // the call that would have contradicted it had just failed.
    dashboard.getHospitalAdminSummary.and.returnValue(throwError(() => new Error('boom')));
    build();
    expect(textOf('.stat-value')).toContain('Indisponible');
    expect(textOf('.stat-value')).not.toContain('En ligne');
  });

  it('names the same four cards before and after the summary arrives', () => {
    // The skeleton used to label two cards the loaded state never shows, so
    // the labels swapped under the reader when the data landed.
    dashboard.getHospitalAdminSummary.and.returnValue(throwError(() => new Error('boom')));
    build();
    const onFailure = fixture.componentInstance.stats().map((s) => s.labelKey);

    dashboard.getHospitalAdminSummary.and.returnValue(of(summary()));
    build();
    expect(fixture.componentInstance.stats().map((s) => s.labelKey)).toEqual(onFailure);
  });
});
