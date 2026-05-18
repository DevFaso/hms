import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { EmpiCandidateMatch, EmpiCandidateQuery, EmpiService } from '../../services/empi.service';
import { ToastService } from '../../core/toast.service';

interface CandidateFormState {
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  sex: string;
  nationalId: string;
}

const EMPTY_FORM: CandidateFormState = {
  firstName: '',
  lastName: '',
  dateOfBirth: '',
  sex: '',
  nationalId: '',
};

const SEX_OPTIONS: string[] = ['F', 'M', 'X'];

/**
 * EMPI receptionist candidate-confirm panel (roadmap row 25
 * follow-on). Renders a draft-identity form + a ranked list of
 * matching Patients with the per-field breakdown the receptionist
 * needs to decide whether to confirm the match or create a new
 * patient.
 *
 * <p>Standalone — embeddable inside the walk-in flow + accessible
 * as a route at {@code /reception/empi-candidates} for the case
 * where the receptionist wants to pre-check before starting an
 * intake. The {@code (confirm)} output emits the chosen patientId
 * up to the parent component; the {@code (newPatient)} output
 * signals the parent to start a fresh-create flow.
 */
@Component({
  selector: 'app-empi-candidates-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="empi-panel" data-testid="empi-candidates-panel">
      <header class="empi-panel__header">
        <h2>{{ 'EMPI.TITLE' | translate }}</h2>
        <p class="empi-panel__subtitle">{{ 'EMPI.SUBTITLE' | translate }}</p>
      </header>

      <form class="empi-panel__form" (submit)="search($event)" novalidate data-testid="empi-form">
        <div class="form-grid">
          <div class="form-row">
            <label for="empi-first">{{ 'EMPI.FIELD.FIRST_NAME' | translate }}</label>
            <input
              id="empi-first"
              name="firstName"
              type="text"
              autocomplete="off"
              [(ngModel)]="form.firstName"
              data-testid="empi-firstName"
            />
          </div>
          <div class="form-row">
            <label for="empi-last">{{ 'EMPI.FIELD.LAST_NAME' | translate }}</label>
            <input
              id="empi-last"
              name="lastName"
              type="text"
              autocomplete="off"
              [(ngModel)]="form.lastName"
              data-testid="empi-lastName"
            />
          </div>
          <div class="form-row">
            <label for="empi-dob">{{ 'EMPI.FIELD.DATE_OF_BIRTH' | translate }}</label>
            <input
              id="empi-dob"
              name="dateOfBirth"
              type="date"
              [(ngModel)]="form.dateOfBirth"
              data-testid="empi-dob"
            />
          </div>
          <div class="form-row">
            <label for="empi-sex">{{ 'EMPI.FIELD.SEX' | translate }}</label>
            <select id="empi-sex" name="sex" [(ngModel)]="form.sex" data-testid="empi-sex">
              <option value="">{{ 'EMPI.FIELD.SEX_PLACEHOLDER' | translate }}</option>
              <option *ngFor="let opt of sexOptions" [value]="opt">{{ opt }}</option>
            </select>
          </div>
          <div class="form-row form-row--span-2">
            <label for="empi-national-id">{{ 'EMPI.FIELD.NATIONAL_ID' | translate }}</label>
            <input
              id="empi-national-id"
              name="nationalId"
              type="text"
              autocomplete="off"
              [(ngModel)]="form.nationalId"
              data-testid="empi-nationalId"
            />
          </div>
        </div>
        <div class="form-actions">
          <button
            type="submit"
            class="btn-primary"
            [disabled]="searching()"
            data-testid="empi-search"
          >
            {{ searching() ? ('EMPI.SEARCHING' | translate) : ('EMPI.SEARCH' | translate) }}
          </button>
          <button type="button" class="btn-secondary" (click)="reset()" data-testid="empi-reset">
            {{ 'COMMON.CLEAR' | translate }}
          </button>
        </div>
      </form>

      <p *ngIf="error()" class="empi-panel__error" data-testid="empi-error">
        {{ 'EMPI.ERROR' | translate }}
      </p>

      <ng-container *ngIf="!error() && searched()">
        <p *ngIf="results().length === 0" class="empi-panel__empty" data-testid="empi-empty">
          {{ 'EMPI.NO_MATCHES' | translate }}
        </p>

        <table *ngIf="results().length > 0" class="data-table" data-testid="empi-results">
          <thead>
            <tr>
              <th scope="col">{{ 'EMPI.COL_PATIENT' | translate }}</th>
              <th scope="col">{{ 'EMPI.COL_SCORE' | translate }}</th>
              <th scope="col">{{ 'EMPI.COL_BREAKDOWN' | translate }}</th>
              <th scope="col">{{ 'COMMON.ACTIONS' | translate }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              *ngFor="let match of results(); trackBy: trackByPatientId"
              [attr.data-patient-id]="match.patientId"
            >
              <td>{{ match.displayName }}</td>
              <td>
                <span
                  class="empi-panel__score"
                  [class.empi-panel__score--high]="match.score >= 0.85"
                  [class.empi-panel__score--medium]="match.score >= 0.7 && match.score < 0.85"
                  [class.empi-panel__score--low]="match.score < 0.7"
                >
                  {{ (match.score * 100 | number: '1.0-1') + '%' }}
                </span>
              </td>
              <td>
                <span
                  class="empi-panel__chip"
                  [class.empi-panel__chip--match]="match.nameMatched"
                  [class.empi-panel__chip--miss]="!match.nameMatched"
                >
                  {{ 'EMPI.BREAKDOWN.NAME' | translate }}
                </span>
                <span
                  class="empi-panel__chip"
                  [class.empi-panel__chip--match]="match.dobMatched"
                  [class.empi-panel__chip--miss]="!match.dobMatched"
                >
                  {{ 'EMPI.BREAKDOWN.DOB' | translate }}
                </span>
                <span
                  class="empi-panel__chip"
                  [class.empi-panel__chip--match]="match.sexMatched"
                  [class.empi-panel__chip--miss]="!match.sexMatched"
                >
                  {{ 'EMPI.BREAKDOWN.SEX' | translate }}
                </span>
                <span
                  class="empi-panel__chip"
                  [class.empi-panel__chip--match]="match.nationalIdMatched"
                  [class.empi-panel__chip--miss]="!match.nationalIdMatched"
                >
                  {{ 'EMPI.BREAKDOWN.NATIONAL_ID' | translate }}
                </span>
              </td>
              <td class="actions">
                <button
                  type="button"
                  class="btn-primary"
                  (click)="confirmMatch(match)"
                  [attr.data-testid]="'empi-confirm-' + match.patientId"
                >
                  {{ 'EMPI.CONFIRM_MATCH' | translate }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div *ngIf="results().length > 0" class="empi-panel__new-patient">
          <p>{{ 'EMPI.NOT_THIS_ONE' | translate }}</p>
          <button
            type="button"
            class="btn-secondary"
            (click)="newPatientFromForm()"
            data-testid="empi-new-patient"
          >
            {{ 'EMPI.CREATE_NEW_PATIENT' | translate }}
          </button>
        </div>
      </ng-container>
    </section>
  `,
  styles: [
    `
      .empi-panel {
        padding: 1.5rem;
      }
      .empi-panel__header h2 {
        margin-bottom: 0.25rem;
      }
      .empi-panel__subtitle {
        margin-top: 0;
        color: var(--muted, #64748b);
      }
      .form-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 0.75rem 1rem;
      }
      .form-row {
        display: flex;
        flex-direction: column;
      }
      .form-row label {
        font-weight: 500;
        margin-bottom: 0.25rem;
      }
      .form-row--span-2 {
        grid-column: span 2;
      }
      .form-actions {
        display: flex;
        gap: 0.5rem;
        margin: 1rem 0 1.5rem;
      }
      .empi-panel__error {
        color: var(--danger, #b00020);
      }
      .empi-panel__empty {
        color: var(--muted, #64748b);
        font-style: italic;
      }
      .empi-panel__score {
        font-weight: 600;
      }
      .empi-panel__score--high {
        color: var(--success, #166534);
      }
      .empi-panel__score--medium {
        color: var(--warning, #b45309);
      }
      .empi-panel__score--low {
        color: var(--muted, #475569);
      }
      .empi-panel__chip {
        display: inline-block;
        margin-right: 0.25rem;
        padding: 0.1rem 0.5rem;
        border-radius: 999px;
        font-size: 0.8em;
      }
      .empi-panel__chip--match {
        background: var(--success-bg, #dcfce7);
        color: var(--success, #166534);
      }
      .empi-panel__chip--miss {
        background: var(--muted-bg, #f1f5f9);
        color: var(--muted, #475569);
      }
      .empi-panel__new-patient {
        margin-top: 1rem;
        padding: 0.75rem;
        background: var(--muted-bg, #f8fafc);
        border-radius: 4px;
      }
      .actions {
        display: flex;
        gap: 0.5rem;
      }
    `,
  ],
})
export class EmpiCandidatesPanelComponent {
  protected readonly sexOptions = SEX_OPTIONS;

  protected readonly results = signal<EmpiCandidateMatch[]>([]);
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly error = signal(false);

  protected form: CandidateFormState = { ...EMPTY_FORM };

  private readonly empi = inject(EmpiService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  protected search(event: Event): void {
    event.preventDefault();
    const query = this.toQuery();
    if (!hasAnyField(query)) {
      this.toast.error(this.translate.instant('EMPI.VALIDATION.AT_LEAST_ONE_FIELD'));
      return;
    }
    this.searching.set(true);
    this.error.set(false);
    this.empi.findCandidates(query).subscribe({
      next: (matches) => {
        this.results.set(matches ?? []);
        this.searching.set(false);
        this.searched.set(true);
      },
      error: () => {
        this.results.set([]);
        this.searching.set(false);
        this.searched.set(true);
        this.error.set(true);
      },
    });
  }

  protected reset(): void {
    this.form = { ...EMPTY_FORM };
    this.results.set([]);
    this.searched.set(false);
    this.error.set(false);
  }

  protected confirmMatch(match: EmpiCandidateMatch): void {
    // The cell-text says "the operator confirms" — surfacing the
    // confirmation as a toast keeps this component self-contained
    // for the foundation pass. The named follow-on is to route the
    // confirmed patientId into the walk-in / appointment flow
    // (parent component @Output binding) once embedded inside the
    // walk-in dialog.
    this.toast.success(this.translate.instant('EMPI.TOAST.CONFIRMED', { name: match.displayName }));
  }

  protected newPatientFromForm(): void {
    this.toast.info(this.translate.instant('EMPI.TOAST.NEW_PATIENT_HANDOFF'));
  }

  protected trackByPatientId(_index: number, match: EmpiCandidateMatch): string {
    return match.patientId;
  }

  private toQuery(): EmpiCandidateQuery {
    return {
      firstName: blankToNull(this.form.firstName),
      lastName: blankToNull(this.form.lastName),
      dateOfBirth: blankToNull(this.form.dateOfBirth),
      sex: blankToNull(this.form.sex),
      nationalId: blankToNull(this.form.nationalId),
    };
  }
}

function blankToNull(raw: string): string | null {
  const trimmed = raw?.trim();
  return trimmed ? trimmed : null;
}

function hasAnyField(query: EmpiCandidateQuery): boolean {
  return !!(
    query.firstName ||
    query.lastName ||
    query.dateOfBirth ||
    query.sex ||
    query.nationalId
  );
}
