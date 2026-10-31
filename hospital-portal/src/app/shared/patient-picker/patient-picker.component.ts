import { PersonNamePipe } from '../../shared/pipes/person-name.pipe';
import {
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { TranslateModule } from '@ngx-translate/core';
import { forkJoin, of, Subject, Subscription } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { PatientService, PatientResponse } from '../../services/patient.service';
import { patientSubline } from './patient-subline';

/**
 * Debounced patient typeahead with the shared chip/dropdown idiom.
 * Owns the search stream (including error recovery) so feature pages only
 * handle the selection: `(selectedChange)` emits the picked patient or null
 * when the chip is cleared.
 */
@Component({
  selector: 'app-patient-picker',
  standalone: true,
  imports: [PersonNamePipe, TranslateModule],
  templateUrl: './patient-picker.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './patient-picker.component.scss',
})
export class PatientPickerComponent implements OnInit, OnDestroy {
  private readonly patientService = inject(PatientService);

  /** id for the input element so an external <label for> keeps working. */
  @Input() inputId = 'patient-picker-input';
  /**
   * Hospital scope for name search. Exact identifiers (phone/email/MRN) also
   * query the cross-hospital lookup regardless of this input.
   */
  @Input() hospitalId: string | null = null;
  @Input() set selected(value: PatientResponse | null) {
    this.selectedPatient.set(value);
    if (!value) {
      this.query.set('');
      this.suggestions.set([]);
      this.dropdownOpen.set(false);
      this.noMatch.set(false);
    }
  }
  @Output() selectedChange = new EventEmitter<PatientResponse | null>();

  readonly selectedPatient = signal<PatientResponse | null>(null);
  readonly query = signal('');
  readonly suggestions = signal<PatientResponse[]>([]);
  readonly dropdownOpen = signal(false);
  readonly searchLoading = signal(false);
  /** The last finished search found nothing (QA D68): say so, not an empty box. */
  readonly noMatch = signal(false);
  private readonly search$ = new Subject<string>();
  private searchSub?: Subscription;

  ngOnInit(): void {
    this.searchSub = this.search$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        // catchError INSIDE switchMap: a failed lookup must not kill the stream.
        // Two sources merged: /patients/search matches names within the hospital
        // scope, /patients/lookup matches exact identifiers (phone/email/MRN)
        // across hospitals — the previous /patients?search call was silently
        // unfiltered because the backend never implemented that param.
        switchMap((q) => {
          this.searchLoading.set(true);
          this.noMatch.set(false);
          const scoped = this.patientService
            .search({
              name: q,
              hospitalId: this.hospitalId ?? undefined,
              size: 8,
              // QA D80: an admitted patient whose record is inactive must still
              // be found by the task, handoff and discharge pickers.
              includeInactive: true,
            })
            .pipe(catchError(() => of([] as PatientResponse[])));
          // Cross-hospital lookup fires only for EXACT identifiers (phone/email/
          // MRN) — free-text name fragments must never leave the hospital scope.
          const exact = this.looksLikeIdentifier(q)
            ? this.patientService
                .lookup({ identifier: q, hospitalId: this.hospitalId ?? undefined })
                .pipe(catchError(() => of([] as PatientResponse[])))
            : of([] as PatientResponse[]);
          return forkJoin([scoped, exact]);
        }),
      )
      .subscribe(([scoped, exact]) => {
        const merged: PatientResponse[] = [];
        for (const p of [...(exact ?? []), ...(scoped ?? [])]) {
          const kept = merged.findIndex((m) => m.id === p.id);
          if (kept < 0) {
            merged.push(p);
          } else if (!merged[kept].mrn?.trim() && p.mrn?.trim()) {
            // QA D903: the hospital-scoped search knows this hospital's MRN even
            // when the exact-identifier hit came back without one.
            merged[kept] = { ...merged[kept], mrn: p.mrn };
          }
        }
        const items = merged.slice(0, 8);
        this.suggestions.set(items);
        this.dropdownOpen.set(items.length > 0);
        this.noMatch.set(items.length === 0);
        this.searchLoading.set(false);
      });
  }

  ngOnDestroy(): void {
    this.searchSub?.unsubscribe();
  }

  /**
   * Phone-like (6+ digits/symbols), email-like, MRN-prefixed input, or a bare
   * MRN as the server issues it — a hospital prefix then digits, e.g. CHU0421
   * (D129: the maternity picker could not find a patient by MRN).
   */
  private looksLikeIdentifier(q: string): boolean {
    const trimmed = q.trim();
    return (
      trimmed.includes('@') ||
      /^mrn-/i.test(trimmed) ||
      /^[A-Za-z]{2,8}-?\d{3,}$/.test(trimmed) ||
      /^\+?[\d\s().-]{6,}$/.test(trimmed)
    );
  }

  onQueryChange(q: string): void {
    this.query.set(q);
    if (q.length >= 2) {
      this.search$.next(q);
    } else {
      this.suggestions.set([]);
      this.dropdownOpen.set(false);
      this.noMatch.set(false);
    }
  }

  select(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.dropdownOpen.set(false);
    this.query.set('');
    this.selectedChange.emit(p);
  }

  clear(): void {
    this.noMatch.set(false);
    this.selectedPatient.set(null);
    this.query.set('');
    this.suggestions.set([]);
    this.dropdownOpen.set(false);
    this.selectedChange.emit(null);
  }

  /**
   * QA D903: the line under the name identifies the patient AT THIS HOSPITAL —
   * the MRN the server resolved for the request's hospital scope. Contact
   * details are only the fallback for a record that carries no MRN (e.g. a
   * preselected patient rebuilt from a row that had none).
   */
  subline(p: PatientResponse): string {
    return patientSubline(p);
  }

  initials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }
}
