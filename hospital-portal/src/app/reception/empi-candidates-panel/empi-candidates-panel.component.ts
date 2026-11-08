import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  DestroyRef,
  Output,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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
  templateUrl: './empi-candidates-panel.component.html',
  styleUrl: './empi-candidates-panel.component.scss',
})
export class EmpiCandidatesPanelComponent {
  protected readonly sexOptions = SEX_OPTIONS;
  protected readonly sexLabels: Record<string, string> = {
    F: 'EMPI.SEX.F',
    M: 'EMPI.SEX.M',
    X: 'EMPI.SEX.X',
  };

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

  private readonly destroyRef = inject(DestroyRef);
  private searchRequest = 0;

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
    if (this.merging()) return;
    const query = this.toQuery();
    if (!hasAnyField(query)) {
      this.toast.error(this.translate.instant('EMPI.VALIDATION.AT_LEAST_ONE_FIELD'));
      return;
    }
    const request = ++this.searchRequest;
    this.results.set([]);
    this.searched.set(false);
    this.mergeSelection.set([]);
    this.mergeArmed.set(false);
    this.searching.set(true);
    this.error.set(false);
    this.disabled.set(false);
    this.empi
      .findCandidates(query)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (matches) => {
          if (request !== this.searchRequest) return;
          this.results.set(matches ?? []);
          this.searching.set(false);
          this.searched.set(true);
        },
        error: (err: HttpErrorResponse) => {
          if (request !== this.searchRequest) return;
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
    if (this.merging()) return;
    ++this.searchRequest;
    this.searching.set(false);
    this.disabled.set(false);
    this.mergeSelection.set([]);
    this.mergeArmed.set(false);
    this.form = { ...EMPTY_FORM };
    this.results.set([]);
    this.searched.set(false);
    this.error.set(false);
  }

  protected confirmMatch(match: EmpiCandidateMatch): void {
    if (this.merging()) return;
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
    if (this.merging()) return;
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
    if (this.merging()) return;
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
    if (this.merging()) return;
    this.mergeArmed.set(false);
    this.mergeSelection.update((selection) =>
      selection.length === 2 ? [selection[1], selection[0]] : selection,
    );
  }

  /** Two-click confirmation: first click arms, second executes. */
  protected executeMerge(): void {
    if (this.merging()) return;
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
