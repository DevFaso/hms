import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { KpiCardsComponent } from './kpi-cards.component';
import { DashboardService, KpiDashboard } from '../../services/dashboard.service';

describe('KpiCardsComponent', () => {
  function render(dashboard: KpiDashboard): HTMLElement {
    const service = jasmine.createSpyObj<DashboardService>('DashboardService', ['getKpiDashboard']);
    service.getKpiDashboard.and.returnValue(of(dashboard));
    TestBed.configureTestingModule({
      imports: [KpiCardsComponent, TranslateModule.forRoot()],
      providers: [{ provide: DashboardService, useValue: service }],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      ANALYTICS: {
        KPI: {
          NO_DATA_IN_WINDOW: 'Aucune donnée sur la période',
          NO_APPOINTMENTS_IN_WINDOW: 'Aucun rendez-vous sur la période',
          SAMPLE_SIZE: '{{count}} mesures',
          NO_SHOW_RATIO: '{{noShow}} absences sur {{total}} rendez-vous',
        },
      },
    });
    translate.use('fr');
    const fixture = TestBed.createComponent(KpiCardsComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function subtitles(el: HTMLElement): string[] {
    return Array.from(el.querySelectorAll('.kpi-cards__sub')).map((s) =>
      (s.textContent ?? '').trim(),
    );
  }

  it('QA D866: an empty window reads in the user language, not "no data in window"', () => {
    const el = render({
      from: '2026-10-01',
      to: '2026-10-30',
      doorToDoctor: { sampleSize: 0 },
      dispenseLeadTime: { sampleSize: 0 },
      noShowRate: { totalAppointments: 0, noShowCount: 0 },
    });

    expect(subtitles(el)).toEqual([
      'Aucune donnée sur la période',
      'Aucune donnée sur la période',
      'Aucun rendez-vous sur la période',
    ]);
  });

  it('QA D866: sample counts are translated with their numbers', () => {
    const el = render({
      from: '2026-10-01',
      to: '2026-10-30',
      doorToDoctor: { sampleSize: 12, averageMinutes: 20 },
      dispenseLeadTime: { sampleSize: 3, averageMinutes: 9 },
      noShowRate: { totalAppointments: 40, noShowCount: 4, rate: 0.1 },
    });

    expect(subtitles(el)).toEqual(['12 mesures', '3 mesures', '4 absences sur 40 rendez-vous']);
  });
});
