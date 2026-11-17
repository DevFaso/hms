import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { InlineSubFormDirective } from './inline-sub-form.directive';

/**
 * QA D1155 / D1131: the question an inline form asks when Escape is pressed
 * over typed text ("Discard what you typed?"). Placed inside the form it
 * belongs to (see {@link InlineSubFormDirective}); renders nothing until asked.
 */
@Component({
  selector: 'app-inline-discard-ask',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (form().asking()) {
      <div class="inline-discard" role="alert" data-testid="inline-discard-ask">
        <p>{{ 'INLINE_FORM.DISCARD_ASK' | translate }}</p>
        <button
          type="button"
          class="btn-secondary"
          data-inline-keep
          data-testid="inline-keep"
          (click)="form().keep()"
        >
          {{ 'INLINE_FORM.KEEP' | translate }}
        </button>
        <button
          type="button"
          class="btn-danger"
          data-testid="inline-discard"
          (click)="form().discard()"
        >
          {{ 'INLINE_FORM.DISCARD' | translate }}
        </button>
      </div>
    }
  `,
  styles: [
    `
      .inline-discard {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.5rem;
        margin-top: 0.5rem;
      }
      .inline-discard p {
        margin: 0;
        flex-basis: 100%;
      }
    `,
  ],
})
export class InlineDiscardAskComponent {
  readonly form = input.required<InlineSubFormDirective>();
}
