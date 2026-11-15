import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, Route } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ChartReviewComponent } from './chart-review.component';
import {
  ChartReview,
  ChartReviewImaging,
  ChartReviewService,
} from '../../services/chart-review.service';
import { RoleContextService } from '../../core/role-context.service';
import { IMAGING_PAGE_ROLES } from '../../shared/imaging-access';
import { routes } from '../../app.routes';
import fr from '../../../assets/i18n/fr.json';

/**
 * QA r53 imaging, the patient chart (D1924, D1926): an addendum hid the
 * signed conclusion and the report could not be opened; the card showed
 * « Sans objet », dropped the exam's time and said « Urgente » and
 * « Impression » where Imagerie says « Urgent » and « Conclusion ».
 */
function imagingChart(entry: Partial<ChartReviewImaging>): ChartReview {
  return {
    patientId: 'p-1',
    limit: 20,
    encounters: [],
    notes: [],
    results: [],
    medications: [],
    imaging: [
      {
        id: 'o1',
        modality: 'CT',
        studyType: 'Scanner abdomino-pelvien injecté',
        bodyRegion: 'Abdomen',
        laterality: 'NOT_APPLICABLE',
        priority: 'URGENT',
        status: 'RESULTS_AVAILABLE',
        orderedAt: '2026-11-14T08:00:00',
        ...entry,
      },
    ],
    procedures: [],
    timeline: [],
  };
}

describe('ChartReviewComponent — r53 imaging card', () => {
  let fixture: ComponentFixture<ChartReviewComponent>;
  let router: jasmine.SpyObj<Router>;

  beforeAll(() => registerLocaleData(localeFr));

  function setUp(chart: ChartReview, roles: string[] = ['ROLE_DOCTOR']): HTMLElement {
    const chartSpy = jasmine.createSpyObj<ChartReviewService>('ChartReviewService', [
      'getChartReview',
    ]);
    chartSpy.getChartReview.and.returnValue(of(chart));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    TestBed.configureTestingModule({
      imports: [ChartReviewComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ChartReviewService, useValue: chartSpy },
        { provide: LOCALE_ID, useValue: 'fr' },
        { provide: Router, useValue: router },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => roles.some((r) => wanted.includes(r)),
          } as unknown as RoleContextService,
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    fixture = TestBed.createComponent(ChartReviewComponent);
    fixture.componentRef.setInput('patientId', 'p-1');
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector('[data-testid="chart-review-tab-imaging"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('[data-testid="chart-review-panel-imaging"]');
  }

  describe('D1924: the signed conclusion, its addenda, and the way to the report', () => {
    const withAddendum = imagingChart({
      reportStatus: 'FINAL',
      reportImpression: 'Appendicite aiguë non compliquée',
      reports: [
        {
          id: 'r1',
          reportStatus: 'FINAL',
          reportVersion: 1,
          impression: 'Appendicite aiguë non compliquée',
          signedAt: '2026-11-14T10:00:00',
        },
        {
          id: 'r2',
          reportStatus: 'ADDENDUM',
          reportVersion: 2,
          impression: 'Inchangé',
          createdAt: '2026-11-14T11:00:00',
        },
      ],
    });

    it('shows the conclusion first, then the addendum, each labelled', () => {
      const panel = setUp(withAddendum);
      const rows = [...panel.querySelectorAll('[data-testid="chart-review-imaging-report"]')].map(
        (r) => r.textContent!.replace(/\s+/g, ' ').trim(),
      );
      expect(rows.length).toBe(2);
      expect(rows[0]).toContain('Conclusion');
      expect(rows[0]).toContain('Appendicite aiguë non compliquée');
      expect(rows[1]).toContain('Addendum');
      expect(rows[1]).toContain('Inchangé');
    });

    it('opens the order and its reports on Imagerie', () => {
      const panel = setUp(withAddendum);
      const open = panel.querySelector<HTMLButtonElement>(
        '[data-testid="chart-review-imaging-open"]',
      )!;
      expect(open.textContent).toContain('Ouvrir le compte rendu');
      open.click();
      expect(router.navigate).toHaveBeenCalledOnceWith(['/imaging'], {
        queryParams: { order: 'o1' },
      });
    });

    it('a reader Imagerie refuses (lab, pharmacy) is not offered the link', () => {
      for (const role of ['ROLE_LAB_TECHNICIAN', 'ROLE_PHARMACIST']) {
        TestBed.resetTestingModule();
        const panel = setUp(withAddendum, [role]);
        expect(panel.querySelector('[data-testid="chart-review-imaging-open"]'))
          .withContext(role)
          .toBeNull();
        expect(panel.querySelectorAll('[data-testid="chart-review-imaging-report"]').length)
          .withContext(role)
          .toBe(2);
      }
    });

    it('the link is offered to exactly the roles the /imaging route admits', () => {
      const imagingRoute = routes
        .flatMap((r: Route) => [r, ...(r.children ?? [])])
        .find((r: Route) => r.path === 'imaging');
      expect([...(imagingRoute!.data!['roles'] as string[])].sort()).toEqual(
        [...IMAGING_PAGE_ROLES].sort(),
      );
    });

    it('an older payload with the impression alone still shows it', () => {
      const panel = setUp(
        imagingChart({ reportStatus: 'PRELIMINARY', reportImpression: 'Pas de foyer' }),
      );
      expect(
        panel.querySelector('[data-testid="chart-review-imaging-report"]')!.textContent,
      ).toContain('Pas de foyer');
    });
  });

  describe('D1926: the card reads as Imagerie does', () => {
    it('hides « Sans objet », keeps the time, and says « Urgent »', () => {
      const panel = setUp(
        imagingChart({ scheduledFor: '2026-11-20T10:00:00', scheduledTime: '10:00' }),
      );
      expect(panel.querySelector('[data-testid="chart-review-imaging-laterality"]')).toBeNull();
      expect(panel.textContent).not.toContain('Sans objet');
      const scheduled = panel.querySelector('[data-testid="chart-review-imaging-scheduled"]')!;
      expect(scheduled.textContent).toContain('10:00');
      expect(scheduled.textContent).toContain('20 nov. 2026');
      const priority = panel.querySelector('[data-testid="chart-review-imaging-priority"]')!;
      expect(priority.textContent!.trim()).toBe('· Urgent');
    });

    it('a day with no time is a day', () => {
      const panel = setUp(imagingChart({ scheduledFor: '2026-11-20T00:00:00' }));
      const scheduled = panel.querySelector('[data-testid="chart-review-imaging-scheduled"]')!;
      expect(scheduled.textContent).toContain('20 nov. 2026');
      expect(scheduled.textContent).not.toContain('00:00');
    });

    it('a laterality that applies is still shown', () => {
      const panel = setUp(imagingChart({ laterality: 'LEFT' }));
      expect(panel.querySelector('[data-testid="chart-review-imaging-laterality"]')).not.toBeNull();
    });
  });
});
