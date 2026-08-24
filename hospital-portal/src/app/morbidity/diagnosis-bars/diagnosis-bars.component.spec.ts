import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { DiagnosisBarsComponent } from './diagnosis-bars.component';
import { DiagnosisSlice } from '../morbidity.service';

describe('DiagnosisBarsComponent', () => {
  let component: DiagnosisBarsComponent;
  let fixture: ComponentFixture<DiagnosisBarsComponent>;

  async function build(slices: DiagnosisSlice[]): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DiagnosisBarsComponent, TranslateModule.forRoot()],
    }).compileComponents();

    fixture = TestBed.createComponent(DiagnosisBarsComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('slices', slices);
    fixture.detectChanges();
  }

  it('scales bars against the largest count, not the total', async () => {
    await build([
      { code: 'B54', display: 'Malaria', count: 400 },
      { code: 'I10', display: 'Hypertension', count: 100 },
    ]);

    expect(component.widthPercent(400)).toBe(100);
    expect(component.widthPercent(100)).toBe(25);
  });

  it('keeps a tiny count visible rather than rendering nothing', async () => {
    await build([
      { code: 'B54', display: 'Malaria', count: 400 },
      { code: 'A00', display: 'Cholera', count: 1 },
    ]);

    // 1/400 would be 0.25% — a bar nobody can see. Floored to a sliver.
    expect(component.widthPercent(1)).toBe(1.5);
  });

  it('is zero-safe when every count is zero', async () => {
    await build([{ code: 'B54', display: 'Malaria', count: 0 }]);

    expect(component.widthPercent(0)).toBe(0);
  });

  it('falls back to the display text when a diagnosis has no ICD code', async () => {
    await build([{ code: null, display: 'Free-text diagnosis', count: 3 }]);

    expect(component.trackKey({ code: null, display: 'Free-text diagnosis', count: 3 })).toBe(
      'Free-text diagnosis',
    );
  });

  it('renders an empty-state message for no data', async () => {
    await build([]);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('MORBIDITY.NO_DATA');
  });

  it('renders one row per diagnosis with its count as real text', async () => {
    await build([
      { code: 'B54', display: 'Malaria', count: 400 },
      { code: 'A00', display: 'Cholera', count: 121 },
    ]);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('.dx-bars__row')).toHaveSize(2);
    // Counts must be readable text, not only bar widths — that is what
    // makes the chart accessible without an aria-label.
    expect(host.textContent).toContain('400');
    expect(host.textContent).toContain('121');
  });
});
