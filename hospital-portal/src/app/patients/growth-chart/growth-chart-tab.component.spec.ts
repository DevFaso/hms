import { TestBed, ComponentFixture } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { GrowthChartTabComponent } from './growth-chart-tab.component';
import {
  GrowthChartResponse,
  GrowthPoint,
  VitalSignService,
} from '../../services/vital-sign.service';

describe('GrowthChartTabComponent', () => {
  let fixture: ComponentFixture<GrowthChartTabComponent>;
  let component: GrowthChartTabComponent;
  let vitalServiceSpy: jasmine.SpyObj<VitalSignService>;

  const point = (overrides: Partial<GrowthPoint>): GrowthPoint => ({
    recordedAt: '2026-02-10T09:00:00',
    ageDays: 31,
    weightKg: null,
    heightCm: null,
    headCircumferenceCm: null,
    source: 'NURSE_STATION',
    ...overrides,
  });

  const chart = (points: GrowthPoint[]): GrowthChartResponse => ({
    patientId: 'p1',
    dateOfBirth: '2026-01-10',
    gender: 'FEMALE',
    points,
  });

  beforeEach(async () => {
    vitalServiceSpy = jasmine.createSpyObj('VitalSignService', ['getGrowthChart']);
    vitalServiceSpy.getGrowthChart.and.returnValue(of(chart([])));

    await TestBed.configureTestingModule({
      imports: [GrowthChartTabComponent, TranslateModule.forRoot()],
      providers: [{ provide: VitalSignService, useValue: vitalServiceSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(GrowthChartTabComponent);
    component = fixture.componentInstance;
    component.patientId = 'p1';
  });

  it('plots the weight trajectory by default, oldest first', () => {
    vitalServiceSpy.getGrowthChart.and.returnValue(
      of(
        chart([
          point({ ageDays: 90, weightKg: 5.6, heightCm: 58 }),
          point({ ageDays: 31, weightKg: 4.1 }),
        ]),
      ),
    );
    fixture.detectChanges();

    expect(vitalServiceSpy.getGrowthChart).toHaveBeenCalledWith('p1');
    const circles = fixture.nativeElement.querySelectorAll('svg circle');
    expect(circles.length).toBe(2);
    expect(component.seriesPoints().map((p) => p.ageDays)).toEqual([31, 90]);
  });

  it('switching metric re-plots only rows carrying that measurement', () => {
    vitalServiceSpy.getGrowthChart.and.returnValue(
      of(
        chart([
          point({ ageDays: 31, weightKg: 4.1 }),
          point({ ageDays: 90, weightKg: 5.6, heightCm: 58 }),
        ]),
      ),
    );
    fixture.detectChanges();

    fixture.nativeElement.querySelector('[data-testid="metric-height"]').click();
    fixture.detectChanges();

    expect(component.seriesPoints().length).toBe(1);
    expect(fixture.nativeElement.querySelectorAll('svg circle').length).toBe(1);
  });

  it('renders the birth-weight seed as a distinct delivery point', () => {
    vitalServiceSpy.getGrowthChart.and.returnValue(
      of(
        chart([
          point({ ageDays: 0, weightKg: 3.2, source: 'DELIVERY' }),
          point({ ageDays: 31, weightKg: 4.1 }),
        ]),
      ),
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="delivery-point"]')).toBeTruthy();
  });

  it('shows the per-metric empty message when the selected metric has no rows', () => {
    vitalServiceSpy.getGrowthChart.and.returnValue(
      of(chart([point({ ageDays: 31, weightKg: 4.1 })])),
    );
    fixture.detectChanges();

    fixture.nativeElement.querySelector('[data-testid="metric-head"]').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="no-metric-points"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('svg')).toBeNull();
  });

  it('shows the empty state when there are no measurements at all', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="growth-empty"]')).toBeTruthy();
  });

  it('surfaces the backend refusal message verbatim on load failure', () => {
    vitalServiceSpy.getGrowthChart.and.returnValue(
      throwError(() => ({ error: { message: 'Patient not found with ID: p1' } })),
    );
    fixture.detectChanges();

    const errorBox = fixture.nativeElement.querySelector('[data-testid="growth-error"]');
    expect(errorBox).toBeTruthy();
    expect(errorBox.textContent).toContain('Patient not found with ID: p1');
  });

  it('uses a months axis for infants and a years axis past age two', () => {
    vitalServiceSpy.getGrowthChart.and.returnValue(
      of(chart([point({ ageDays: 200, weightKg: 7.5 })])),
    );
    fixture.detectChanges();
    expect(component.chartModel()?.monthsMode).toBeTrue();

    vitalServiceSpy.getGrowthChart.and.returnValue(
      of(chart([point({ ageDays: 2200, weightKg: 19.0 })])),
    );
    component.load();
    fixture.detectChanges();
    expect(component.chartModel()?.monthsMode).toBeFalse();
  });

  it('lists every measurement in the data table regardless of the selected metric', () => {
    vitalServiceSpy.getGrowthChart.and.returnValue(
      of(
        chart([
          point({ ageDays: 0, weightKg: 3.2, source: 'DELIVERY' }),
          point({ ageDays: 31, weightKg: 4.1 }),
          point({ ageDays: 90, heightCm: 58 }),
        ]),
      ),
    );
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('[data-testid="growth-table"] tbody tr');
    expect(rows.length).toBe(3);
  });
});
