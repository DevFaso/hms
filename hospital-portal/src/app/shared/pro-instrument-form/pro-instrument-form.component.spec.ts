import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { ProInstrumentFormComponent, unansweredItems } from './pro-instrument-form.component';
import { ProInstrumentView } from '../../services/pro-screening.service';

/**
 * The fixture is a made-up two-item instrument, never EPDS content — the
 * real wording is licensed text that arrives as data from a validated
 * source, and a spec is not that source.
 */
const fixtureInstrument: ProInstrumentView = {
  code: 'TEST',
  name: 'Test instrument',
  language: 'en',
  availableLanguages: ['en', 'fr'],
  instruction: 'Pick the answer that fits best.',
  maxScore: 6,
  criticalItemNo: 2,
  items: [
    {
      itemNo: 1,
      prompt: 'First question',
      options: [
        { optionNo: 0, label: 'Never' },
        { optionNo: 1, label: 'Sometimes' },
        { optionNo: 2, label: 'Often' },
      ],
    },
    {
      itemNo: 2,
      prompt: 'Second question',
      options: [
        { optionNo: 0, label: 'No' },
        { optionNo: 3, label: 'Yes' },
      ],
    },
  ],
};

describe('unansweredItems', () => {
  it('lists the items without an answer, in instrument order', () => {
    expect(unansweredItems(fixtureInstrument, {})).toEqual([1, 2]);
    expect(unansweredItems(fixtureInstrument, { 2: 0 })).toEqual([1]);
    // An option numbered 0 is still an answer — `falsy` is not `unanswered`.
    expect(unansweredItems(fixtureInstrument, { 1: 0, 2: 0 })).toEqual([]);
  });
});

describe('ProInstrumentFormComponent', () => {
  let fixture: ComponentFixture<ProInstrumentFormComponent>;
  let component: ProInstrumentFormComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProInstrumentFormComponent, TranslateModule.forRoot()],
    }).compileComponents();
    fixture = TestBed.createComponent(ProInstrumentFormComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('instrument', fixtureInstrument);
    fixture.componentRef.setInput('idPrefix', 'spec');
    fixture.detectChanges();
  });

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;

  it('renders one radio group per item with the option labels only', () => {
    const groups = el().querySelectorAll('fieldset.pro-item');
    expect(groups.length).toBe(2);
    expect(groups[0].querySelector('legend')?.textContent).toContain('First question');
    expect(groups[0].querySelectorAll('input[type="radio"]').length).toBe(3);
    // Labels, never scores: the option worth 3 points renders as "Yes", not "3".
    const secondLabels = Array.from(groups[1].querySelectorAll('label')).map((l) =>
      l.textContent?.trim(),
    );
    expect(secondLabels).toEqual(['No', 'Yes']);
    expect(el().textContent).not.toContain('maxScore');
  });

  it('namespaces the radio groups by prefix so two forms on a page cannot collide', () => {
    const first = el().querySelector<HTMLInputElement>('#spec-item-1-opt-0');
    expect(first).not.toBeNull();
    expect(first?.name).toBe('spec-item-1');
    expect(el().querySelector('#spec-item-2-opt-3')).not.toBeNull();
  });

  it('marks unanswered items and updates the answers model on select', () => {
    expect(el().querySelectorAll('.pro-item-unanswered').length).toBe(2);
    const emitted: Record<number, number>[] = [];
    component.answers.subscribe((v) => emitted.push(v));

    const option = el().querySelector<HTMLInputElement>('#spec-item-2-opt-3')!;
    option.click();
    fixture.detectChanges();

    expect(component.answers()).toEqual({ 2: 3 });
    expect(emitted).toEqual([{ 2: 3 }]);
    expect(el().querySelectorAll('.pro-item-unanswered').length).toBe(1);
    expect(component.answeredCount()).toBe(1);
  });

  it('keeps other answers when one item changes', () => {
    fixture.componentRef.setInput('answers', { 1: 1 });
    fixture.detectChanges();
    component.select(2, 0);
    expect(component.answers()).toEqual({ 1: 1, 2: 0 });
    component.select(1, 2);
    expect(component.answers()).toEqual({ 1: 2, 2: 0 });
  });

  it('ignores selection while disabled', () => {
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();
    component.select(1, 1);
    expect(component.answers()).toEqual({});
    expect(el().querySelector<HTMLInputElement>('#spec-item-1-opt-0')?.disabled).toBeTrue();
  });
});
