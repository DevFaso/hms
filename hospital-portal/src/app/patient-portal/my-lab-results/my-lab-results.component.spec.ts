import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of, throwError } from 'rxjs';
import { MyLabResultsComponent } from './my-lab-results.component';
import {
  LabResultPage,
  LabResultSummary,
  PatientPortalService,
} from '../../services/patient-portal.service';

function lab(overrides: Partial<LabResultSummary> = {}): LabResultSummary {
  return {
    id: 'l1',
    testName: 'Hemoglobin',
    result: '13.7',
    referenceRange: '12 - 15.5 g/dL',
    status: 'NORMAL',
    collectedDate: '2026-09-20T08:00:00',
    released: true,
    isPending: false,
    isAbnormal: false,
    unit: 'g/dL',
    orderedBy: 'Dr Diallo',
    performedBy: 'Tech',
    category: 'HEMATOLOGY',
    notes: '',
    resultedAt: '2026-09-20T09:00:00',
    ...overrides,
  };
}

const pending = () => lab({ isPending: true, released: false, status: 'PENDING', result: '' });

describe('MyLabResultsComponent', () => {
  let component: MyLabResultsComponent;
  let fixture: ComponentFixture<MyLabResultsComponent>;
  let results: LabResultSummary[] = [];

  /** One page of `results` (QA D1213: the page reads by offset; twenty at a time). */
  let pageFor: (offset: number) => Observable<LabResultPage> = (offset) =>
    of({ items: results.slice(offset, offset + 20), hasMore: results.length > offset + 20 });
  const mockPortalService = {
    getMyLabResultsPage: (offset: number) => pageFor(offset),
  };

  async function render(rows: LabResultSummary[] = []): Promise<HTMLElement> {
    results = rows;
    await TestBed.configureTestingModule({
      imports: [MyLabResultsComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: mockPortalService }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyLabResultsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => TestBed.resetTestingModule());

  it('should create', async () => {
    await render();
    expect(component).toBeTruthy();
  });

  it('should show empty state when no results', async () => {
    await render();
    expect(component.results().length).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('should toggle expand', async () => {
    await render();
    component.toggleExpand('l1');
    expect(component.expandedId()).toBe('l1');
    component.toggleExpand('l1');
    expect(component.expandedId()).toBeNull();
  });

  /**
   * The defect this guards: an unreleased result carries no value, and the list
   * drew it with the same green "all clear" check as a normal one, beside an
   * empty value. Asserting through the DOM on purpose — reading the component's
   * helpers alone would stay green if the template stopped calling them.
   */
  it('draws a pending result as pending, never as an all-clear', async () => {
    const el = await render([pending()]);

    const icons = [...el.querySelectorAll('.pli-icon .material-symbols-outlined')].map((i) =>
      i.textContent?.trim(),
    );
    expect(icons).toEqual(['hourglass_top']);
    expect(el.querySelector('.pending-badge')).toBeTruthy();
    expect(el.querySelector('.abnormal-badge')).toBeFalsy();
    // The subtitle says a result is expected, rather than "Result:" with nothing after it.
    expect(el.querySelector('.pli-sub')?.textContent).toContain(
      'PORTAL.LAB_RESULTS.PENDING_RESULT',
    );
    expect(component.interpretation(component.results()[0])).toBe(
      'PORTAL.LAB_RESULTS.PENDING_INTERPRETATION',
    );
  });

  it('draws a released abnormal result with the warning icon and badge', async () => {
    const el = await render([lab({ isAbnormal: true, status: 'ABNORMAL_HIGH', result: '17.2' })]);

    expect(el.querySelector('.pli-icon .material-symbols-outlined')?.textContent?.trim()).toBe(
      'warning',
    );
    expect(el.querySelector('.abnormal-badge')).toBeTruthy();
    expect(el.querySelector('.pending-badge')).toBeFalsy();
    // QA D344: the French default writes the decimal with a comma.
    expect(el.querySelector('.pli-sub')?.textContent).toContain('17,2');
    expect(component.interpretation(component.results()[0])).toBe(
      'PORTAL.LAB_RESULTS.ABNORMAL_RESULT',
    );
  });

  it('draws a released normal result with the all-clear and its value', async () => {
    const el = await render([lab()]);

    expect(el.querySelector('.pli-icon .material-symbols-outlined')?.textContent?.trim()).toBe(
      'check_circle',
    );
    expect(el.querySelector('.pending-badge')).toBeFalsy();
    expect(el.querySelector('.pli-sub')?.textContent).toContain('13,7');
    expect(el.querySelector('.pli-sub')?.textContent).toContain('15,5');
    expect(component.interpretation(component.results()[0])).toBe(
      'PORTAL.LAB_RESULTS.NORMAL_RESULT',
    );
  });

  it('draws a released result nothing graded (units differ) without the all-clear', async () => {
    const el = await render([lab({ referenceRange: '', isUngraded: true })]);

    expect(el.querySelector('.pli-icon .material-symbols-outlined')?.textContent?.trim()).toBe(
      'info',
    );
    expect(el.querySelector('.abnormal-badge')).toBeFalsy();
    expect(component.interpretation(component.results()[0])).toBe(
      'PORTAL.LAB_RESULTS.NOT_GRADED_RESULT',
    );
  });

  it('shows the pending text in the detail panel instead of a blank result', async () => {
    const el = await render([pending()]);
    component.toggleExpand('l1');
    fixture.detectChanges();

    const detail = el.querySelector('.detail-panel');
    expect(detail?.textContent).toContain('PORTAL.LAB_RESULTS.PENDING_RESULT');
    expect(detail?.textContent).toContain('PORTAL.LAB_RESULTS.PENDING_INTERPRETATION');
    expect(detail?.textContent).not.toContain('PORTAL.LAB_RESULTS.NORMAL_RESULT');
  });

  /**
   * QA D1016 (D931 fixed on the doctor's side only): the patient saw a
   * corrected "Négatif" as a second, ordinary result. It is marked, in the
   * list and in the detail, and a first result is not.
   */
  it('QA D1016: marks a corrected result as corrected, in the list and the detail', async () => {
    const el = await render([lab({ id: 'c1', corrected: true }), lab({ id: 'n1' })]);
    const items = el.querySelectorAll('.portal-list-item');
    expect(items[0].querySelector('[data-testid="lab-correction-badge"]')).toBeTruthy();
    expect(items[1].querySelector('[data-testid="lab-correction-badge"]')).toBeFalsy();
    component.toggleExpand('c1');
    fixture.detectChanges();
    expect(el.querySelectorAll('.detail-panel [data-testid="lab-correction-badge"]').length).toBe(
      1,
    );
  });

  describe('QA D1213: every released result is reachable', () => {
    /** Twenty-one current results, newest first; the oldest is the critical K 6,8. */
    function history(): LabResultSummary[] {
      const rows = Array.from({ length: 20 }, (_, i) =>
        lab({ id: `l${i}`, testName: `Glycémie ${i}`, collectedDate: '2026-10-20T08:00:00' }),
      );
      rows.push(
        lab({
          id: 'k-critical',
          testName: 'Kaliémie',
          result: '6.8',
          unit: 'mmol/L',
          status: 'CRITICAL',
          isAbnormal: true,
          collectedDate: '2026-09-01T08:00:00',
        }),
      );
      return rows;
    }

    afterEach(() => {
      pageFor = (offset) =>
        of({ items: results.slice(offset, offset + 20), hasMore: results.length > offset + 20 });
    });

    it('shows twenty, then the older ones (the critical K 6,8 too) on "Afficher les résultats plus anciens"', async () => {
      const el = await render(history());
      expect(el.querySelectorAll('[data-lab-id]').length).toBe(20);
      expect(el.textContent).not.toContain('Kaliémie');
      const more = el.querySelector<HTMLButtonElement>('[data-testid="lab-load-more"]');
      expect(more).withContext('a way to the older results').not.toBeNull();

      more!.click();
      fixture.detectChanges();

      expect(el.querySelectorAll('[data-lab-id]').length).toBe(21);
      expect(el.querySelector('[data-lab-id="k-critical"]')?.textContent).toContain('Kaliémie');
      // Nothing older: the button goes, and the keyboard lands on the first row added.
      expect(el.querySelector('[data-testid="lab-load-more"]')).toBeNull();
      await new Promise((resolve) => setTimeout(resolve));
      expect(document.activeElement?.getAttribute('data-lab-id')).toBe('k-critical');
    });

    it('asks the next page from the offset of what is shown', async () => {
      const asked: number[] = [];
      pageFor = (offset) => {
        asked.push(offset);
        return of({
          items: history().slice(offset, offset + 20),
          hasMore: history().length > offset + 20,
        });
      };
      const el = await render(history());
      el.querySelector<HTMLButtonElement>('[data-testid="lab-load-more"]')!.click();
      expect(asked).toEqual([0, 20]);
    });

    it('never lists a row twice, and stops when a page adds nothing (a server that ignores the offset)', async () => {
      pageFor = () => of({ items: history().slice(0, 20), hasMore: true });
      const el = await render(history());
      el.querySelector<HTMLButtonElement>('[data-testid="lab-load-more"]')!.click();
      fixture.detectChanges();
      expect(el.querySelectorAll('[data-lab-id]').length).toBe(20);
      expect(el.querySelector('[data-testid="lab-load-more"]')).toBeNull();
      // The button went: the keyboard lands on the last row shown, not the page body.
      await new Promise((resolve) => setTimeout(resolve));
      expect(document.activeElement?.getAttribute('data-lab-id')).toBe('l19');
    });

    it('a failed first read says so, with the server reason, never "no results"', async () => {
      pageFor = () =>
        throwError(() => ({ status: 403, error: { message: 'Accès au portail suspendu.' } }));
      const el = await render();
      const failed = el.querySelector('[data-testid="lab-load-failed"]');
      expect(failed?.textContent).toContain('Accès au portail suspendu.');
      expect(el.textContent).not.toContain('PORTAL.LAB_RESULTS.EMPTY_TITLE');

      pageFor = (offset) => of({ items: history().slice(offset, offset + 20), hasMore: true });
      failed!.querySelector<HTMLButtonElement>('button')!.click();
      fixture.detectChanges();
      expect(el.querySelectorAll('[data-lab-id]').length).toBe(20);
    });

    it('a failed later page keeps the list and offers the older results again', async () => {
      const el = await render(history());
      pageFor = () =>
        throwError(() => ({ status: 500, error: { message: 'NullPointerException' } }));
      el.querySelector<HTMLButtonElement>('[data-testid="lab-load-more"]')!.click();
      fixture.detectChanges();
      expect(el.querySelectorAll('[data-lab-id]').length).toBe(20);
      const failed = el.querySelector('[data-testid="lab-load-more-failed"]');
      expect(failed?.textContent).toContain('PORTAL_PAGING.LAB_LOAD_MORE_FAILED');
      expect(failed?.textContent).not.toContain('NullPointerException');
      expect(el.querySelector('[data-testid="lab-load-more"]')).not.toBeNull();
    });
  });
});
