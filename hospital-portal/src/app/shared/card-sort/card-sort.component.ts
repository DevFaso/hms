import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

/** A column the list can be sorted by: its field and the i18n key of its header. */
export interface CardSortColumn {
  readonly field: string;
  readonly label: string;
}

export type CardSortDirection = 'asc' | 'desc';

export interface CardSortChange {
  readonly field: string;
  readonly direction: CardSortDirection;
}

let nextId = 0;

/**
 * « Trier par » for a list table in card mode (#1247 follow-up, Rex).
 *
 * Under 600 px — a phone, or a desktop browser zoomed far enough — the global
 * table layer (styles.scss, "card mode") shows each row as a card and hides
 * the header row, sort buttons included: the list could no longer be sorted.
 * This control takes the header's place there: a select of the sortable
 * columns and an ascending / descending toggle. It drives the page's own sort
 * state (the page hands it `field` and `direction` and applies `sortChange`
 * the same way as a header click), so the two can never disagree.
 *
 * It is not shown at 600 px and wider, where the header row sorts. Every
 * template with a sortable header (`aria-sort`) places one next to its table:
 * guarded by scripts/lib/card-sort-control.test.mjs.
 */
@Component({
  selector: 'app-card-sort',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card-sort" role="group" [attr.aria-label]="'CARD_SORT.GROUP' | translate">
      <label class="card-sort__label" [attr.for]="selectId">{{
        'CARD_SORT.SORT_BY' | translate
      }}</label>
      <select
        class="card-sort__select"
        #select
        [id]="selectId"
        [value]="field()"
        (change)="pick(select.value)"
        data-testid="card-sort-field"
      >
        @for (column of columns(); track column.field) {
          <option [value]="column.field" [selected]="column.field === field()">
            {{ column.label | translate }}
          </option>
        }
      </select>
      <button
        type="button"
        class="card-sort__direction"
        (click)="flip()"
        [attr.aria-label]="
          (direction() === 'asc' ? 'CARD_SORT.ASCENDING_FLIP' : 'CARD_SORT.DESCENDING_FLIP')
            | translate
        "
        data-testid="card-sort-direction"
      >
        <span class="material-symbols-outlined" aria-hidden="true">{{
          direction() === 'asc' ? 'arrow_upward' : 'arrow_downward'
        }}</span>
        {{ (direction() === 'asc' ? 'CARD_SORT.ASCENDING' : 'CARD_SORT.DESCENDING') | translate }}
      </button>
    </div>
  `,
  styles: [
    `
      :host {
        display: none;
      }
      @media screen and (max-width: 599px) {
        :host {
          display: block;
          margin: 0 0 0.75rem;
        }
      }
      .card-sort {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 0.5rem;
      }
      .card-sort__label {
        font-size: 0.8125rem;
        font-weight: 600;
        color: var(--text-secondary);
      }
      .card-sort__select {
        flex: 1 1 8rem;
        min-width: 0;
        min-height: 40px;
        padding: 0 0.5rem;
        border: 1px solid var(--border);
        border-radius: 8px;
        background: var(--surface);
        color: var(--text-primary);
        font: inherit;
        font-size: 0.875rem;
      }
      .card-sort__direction {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        min-height: 40px;
        padding: 0 0.75rem;
        border: 1px solid var(--border);
        border-radius: 8px;
        background: var(--surface);
        color: var(--text-primary);
        font: inherit;
        font-size: 0.875rem;
        cursor: pointer;
      }
      .card-sort__direction .material-symbols-outlined {
        font-size: 1.125rem;
      }
      .card-sort__select:focus-visible,
      .card-sort__direction:focus-visible {
        outline: 2px solid var(--primary);
        outline-offset: 2px;
      }
    `,
  ],
})
export class CardSortComponent {
  /** The sortable columns, in the order of the table's headers. */
  readonly columns = input.required<readonly CardSortColumn[]>();
  /** The page's current sort field. */
  readonly field = input.required<string>();
  /** The page's current sort direction. */
  readonly direction = input.required<CardSortDirection>();
  /** A new sort: the page applies it as it applies a header click. */
  readonly sortChange = output<CardSortChange>();

  protected readonly selectId = `card-sort-${++nextId}`;

  pick(field: string): void {
    if (field === this.field()) return;
    this.sortChange.emit({ field, direction: this.direction() });
  }

  flip(): void {
    this.sortChange.emit({
      field: this.field(),
      direction: this.direction() === 'asc' ? 'desc' : 'asc',
    });
  }
}
