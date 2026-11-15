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

  describe('r44: one severity word', () => {
    function render(severityFlag: string | null, value = '1.05') {
      TestBed.configureTestingModule({
        imports: [ResultRangeComponent, TranslateModule.forRoot()],
      });
      const fixture = TestBed.createComponent(ResultRangeComponent);
      fixture.componentRef.setInput('result', {
        id: 'r44',
        resultValue: value,
        resultUnit: 'g/L',
        resultDate: '2026-11-13T09:00:00Z',
        severityFlag,
        gradedReferenceRange: { minValue: 0.7, maxValue: 0.99, unit: 'g/L' },
      } as LabResultResponse);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }
    const badge = (host: HTMLElement) =>
      host.querySelector('[data-testid="range-state"]')?.textContent?.trim();

    it('names the position with the labSeverityFlag labels the drawer and the list use', () => {
      const host = render('HIGH');
      // No locale is loaded here, so the curated English labels show.
      expect(badge(host)).toBe('High');
      const scale = [...host.querySelectorAll('.labels span')].map((e) => e.textContent?.trim());
      expect(scale).toEqual(['Low', 'Normal', 'High']);
    });

    it("shows the server's critical grade rather than a second word for it", () => {
      expect(badge(render('CRITICAL', '5.5'))).toBe('Critical');
    });

    it('falls back to the marker position when the server gave no range grade', () => {
      expect(badge(render(null, '0.5'))).toBe('Low');
    });
  });
});
