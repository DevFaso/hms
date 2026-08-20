import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { PartographChartComponent } from './partograph-chart.component';
import { PartographEntryResponse } from '../services/labor.service';

function point(
  hours: number | null,
  dilation: number | null,
  descent: number | null = null,
): PartographEntryResponse {
  return {
    id: 'pe-' + hours,
    episodeId: 'e1',
    patientId: 'p1',
    observationTime: '2026-08-20T08:00:00',
    documentedAt: '2026-08-20T08:00:00',
    lateEntry: false,
    recordedByStaffName: null,
    fetalHeartRateBpm: null,
    liquorColour: null,
    mouldingDegree: null,
    cervicalDilationCm: dilation,
    descentFifths: descent,
    contractionsPerTenMinutes: null,
    contractionDurationSeconds: null,
    oxytocinDropsPerMinute: null,
    drugsGiven: null,
    ivFluids: null,
    pulseBpm: null,
    systolicBpMmHg: null,
    diastolicBpMmHg: null,
    temperatureCelsius: null,
    urineOutputMl: null,
    urineProtein: null,
    urineAcetone: null,
    notes: null,
    alerts: [],
    hoursSinceActivePhaseStart: hours,
  };
}

describe('PartographChartComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PartographChartComponent, TranslateModule.forRoot()],
    }).compileComponents();
  });

  function create(entries: PartographEntryResponse[]) {
    const fixture = TestBed.createComponent(PartographChartComponent);
    fixture.componentRef.setInput('entries', entries);
    fixture.detectChanges();
    return fixture;
  }

  it('plots only entries with an active-phase offset and a value', () => {
    const fixture = create([
      point(0, 4),
      point(2, 6),
      point(null, 3), // latent phase — excluded
      point(4, null), // no dilation — excluded from dilation series
    ]);
    expect(fixture.componentInstance.dilationPointsAttr().length).toBe(2);
  });

  it('sorts points by hours for the polyline', () => {
    const fixture = create([point(3, 7), point(1, 5)]);
    const points = fixture.componentInstance.dilationPointsAttr();
    expect(points[0].hours).toBe(1);
    expect(points[1].hours).toBe(3);
  });

  it('maps dilation onto the inverted y axis (10 cm at the top)', () => {
    const fixture = create([point(0, 10), point(0, 0)]);
    const [top, bottom] = fixture.componentInstance.dilationPointsAttr();
    expect(top.y).toBeLessThan(bottom.y);
  });

  it('keeps the action line 4 hours right of the alert line', () => {
    const chart = create([]).componentInstance;
    expect(chart.actionLine.x1).toBeGreaterThan(chart.alertLine.x1);
    expect(chart.actionLine.y1).toBe(chart.alertLine.y1); // both start at 4 cm
  });

  it('renders descent as a separate series', () => {
    const fixture = create([point(1, 6, 3)]);
    expect(fixture.componentInstance.descentPointsAttr().length).toBe(1);
    expect(fixture.componentInstance.descentPointsAttr()[0].value).toBe(3);
  });
});
