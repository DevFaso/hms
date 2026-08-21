import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Output,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { EmpiCandidateMatch, EmpiCandidateQuery, EmpiService } from '../../services/empi.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';

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

      <p *ngIf="disabled()" class="empi-panel__error" data-testid="empi-disabled">
        {{ 'EMPI.DISABLED' | translate }}
      </p>

      <p *ngIf="error()" class="empi-panel__error" data-testid="empi-error">
        {{ 'EMPI.ERROR' | translate }}
      </p>

      <ng-container *ngIf="!error() && !disabled() && searched()">
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
                <button
                  *ngIf="isAdmin"
                  type="button"
                  class="btn-secondary"
                  (click)="toggleMergeSelection(match)"
                  [attr.data-testid]="'empi-select-' + match.patientId"
                >
                  {{
                    (isSelectedForMerge(match.patientId)
                      ? 'EMPI.MERGE_DESELECT'
                      : 'EMPI.MERGE_SELECT'
                    ) | translate
                  }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div
          *ngIf="isAdmin && mergeSelection().length === 2"
          class="empi-panel__merge-bar"
          data-testid="empi-merge-bar"
        >
          <p>
            {{ 'EMPI.MERGE_PRIMARY_LABEL' | translate }}:
            <strong>{{ mergeSelection()[0].displayName }}</strong>
            · {{ 'EMPI.MERGE_SECONDARY_LABEL' | translate }}:
            {{ mergeSelection()[1].displayName }}
          </p>
          <div class="actions">
            <button type="button" class="btn-secondary" (click)="swapMergeSelection()">
              {{ 'EMPI.MERGE_SWAP' | translate }}
            </button>
            <button
              type="button"
              class="btn-primary"
              (click)="executeMerge()"
              [disabled]="merging()"
              data-testid="empi-merge"
            >
              {{ (mergeArmed() ? 'EMPI.MERGE_CONFIRM_BUTTON' : 'EMPI.MERGE_BUTTON') | translate }}
            </button>
          </div>
          <p class="empi-panel__merge-hint">{{ 'EMPI.MERGE_HINT' | translate }}</p>
        </div>

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
      .empi-panel__merge-bar {
        margin-top: 1rem;
        padding: 0.75rem;
        border: 1px solid var(--warning, #b45309);
        background: #fffbeb;
        border-radius: 4px;
      }
      .empi-panel__merge-hint {
        margin: 0.5rem 0 0;
        font-size: 0.85em;
        color: var(--warning, #b45309);
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
  /**
   * Probabilistic matching is behind app.empi.probabilistic.enabled, which
   * defaults to false — the endpoint then 404s. Rendering that as the generic
   * failure message left an admin staring at "something went wrong" for a
   * deployment that is simply not configured, so it gets its own state.
   */
  protected readonly disabled = signal(false);

  /* ── P1 #8: admin duplicate-merge mode ── */
  protected readonly mergeSelection = signal<EmpiCandidateMatch[]>([]);
  protected readonly merging = signal(false);
  protected readonly mergeArmed = signal(false);
  protected readonly selectedMergeIds = computed(
    () => new Set(this.mergeSelection().map((match) => match.patientId)),
  );

  /** Confirmed match — emitted for embedding parents (walk-in flow). */
  @Output() confirm = new EventEmitter<EmpiCandidateMatch>();
  /** New-patient handoff — emitted for embedding parents. */
  @Output() newPatient = new EventEmitter<EmpiCandidateQuery>();

  protected form: CandidateFormState = { ...EMPTY_FORM };

  private readonly empi = inject(EmpiService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);
  private readonly roleContext = inject(RoleContextService);

  /** Only admins may execute the irreversible identity merge. */
  protected readonly isAdmin = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);

  protected search(event: Event): void {
    event.preventDefault();
    const query = this.toQuery();
    if (!hasAnyField(query)) {
      this.toast.error(this.translate.instant('EMPI.VALIDATION.AT_LEAST_ONE_FIELD'));
      return;
    }
    this.searching.set(true);
    this.error.set(false);
    this.disabled.set(false);
    this.empi.findCandidates(query).subscribe({
      next: (matches) => {
        this.results.set(matches ?? []);
        this.searching.set(false);
        this.searched.set(true);
      },
      error: (err: HttpErrorResponse) => {
        this.results.set([]);
        this.searching.set(false);
        this.searched.set(true);
        // 404 here means the feature flag is off, not that the search failed.
        const featureOff = err?.status === 404;
        this.disabled.set(featureOff);
        this.error.set(!featureOff);
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
    this.toast.success(this.translate.instant('EMPI.TOAST.CONFIRMED', { name: match.displayName }));
    this.confirm.emit(match);
    // Self-contained default when nothing is embedding the panel: the
    // confirmed patient IS the person at the desk — open their chart
    // instead of letting a duplicate be created.
    if (!this.confirm.observed) {
      this.router.navigate(['/patients', match.patientId]);
    }
  }

  protected newPatientFromForm(): void {
    this.toast.info(this.translate.instant('EMPI.TOAST.NEW_PATIENT_HANDOFF'));
    const query = this.toQuery();
    this.newPatient.emit(query);
    if (!this.newPatient.observed) {
      this.router.navigate(['/patients/new']);
    }
  }

  /* ── P1 #8: admin duplicate merge ── */

  protected isSelectedForMerge(patientId: string): boolean {
    return this.selectedMergeIds().has(patientId);
  }

  protected toggleMergeSelection(match: EmpiCandidateMatch): void {
    this.mergeArmed.set(false);
    this.mergeSelection.update((selection) => {
      if (selection.some((selected) => selected.patientId === match.patientId)) {
        return selection.filter((selected) => selected.patientId !== match.patientId);
      }
      if (selection.length >= 2) {
        return selection; // at most two — deselect one first
      }
      return [...selection, match];
    });
  }

  protected swapMergeSelection(): void {
    this.mergeArmed.set(false);
    this.mergeSelection.update((selection) =>
      selection.length === 2 ? [selection[1], selection[0]] : selection,
    );
  }

  /** Two-click confirmation: first click arms, second executes. */
  protected executeMerge(): void {
    const selection = this.mergeSelection();
    if (selection.length !== 2) return;
    if (!this.mergeArmed()) {
      this.mergeArmed.set(true);
      return;
    }
    this.merging.set(true);
    const [primary, secondary] = selection;
    this.empi.mergeByPatient(primary.patientId, secondary.patientId).subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant('EMPI.TOAST.MERGED', { name: primary.displayName }),
        );
        this.merging.set(false);
        this.mergeArmed.set(false);
        this.mergeSelection.set([]);
        // Drop the merged-away duplicate from the current result list.
        this.results.update((matches) =>
          matches.filter((match) => match.patientId !== secondary.patientId),
        );
      },
      error: () => {
        this.toast.error(this.translate.instant('EMPI.TOAST.MERGE_FAILED'));
        this.merging.set(false);
        this.mergeArmed.set(false);
      },
    });
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
