import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { MATERNAL_CONCERNS, MaternalConcern } from '../maternal-concerns';

/**
 * Policy 2026-11-20 (provisional, pending a named obstetric clinician's sign-off): the optional tick
 * list of the maternal early warning score's additional concerns, recorded with a vital set. The
 * server reads it for a pregnant or postpartum woman only, where a tick raises the level of concern
 * (never the points). The portal holds no threshold and never requires a tick; the set saves either
 * way. Folded by default: it concerns maternity patients only.
 */
@Component({
  selector: 'app-maternal-concerns',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <details
      class="maternal-concerns"
      data-testid="maternal-concerns"
      [open]="selected().length > 0"
    >
      <summary>{{ 'MATERNAL_CONCERNS.LEGEND' | translate }}</summary>
      <fieldset class="maternal-concerns-options">
        <legend class="visually-hidden">{{ 'MATERNAL_CONCERNS.LEGEND' | translate }}</legend>
        @for (concern of concerns; track concern) {
          <label class="maternal-concerns-option">
            <input
              type="checkbox"
              [attr.data-testid]="'maternal-concern-' + concern"
              [checked]="selected().includes(concern)"
              (change)="toggle(concern, $any($event.target).checked)"
            />
            {{ 'MATERNAL_CONCERNS.' + concern | translate }}
          </label>
        }
      </fieldset>
    </details>
  `,
  styles: [
    `
      .maternal-concerns {
        border: 1px solid var(--border-color, #d0d7de);
        border-radius: 6px;
        padding: 0.5rem 0.75rem;
        min-width: 0;
      }
      .maternal-concerns summary {
        font-weight: 600;
        cursor: pointer;
        overflow-wrap: anywhere;
      }
      .maternal-concerns-options {
        border: 0;
        padding: 0;
        margin: 0.5rem 0 0;
        min-width: 0;
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem 1rem;
      }
      .maternal-concerns-option {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        font-weight: normal;
        overflow-wrap: anywhere;
      }
      .visually-hidden {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0 0 0 0);
        white-space: nowrap;
      }
    `,
  ],
})
export class MaternalConcernsComponent {
  readonly concerns = MATERNAL_CONCERNS;
  readonly selected = input<readonly MaternalConcern[]>([]);
  readonly selectedChange = output<MaternalConcern[]>();

  toggle(concern: MaternalConcern, checked: boolean): void {
    const rest = this.selected().filter((c) => c !== concern);
    // Kept in the server's order, so the same ticks always read the same way.
    const next = checked ? [...rest, concern] : rest;
    this.selectedChange.emit(MATERNAL_CONCERNS.filter((c) => next.includes(c)));
  }
}
