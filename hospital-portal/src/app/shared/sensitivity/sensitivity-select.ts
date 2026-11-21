import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import {
  SENSITIVITY_CATEGORIES,
  SensitivityCategory,
  sensitivityLabelKey,
} from './sensitivity-category';

/**
 * QA D2730: the one control that sets a sensitive category - on a visit, on a
 * problem-list entry, on a lab test. A labelled select, the category that
 * applies when the row has none of its own, and a hint that says what the
 * choice does. The host saves; this only reports the choice.
 */
@Component({
  selector: 'app-sensitivity-select',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './sensitivity-select.scss',
  template: `
    <div class="sensitivity-select">
      <label class="sensitivity-select__label" [for]="controlId()">
        <span class="material-symbols-outlined" aria-hidden="true">shield_lock</span>
        {{ labelKey() | translate }}
      </label>
      <select
        class="sensitivity-select__control"
        [id]="controlId()"
        [attr.data-testid]="controlId()"
        [attr.aria-describedby]="controlId() + '-hint'"
        [disabled]="disabled()"
        (change)="pick($any($event.target).value)"
      >
        <option value="" [selected]="!value()">{{ noneKey() | translate }}</option>
        @for (c of categories; track c) {
          <option [value]="c" [selected]="c === value()">{{ labelOf(c) | translate }}</option>
        }
      </select>
      @if (!value() && inherited(); as from) {
        <p
          class="sensitivity-select__inherited"
          role="note"
          [attr.data-testid]="controlId() + '-inherited'"
        >
          {{ 'SENSITIVITY_TAG.INHERITED' | translate: { category: labelOf(from) | translate } }}
        </p>
      }
      <p class="sensitivity-select__hint" [id]="controlId() + '-hint'">
        {{ hintKey() | translate }}
      </p>
    </div>
  `,
})
export class SensitivitySelectComponent {
  /** The id of the select: its label, its hint and its test id are derived from it. */
  readonly controlId = input.required<string>();
  /** The category set on the row itself; null for none. */
  readonly value = input<SensitivityCategory | null>(null);
  /** The category that applies when the row has none (its visit's, its department's). */
  readonly inherited = input<SensitivityCategory | null>(null);
  readonly disabled = input(false);
  readonly labelKey = input('SENSITIVITY_TAG.LABEL');
  readonly noneKey = input('SENSITIVITY_TAG.NONE');
  readonly hintKey = input.required<string>();

  /** The choice: a category, or null when "none" is picked. */
  readonly picked = output<SensitivityCategory | null>();

  readonly categories = SENSITIVITY_CATEGORIES;

  labelOf(category: SensitivityCategory): string {
    return sensitivityLabelKey(category);
  }

  pick(raw: string): void {
    this.picked.emit((raw || null) as SensitivityCategory | null);
  }
}
