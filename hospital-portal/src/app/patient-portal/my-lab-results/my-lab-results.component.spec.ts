import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyLabResultsComponent } from './my-lab-results.component';
import { LabResultSummary, PatientPortalService } from '../../services/patient-portal.service';

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

  const mockPortalService = {
    getMyLabResults: () => of(results),
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
    expect(el.querySelector('.pli-sub')?.textContent).toContain('17.2');
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
    expect(el.querySelector('.pli-sub')?.textContent).toContain('13.7');
    expect(component.interpretation(component.results()[0])).toBe(
      'PORTAL.LAB_RESULTS.NORMAL_RESULT',
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
});
