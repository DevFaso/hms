import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { ResultEvolutionComponent } from './result-evolution.component';

describe('ResultEvolutionComponent', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [ResultEvolutionComponent, TranslateModule.forRoot()],
    });
    const fixture = TestBed.createComponent(ResultEvolutionComponent);
    fixture.componentRef.setInput('resultId', 'result-a');
    fixture.componentRef.setInput('preferredUnit', 'g/L');
    fixture.componentRef.setInput('history', [
      { resultValue: '1.2', resultUnit: 'g/L', resultDate: '2026-11-08T09:00:00Z' },
      { resultValue: '120', resultUnit: 'mg/dL', resultDate: '2026-11-07T09:00:00Z' },
      {
        resultValue: '0.9',
        resultUnit: 'g/L',
        resultDate: '2026-11-01T09:00:00Z',
        corrected: true,
      },
    ]);
    fixture.detectChanges();
    return fixture;
  }

  it('restores the chronological observations table and the delta column', () => {
    const fixture = setup();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('thead th').length).toBe(3);
    expect(host.querySelector('thead')?.textContent).toContain('LAB_RESULTS.DELTA');
    const rows = host.querySelectorAll('tbody tr');
    expect(rows[0].textContent).toMatch(/1[.,]2/);
    expect(rows[1].textContent).toContain('120');
    expect(rows[2].textContent).toMatch(/0[.,]9/);
  });

  it('shows loading, failed and insufficient history as different states', () => {
    const fixture = setup();
    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('svg')).toBeNull();
    fixture.componentRef.setInput('loading', false);
    fixture.componentRef.setInput('failed', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
    fixture.componentRef.setInput('failed', false);
    fixture.componentRef.setInput('history', []);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty')?.textContent).toContain(
      'LAB_EVOLUTION.INSUFFICIENT',
    );
  });

  it('makes corrected observations distinguishable without colour', () => {
    const fixture = setup();
    const point = fixture.nativeElement.querySelector('circle.corrected') as SVGCircleElement;
    expect(getComputedStyle(point).fill).toBe('rgb(255, 255, 255)');
    expect(point.textContent).toContain('LAB_RESULTS.CORRECTED');
    expect(fixture.nativeElement.querySelector('app-lab-correction-badge')).toBeTruthy();
  });

  it('resets the selected unit when a different result reuses the panel', () => {
    const fixture = setup();
    fixture.componentInstance.chosenUnit.set('mg/dL');
    expect(fixture.componentInstance.unit()).toBe('mg/dL');
    fixture.componentRef.setInput('resultId', 'result-b');
    fixture.detectChanges();
    expect(fixture.componentInstance.unit()).toBe('g/L');
  });

  it('shows a delta for adjacent exact values in the same unit only', () => {
    const fixture = setup();
    expect(fixture.componentInstance.deltaAt(0)).toBe('—');
    fixture.componentRef.setInput('history', [
      { resultValue: '1.2', resultUnit: 'g/L', resultDate: '2026-11-08T09:00:00Z' },
      { resultValue: '0.9', resultUnit: 'g/L', resultDate: '2026-11-01T09:00:00Z' },
    ]);
    fixture.detectChanges();
    expect(fixture.componentInstance.deltaAt(0)).toMatch(/\+0[.,]3 g\/L/);
    expect(fixture.componentInstance.deltaAt(1)).toBe('—');
  });

  it('never connects correction versions or presents their differences as patient changes', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('polyline')).toBeNull();
    expect(fixture.nativeElement.querySelector('h3')?.textContent).toContain(
      'LAB_EVOLUTION.AUDIT_TITLE',
    );
    expect(fixture.nativeElement.querySelector('.hint')?.textContent).toContain(
      'LAB_EVOLUTION.CORRECTION_NOTE',
    );
    expect(fixture.componentInstance.deltaAt(0)).toBe('—');
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(3);
  });

  it('preserves sub-centesimal changes and puts invalid dates last', () => {
    const fixture = setup();
    fixture.componentRef.setInput('history', [
      { resultValue: '0.0011', resultUnit: 'g/L', resultDate: '2026-11-08T09:00:00Z' },
      { resultValue: '9', resultUnit: 'g/L', resultDate: 'invalid' },
      { resultValue: '0.0010', resultUnit: 'g/L', resultDate: '2026-11-01T09:00:00Z' },
    ]);
    fixture.detectChanges();
    expect(fixture.componentInstance.observations().map((p) => p.resultValue)).toEqual([
      '0.0011',
      '0.0010',
      '9',
    ]);
    expect(fixture.componentInstance.deltaAt(0)).toMatch(/\+0[.,]0001 g\/L/);
    expect(fixture.componentInstance.deltaAt(1)).toBe('—');
    expect(fixture.nativeElement.querySelector('polyline')).toBeTruthy();
  });
});
