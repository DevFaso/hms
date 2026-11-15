import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { RoleContextService } from '../core/role-context.service';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { UnsignedNoteOverdue, UnsignedNotesService } from './unsigned-notes.service';

/**
 * Provisional platform rule: the hospital administrator's list of clinical notes left unsigned for more than
 * 72 hours at the acting hospital. Read-only; the author signs from the visit, and the administrator follows up
 * with them. The administrator gets no in-basket item for it.
 */
@Component({
  selector: 'app-unsigned-notes-page',
  standalone: true,
  imports: [TranslateModule, DateTimePipe, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="unsigned-notes-page">
      <header class="page-header">
        <h1>{{ 'UNSIGNED_NOTES.TITLE' | translate }}</h1>
        <p class="page-subtitle">{{ 'UNSIGNED_NOTES.SUBTITLE' | translate }}</p>
      </header>
      @if (loading()) {
        <p class="state" aria-live="polite">{{ 'COMMON.LOADING' | translate }}</p>
      } @else if (failed()) {
        <p class="state state--error" role="alert">{{ 'UNSIGNED_NOTES.LOAD_ERROR' | translate }}</p>
      } @else if (!rows().length) {
        <p class="state" data-testid="unsigned-notes-empty">
          {{ 'UNSIGNED_NOTES.EMPTY' | translate }}
        </p>
      } @else {
        <div class="table-wrap">
          <table class="unsigned-table" data-testid="unsigned-notes-table">
            <thead>
              <tr>
                <th scope="col">{{ 'UNSIGNED_NOTES.AUTHOR' | translate }}</th>
                <th scope="col">{{ 'UNSIGNED_NOTES.PATIENT' | translate }}</th>
                <th scope="col">{{ 'UNSIGNED_NOTES.VISIT_DATE' | translate }}</th>
                <th scope="col">{{ 'UNSIGNED_NOTES.AGE' | translate }}</th>
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.noteId) {
                <tr>
                  <td>{{ row.authorName || '—' }}</td>
                  <td>{{ row.patientName || '—' }}</td>
                  <td>{{ row.visitDate ? (row.visitDate | serverTime | dateTime) : '—' }}</td>
                  <td>
                    {{
                      'UNSIGNED_NOTES.AGE_VALUE'
                        | translate: { days: daysOf(row), hours: hoursOf(row) }
                    }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `,
  styles: `
    .unsigned-notes-page {
      max-width: 960px;
      margin: 0 auto;
      padding: 24px 16px;
    }
    .page-header {
      margin-bottom: 20px;
    }
    .page-header h1 {
      margin: 0 0 4px;
    }
    .page-subtitle {
      margin: 0;
      color: var(--text-secondary, #5f6368);
    }
    .state {
      padding: 16px 0;
    }
    .state--error {
      color: var(--danger, #b3261e);
    }
    .table-wrap {
      overflow-x: auto;
    }
    .unsigned-table {
      width: 100%;
      border-collapse: collapse;
    }
    .unsigned-table th,
    .unsigned-table td {
      text-align: left;
      padding: 8px 10px;
      border-bottom: 1px solid var(--border-color, #dadce0);
    }
  `,
})
export class UnsignedNotesPageComponent {
  private readonly service = inject(UnsignedNotesService);
  private readonly roleContext = inject(RoleContextService);

  readonly rows = signal<UnsignedNoteOverdue[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);

  /** Only the latest load may write: a response issued under the previous scope is dropped. */
  private loadRequest = 0;

  constructor() {
    // The list follows the hospital scope: the previous hospital's rows are dropped before the new read.
    effect(() => {
      this.roleContext.effectiveHospitalIdForRequest();
      untracked(() => this.load());
    });
  }

  /** Whole days the note has waited. */
  daysOf(row: UnsignedNoteOverdue): number {
    return Math.floor(UnsignedNotesPageComponent.waited(row) / 24);
  }

  /** Hours past the whole days. */
  hoursOf(row: UnsignedNoteOverdue): number {
    return UnsignedNotesPageComponent.waited(row) % 24;
  }

  private static waited(row: UnsignedNoteOverdue): number {
    return Math.max(0, Math.floor(row.ageHours ?? 0));
  }

  private load(): void {
    const request = ++this.loadRequest;
    this.rows.set([]);
    this.failed.set(false);
    this.loading.set(true);
    this.service.getOverdue().subscribe({
      next: (rows) => {
        if (request !== this.loadRequest) return;
        this.rows.set(rows ?? []);
        this.loading.set(false);
      },
      error: () => {
        if (request !== this.loadRequest) return;
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }
}
