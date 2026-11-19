import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { CredentialsLabelPipe } from '../pipes/credentials-label.pipe';
import { DateTimePipe } from '../pipes/date-time.pipe';
import { ServerTimePipe } from '../pipes/server-time.pipe';
import { NoteAddendumView, addendumWhen } from './note-addendum-view';

export type { NoteAddendumView } from './note-addendum-view';

/**
 * QA D2260 (r63, High): an addendum to a signed note was saved and shown
 * nowhere — a correction to a locked note invisible to every reader. Every
 * template that shows a note renders its addenda with this component, under
 * the note, oldest first, each dated and signed by its author
 * (`scripts/lib/note-addenda.test.mjs` fails a note reader that leaves it out).
 */
@Component({
  selector: 'app-note-addenda',
  standalone: true,
  imports: [TranslateModule, CredentialsLabelPipe, DateTimePipe, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (shown().length > 0) {
      <section class="note-addenda" data-testid="note-addenda">
        <h4 class="note-addenda__title">
          {{ 'NOTE_ADDENDA.TITLE' | translate: { count: shown().length } }}
        </h4>
        <ol class="note-addenda__list">
          @for (entry of shown(); track $index) {
            <li class="note-addenda__item" data-testid="note-addendum">
              <p class="note-addenda__meta" data-testid="note-addendum-meta">
                <strong>{{ 'NOTE_ADDENDA.ADDENDUM' | translate }}</strong>
                @if (when(entry); as at) {
                  · <time>{{ at | serverTime | dateTime }}</time>
                }
                @if (entry.authorName) {
                  ·
                  {{
                    (entry.signedAt ? 'NOTE_ADDENDA.SIGNED_BY' : 'NOTE_ADDENDA.BY')
                      | translate: { name: entry.authorName }
                  }}
                  @if (entry.authorCredentials) {
                    <span>, {{ entry.authorCredentials | credentialsLabel }}</span>
                  }
                }
              </p>
              <p class="note-addenda__content" data-testid="note-addendum-content">
                {{ entry.content }}
              </p>
            </li>
          }
        </ol>
      </section>
    }
  `,
  styles: `
    .note-addenda {
      margin-top: 8px;
      padding: 8px 12px;
      border-left: 3px solid #f59e0b;
      background: #fffbeb;
      border-radius: 4px;
      min-width: 0;
    }
    .note-addenda__title {
      margin: 0 0 4px;
      font-size: 0.8125rem;
      font-weight: 600;
      color: #92400e;
    }
    .note-addenda__list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .note-addenda__meta {
      margin: 0;
      font-size: 0.75rem;
      color: #475569;
      overflow-wrap: anywhere;
    }
    .note-addenda__content {
      margin: 2px 0 0;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      font-size: 0.875rem;
      color: #1e293b;
    }
  `,
})
export class NoteAddendaComponent {
  readonly addenda = input<readonly NoteAddendumView[] | null | undefined>([]);

  /** Non-blank addenda, oldest first. */
  readonly shown = computed(() =>
    (this.addenda() ?? [])
      .filter((entry) => !!entry?.content?.trim())
      .slice()
      .sort((a, b) => (addendumWhen(a) ?? '').localeCompare(addendumWhen(b) ?? '')),
  );

  when(a: NoteAddendumView): string | null {
    return addendumWhen(a);
  }
}
