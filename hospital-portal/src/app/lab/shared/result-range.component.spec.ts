import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { ResultRangeComponent } from './result-range.component';
import { LabResultResponse } from '../../services/lab.service';

describe('ResultRangeComponent', () => {
  it('shows the dated result, exact limits and marker with an accessible text equivalent', () => {
    TestBed.configureTestingModule({ imports: [ResultRangeComponent, TranslateModule.forRoot()] });
    const fixture = TestBed.createComponent(ResultRangeComponent);
    fixture.componentRef.setInput('result', {
      id: 'cdx-result',
      resultValue: '15',
      resultUnit: 'cdx-unit',
      resultDate: '2026-01-02T09:00:00Z',
      gradedReferenceRange: { minValue: 10, maxValue: 20, unit: 'cdx-unit' },
    } as LabResultResponse);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.marker')?.getAttribute('style')).toContain('50%');
    expect(host.querySelector('.value-row')?.textContent).toContain('15 cdx-unit');
    expect(host.querySelector('.reference')?.textContent).toContain('10');
    expect(host.querySelector('.reference')?.textContent).toContain('20 cdx-unit');
    expect(host.querySelector('time')?.getAttribute('datetime')).toBe('2026-01-02T09:00:00Z');
    expect(host.querySelector('time')?.textContent).toContain('2026');
    const descriptionIds = host
      .querySelector('[role="img"]')!
      .getAttribute('aria-describedby')!
      .split(' ');
    expect(descriptionIds.every((id) => host.querySelector(`[id="${id}"]`))).toBeTrue();
  });
});
