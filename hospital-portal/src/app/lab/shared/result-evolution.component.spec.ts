import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { ResultEvolutionComponent } from './result-evolution.component';

describe('ResultEvolutionComponent', () => {
  const point = (resultValue: string, resultUnit = 'g/L', day = 1) => ({
    resultValue,
    resultUnit,
    resultDate: `2026-11-0${day}T09:00:00Z`,
  });

  function setup() {
    TestBed.configureTestingModule({
      imports: [ResultEvolutionComponent, TranslateModule.forRoot()],
    });
    return TestBed.createComponent(ResultEvolutionComponent);
  }

  it('resets a manual unit choice when switching observations without closing', () => {
    const fixture = setup();
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('history', [point('1'), point('100', 'mg/dL')]);
    fixture.componentRef.setInput('preferredUnit', 'g/L');
    fixture.detectChanges();
    component.chosenUnit.set('mg/dL');
    expect(component.unit()).toBe('mg/dL');
    // Another result can have the same preferred unit and available unit names.
    fixture.componentRef.setInput('history', [point('2'), point('200', 'mg/dL')]);
    fixture.detectChanges();
    expect(component.unit()).toBe('g/L');
    expect(component.points().map((p) => p.value)).toEqual([2]);
  });

  it('keeps the latest observations first and computes only exact same-unit deltas', () => {
    const fixture = setup();
    const component = fixture.componentInstance;
    fixture.componentRef.setInput('history', [point('1'), point('1.5', 'g/L', 2)]);
    fixture.detectChanges();
    expect(component.sortedHistory().map((p) => p.resultValue)).toEqual(['1.5', '1']);
    expect(component.delta(0)).toContain('0');
    expect(component.delta(0)).toContain('5');
    expect(component.delta(0).startsWith('+')).toBeTrue();
    expect(component.delta(1)).toBe('—');
    for (const previous of [point('100', 'mg/dL'), point('>1'), point('Positive')]) {
      fixture.componentRef.setInput('history', [previous, point('1.5', 'g/L', 2)]);
      expect(component.delta(0)).toBe('—');
    }
    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].children.length).toBe(3);
  });

  it('distinguishes loading, failure and insufficient data and marks corrections with a ring', () => {
    const fixture = setup();
    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).not.toBeNull();
    fixture.componentRef.setInput('loading', false);
    fixture.componentRef.setInput('failed', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    fixture.componentRef.setInput('failed', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty')).not.toBeNull();
    fixture.componentRef.setInput('history', [
      point('1'),
      { ...point('2', 'g/L', 2), corrected: true },
    ]);
    fixture.detectChanges();
    const marker = fixture.nativeElement.querySelector('circle.corrected');
    expect(marker).not.toBeNull();
    expect(getComputedStyle(marker).fill).toBe('rgb(255, 255, 255)');
    expect(getComputedStyle(marker).strokeWidth).toBe('3px');
  });
});
