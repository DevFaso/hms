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
import { of, Subject, Subscription } from 'rxjs';
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
  /** Restrict the lookup to one hospital (omit for cross-hospital search). */
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
        switchMap((q) => {
          this.searchLoading.set(true);
          return this.patientService
            .list(this.hospitalId ?? undefined, q)
            .pipe(catchError(() => of([] as PatientResponse[])));
        }),
      )
      .subscribe((list) => {
        const items = (list ?? []).slice(0, 8);
        this.suggestions.set(items);
        this.dropdownOpen.set(items.length > 0);
        this.searchLoading.set(false);
      });
  }

  ngOnDestroy(): void {
    this.searchSub?.unsubscribe();
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
