import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { ProAnswers, ProInstrumentView } from '../../services/pro-screening.service';

/** Item numbers the respondent has not answered yet, in instrument order. */
export function unansweredItems(instrument: ProInstrumentView, answers: ProAnswers): number[] {
  return instrument.items.filter((i) => answers[i.itemNo] === undefined).map((i) => i.itemNo);
}

/**
 * Renders a PRO instrument (EPDS first) as one radio group per item, in the
 * language the server chose. Shared by the postpartum tab, where a midwife
 * reads the questions to the mother, and the patient portal, where she
 * answers them herself.
 *
 * The component never sees a score — the view DTO carries labels only — so
 * neither surface can leak "this answer is worth 3 points" into the form. The
 * one deliberate omission is a running total, for the same reason.
 */
@Component({
  selector: 'app-pro-instrument-form',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './pro-instrument-form.component.html',
  styleUrl: './pro-instrument-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProInstrumentFormComponent {
  readonly instrument = input.required<ProInstrumentView>();
  /** Two-way: item number → chosen option number. */
  readonly answers = model<ProAnswers>({});
  /** Prefix for radio names/ids so two forms on one page cannot share a group. */
  readonly idPrefix = input('pro');
  readonly disabled = input(false);

  readonly answeredCount = computed(
    () =>
      this.instrument().items.length - unansweredItems(this.instrument(), this.answers()).length,
  );

  select(itemNo: number, optionNo: number): void {
    if (this.disabled()) return;
    this.answers.update((current) => ({ ...current, [itemNo]: optionNo }));
  }

  optionId(itemNo: number, optionNo: number): string {
    return `${this.idPrefix()}-item-${itemNo}-opt-${optionNo}`;
  }
}
