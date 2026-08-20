import {
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { forkJoin, of, Subject, Subscription } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { PatientService, PatientResponse } from '../../services/patient.service';

/**
 * Debounced patient typeahead with the shared chip/dropdown idiom.
 * Owns the search stream (including error recovery) so feature pages only
 * handle the selection: `(selectedChange)` emits the picked patient or null
 * when the chip is cleared.
 */
@Component({
  selector: 'app-patient-picker',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './patient-picker.component.html',
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
    }
  }
  @Output() selectedChange = new EventEmitter<PatientResponse | null>();

  readonly selectedPatient = signal<PatientResponse | null>(null);
  readonly query = signal('');
  readonly suggestions = signal<PatientResponse[]>([]);
  readonly dropdownOpen = signal(false);
  readonly searchLoading = signal(false);
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
          const scoped = this.patientService
            .search({ name: q, hospitalId: this.hospitalId ?? undefined, size: 8 })
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
          if (!merged.some((m) => m.id === p.id)) merged.push(p);
        }
        const items = merged.slice(0, 8);
        this.suggestions.set(items);
        this.dropdownOpen.set(items.length > 0);
        this.searchLoading.set(false);
      });
  }

  ngOnDestroy(): void {
    this.searchSub?.unsubscribe();
  }

  /** Phone-like (6+ digits/symbols), email-like, or MRN-prefixed input. */
  private looksLikeIdentifier(q: string): boolean {
    const trimmed = q.trim();
    return trimmed.includes('@') || /^mrn-/i.test(trimmed) || /^\+?[\d\s().-]{6,}$/.test(trimmed);
  }

  onQueryChange(q: string): void {
    this.query.set(q);
    if (q.length >= 2) {
      this.search$.next(q);
    } else {
      this.suggestions.set([]);
      this.dropdownOpen.set(false);
    }
  }

  select(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.dropdownOpen.set(false);
    this.query.set('');
    this.selectedChange.emit(p);
  }

  clear(): void {
    this.selectedPatient.set(null);
    this.query.set('');
    this.suggestions.set([]);
    this.dropdownOpen.set(false);
    this.selectedChange.emit(null);
  }

  initials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }
}
