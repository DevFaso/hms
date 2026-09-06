import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  EventEmitter,
  OnInit,
  Output,
  ViewChild,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  merge,
  of,
  switchMap,
  tap,
} from 'rxjs';

import { HospitalResponse, HospitalService } from '../../services/hospital.service';

/**
 * Server-side debounced hospital typeahead used by the super-admin
 * cross-tenant scope chip (docs/super-admin-cross-tenant-design.md).
 *
 * The component owns the search box, the dropdown of matches, and the
 * "All hospitals" sentinel option; it emits `selectAll` when the user
 * picks the sentinel and `selectHospital` when the user picks a match.
 * The host (the scope chip) is responsible for the chip surface itself
 * and the URL-state sync.
 *
 * Why a dedicated component (and not just inlining into the chip):
 *   - reusable elsewhere (e.g. cross-tenant analytics filters)
 *   - keeps the debounce + cancellation logic in one place
 *   - the chip stays a thin wrapper around the typeahead + a label
 */
@Component({
  selector: 'app-hospital-typeahead',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './hospital-typeahead.component.html',
  styleUrl: './hospital-typeahead.component.scss',
})
export class HospitalTypeaheadComponent implements OnInit {
  private readonly hospitalService = inject(HospitalService);
  private readonly destroyRef = inject(DestroyRef);

  /** Hide the "All hospitals" sentinel (e.g. when this typeahead is used outside the scope chip). */
  readonly hideAllOption = input<boolean>(false);

  /** Auto-focus the input when the host renders the component (chip overlay does this). */
  readonly autoFocus = input<boolean>(true);

  @Output() readonly selectAll = new EventEmitter<void>();
  @Output() readonly selectHospital = new EventEmitter<HospitalResponse>();
  @Output() readonly dismiss = new EventEmitter<void>();

  @ViewChild('searchInput') private searchInputRef?: ElementRef<HTMLInputElement>;

  // Debounce window matches the design doc's 300 ms; below the
  // ~400 ms perception threshold for "feels instant".
  private static readonly DEBOUNCE_MS = 300;
  private static readonly LIMIT = 20;
  /** Exposed for the "type to narrow" row. */
  protected readonly limit = HospitalTypeaheadComponent.LIMIT;

  protected readonly query = signal<string>('');
  protected readonly results = signal<HospitalResponse[]>([]);
  protected readonly loading = signal<boolean>(false);
  protected readonly errored = signal<boolean>(false);
  /** A full page means there are more: say so instead of silently truncating. */
  protected readonly truncated = computed(
    () => this.results().length >= HospitalTypeaheadComponent.LIMIT,
  );

  /**
   * What the empty-results helper text should say: a "no matches" line once
   * a search resolved to zero rows, an error line when the backend failed,
   * nothing while loading. The list is populated from the first render, so
   * there is no "type more" state any more.
   */
  protected readonly emptyMessageKey = computed(() => {
    if (this.loading()) {
      return null;
    }
    if (this.errored()) {
      return 'HOSPITAL_SCOPE.SEARCH_ERROR';
    }
    if (this.results().length === 0) {
      return 'HOSPITAL_SCOPE.NO_MATCHES';
    }
    return null;
  });

  private readonly search$ = new Subject<string>();

  ngOnInit(): void {
    // The first page loads on open (empty query, first LIMIT by name): the
    // picker used to open on an empty list with only a placeholder to say
    // that typing was required. Keystrokes are debounced; the opener is not.
    merge(of(''), this.search$.pipe(debounceTime(HospitalTypeaheadComponent.DEBOUNCE_MS)))
      .pipe(
        distinctUntilChanged(),
        tap(() => {
          this.loading.set(true);
          this.errored.set(false);
        }),
        switchMap((q) =>
          this.hospitalService.searchHospitals(q, HospitalTypeaheadComponent.LIMIT).pipe(
            catchError(() => {
              this.errored.set(true);
              return of([] as HospitalResponse[]);
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((rows) => {
        this.results.set(rows);
        this.loading.set(false);
      });

    if (this.autoFocus()) {
      // Focus on the next macrotask so Angular has rendered the input.
      setTimeout(() => this.searchInputRef?.nativeElement.focus(), 0);
    }
  }

  protected onQueryChange(value: string): void {
    this.query.set(value);
    // An emptied box goes back to the first page, not to an empty list.
    this.search$.next(value.trim());
  }

  protected pickAll(): void {
    this.selectAll.emit();
  }

  protected pickHospital(hospital: HospitalResponse): void {
    this.selectHospital.emit(hospital);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.stopPropagation();
      this.dismiss.emit();
    }
  }
}
