import { allRows } from '../../shared/all-pages';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  EventEmitter,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  inject,
  input,
  signal,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, Subscription, debounceTime, switchMap, takeUntil } from 'rxjs';

import {
  appliedOrderCount,
  appliedResultKey,
  AppliedOrderSetSummary,
  OrderSetService,
  OrderSetSummary,
} from '../../services/order-set.service';
import { unTickedFrequencyVerdict } from '../../shared/as-needed-frequency';
import { CdsCardListComponent } from '../../shared/cds-card/cds-card.component';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { refusalReason } from '../../shared/refusal-reason';

type State = 'idle' | 'searching' | 'ready' | 'applying' | 'applied' | 'error';

/**
 * QA D1438: the words a stored item may name its route in, as the prescription
 * form's codes (MedicationRoutes on the server knows the same words). Older and
 * API-written items say ORAL where the editor writes PO.
 */
const ROUTE_CODES: Readonly<Record<string, string>> = {
  ORAL: 'PO',
  INTRAVENOUS: 'IV',
  INTRAMUSCULAR: 'IM',
  SUBCUTANEOUS: 'SC',
  RECTAL: 'PR',
  INHALATION: 'INH',
  TOP: 'TOPICAL',
};

/**
 * Modal-style picker for applying a CPOE order set to an admission.
 * Search-driven typeahead → preview the items → click "Apply" → posts
 * to the apply endpoint and shows the resulting order counts plus any
 * non-blocking CDS advisories returned by the medication fan-out.
 *
 * <p>Inputs are required for the apply flow to function:
 * {@code hospitalId}, {@code admissionId}, {@code encounterId},
 * {@code orderingStaffId}. Hosts wire them from the active admission
 * + signed-in clinician context.
 */
@Component({
  selector: 'app-order-set-picker',
  standalone: true,
  imports: [FormsModule, TranslateModule, CdsCardListComponent, EnumLabelPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="order-set-picker" data-testid="order-set-picker" [attr.data-state]="state()">
      <header class="order-set-picker__header">
        <h2>{{ 'ORDER_SETS.PICKER_TITLE' | translate }}</h2>
        <button
          type="button"
          class="close"
          [attr.aria-label]="'COMMON.CLOSE' | translate"
          (click)="closed.emit()"
          data-testid="order-set-picker-close"
        >
          ×
        </button>
      </header>

      <input
        type="search"
        class="order-set-picker__search"
        data-testid="order-set-picker-search"
        [placeholder]="'ORDER_SETS.SEARCH_PLACEHOLDER' | translate"
        [ngModel]="searchTerm()"
        (ngModelChange)="onSearch($event)"
      />

      @if (showList() && results().length > 0) {
        <ul class="order-set-picker__list" data-testid="order-set-picker-list">
          @for (os of results(); track trackById($index, os)) {
            <li
              role="button"
              tabindex="0"
              [class.order-set-picker__item--selected]="selected()?.id === os.id"
              (click)="select(os)"
              (keydown.enter)="select(os)"
              (keydown.space)="select(os); $event.preventDefault()"
              data-testid="order-set-picker-item"
            >
              <div class="order-set-picker__item-name">
                {{ os.name }} <small>v{{ os.version }}</small>
              </div>
              <div class="order-set-picker__item-meta">
                {{ os.orderCount }}
                {{ (os.orderCount === 1 ? 'ORDER_SETS.ITEM_ONE' : 'ORDER_SETS.ITEMS') | translate }}
                ·
                {{ os.admissionType | enumLabel: 'admissionType' }}
              </div>
            </li>
          }
        </ul>
      }

      @if (showList() && results().length === 0) {
        <p class="order-set-picker__empty" data-testid="order-set-picker-empty">
          {{ 'ORDER_SETS.NO_RESULTS' | translate }}
        </p>
      }

      @if (state() === 'searching') {
        <p class="order-set-picker__loading" data-testid="order-set-picker-loading">
          {{ 'ORDER_SETS.SEARCHING' | translate }}
        </p>
      }

      @if (state() === 'error' || applyFailed()) {
        <p class="order-set-picker__error" role="alert" data-testid="order-set-picker-error">
          {{ applyRefusal() || ('ORDER_SETS.ERROR' | translate) }}
        </p>
      }

      @if (selected(); as sel) {
        <section class="order-set-picker__preview" data-testid="order-set-picker-preview">
          <h3>{{ sel.name }}</h3>
          @if (sel.description) {
            <p>{{ sel.description }}</p>
          }
          <ul>
            @for (item of sel.orderItems; track item; let i = $index) {
              <li data-testid="order-set-picker-preview-item">
                <strong>{{ orderItemType(item) }}</strong
                >{{ 'COMMON.COLON' | translate }} {{ orderItemLabel(item) }}
                @if (orderItemDetail(item); as detail) {
                  <small class="order-set-picker__item-detail">{{ detail }}</small>
                }
              </li>
            }
          </ul>
          <!-- QA D1345 class (round 30): once applied, the preview offers no Apply
               (a second press created every order again); the result shows below. -->
          @if (appliedSummary() === null) {
            <button
              type="button"
              class="btn-primary"
              data-testid="order-set-picker-apply"
              [disabled]="applying()"
              (click)="apply(sel)"
            >
              {{ 'ORDER_SETS.APPLY' | translate }}
            </button>
          }
        </section>
      }

      @if (appliedSummary(); as summary) {
        <section class="order-set-picker__applied" data-testid="order-set-picker-applied">
          <p>
            {{
              resultKey(summary)
                | translate: { count: createdCount(summary), skipped: summary.skippedItemCount }
            }}
          </p>
          <app-cds-card-list [cards]="summary.cdsAdvisories" />
        </section>
      }
    </section>
  `,
  styles: [
    `
      .order-set-picker {
        background: var(--surface-1, #fff);
        border: 1px solid var(--border, #e5e5e5);
        border-radius: 8px;
        padding: 1rem;
        max-width: 720px;
      }
      .order-set-picker__header {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
      .order-set-picker__search {
        width: 100%;
        padding: 0.5rem;
        margin: 0.5rem 0;
      }
      .order-set-picker__list {
        list-style: none;
        padding: 0;
        max-height: 220px;
        overflow-y: auto;
      }
      .order-set-picker__list li {
        padding: 0.5rem;
        cursor: pointer;
        border-bottom: 1px solid var(--border, #eee);
      }
      .order-set-picker__list li:hover,
      .order-set-picker__item--selected {
        background: var(--surface-2, #f4f4f4);
      }
      .order-set-picker__error {
        color: var(--danger, #b00020);
      }
      .order-set-picker__item-detail {
        display: block;
        color: var(--text-secondary, #64748b);
      }
      .order-set-picker__preview {
        margin-top: 1rem;
        padding-top: 1rem;
        border-top: 1px solid var(--border, #eee);
      }
    `,
  ],
})
export class OrderSetPickerComponent implements OnChanges, OnDestroy {
  readonly hospitalId = input<string>('');
  readonly admissionId = input<string>('');
  readonly encounterId = input<string>('');
  readonly orderingStaffId = input<string>('');

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly applied = new EventEmitter<AppliedOrderSetSummary>();

  protected readonly state = signal<State>('idle');
  /** A request to apply the order set is in flight (the only reason "Apply" is disabled). */
  protected readonly applying = computed(() => this.state() === 'applying');
  protected readonly results = signal<OrderSetSummary[]>([]);
  protected readonly selected = signal<OrderSetSummary | null>(null);
  protected readonly searchTerm = signal<string>('');
  protected readonly appliedSummary = signal<AppliedOrderSetSummary | null>(null);
  /** QA D1396: the server's own reason an apply was refused, shown instead of the generic line. */
  protected readonly applyRefusal = signal<string | null>(null);
  /** An apply failed: said on the page, and the list stays to pick another set. */
  protected readonly applyFailed = signal(false);
  /** The list while nothing is being read, and while a set is being applied. */
  protected readonly showList = computed(
    () => this.state() === 'ready' || this.state() === 'applying',
  );

  private readonly orderSetService = inject(OrderSetService);
  private readonly translate = inject(TranslateService);
  private readonly searchSubject = new Subject<string>();
  private readonly destroyed$ = new Subject<void>();
  private applySub?: Subscription;

  constructor() {
    this.searchSubject
      .pipe(
        debounceTime(250),
        takeUntil(this.destroyed$),
        // Guard: when the host component renders before its hospitalId
        // input binding has propagated, an immediate request would hit
        // /api/order-sets?hospitalId= and put the picker into an error
        // state. Skip those frames; ngOnChanges nudges the subject again
        // once the input is set.
        switchMap((term) => {
          const hid = this.hospitalId();
          if (!hid) {
            this.state.set('idle');
            return [];
          }
          // QA D1345 class: a search landing mid-apply never re-enables "Apply".
          if (this.state() !== 'applying') this.state.set('searching');
          // QA D1347: every order set the search matches (all pages), not the first 20.
          return allRows((page, size) => this.orderSetService.list(hid, term, page, size));
        }),
      )
      .subscribe({
        next: (rows) => {
          this.results.set(rows);
          if (this.state() !== 'applying') this.state.set('ready');
        },
        error: () => {
          this.results.set([]);
          if (this.state() !== 'applying') this.state.set('error');
        },
      });
    this.searchSubject.next('');
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['hospitalId'] && this.hospitalId()) {
      // Replay so the search-pipe has a non-empty hospitalId on hand.
      this.searchSubject.next(this.searchTerm());
    }
  }

  ngOnDestroy(): void {
    this.applySub?.unsubscribe();
    this.destroyed$.next();
    this.destroyed$.complete();
  }

  protected onSearch(value: string): void {
    this.searchTerm.set(value);
    this.searchSubject.next(value);
  }

  protected select(os: OrderSetSummary): void {
    this.selected.set(os);
    this.appliedSummary.set(null);
    this.applyRefusal.set(null);
    this.applyFailed.set(false);
  }

  protected apply(os: OrderSetSummary): void {
    // QA D1345 class (round 30): an applied set is never applied again from
    // the same preview (a second press created every order a second time);
    // picking a set again starts a new preview.
    if (this.appliedSummary() !== null || this.applying()) return;
    this.applySub?.unsubscribe();
    this.applyRefusal.set(null);
    this.applyFailed.set(false);
    this.state.set('applying');
    this.applySub = this.orderSetService
      .apply(os.id, this.admissionId(), {
        encounterId: this.encounterId(),
        orderingStaffId: this.orderingStaffId(),
      })
      .subscribe({
        next: (summary) => {
          this.appliedSummary.set(summary);
          this.state.set('applied');
          this.applied.emit(summary);
        },
        error: (err: unknown) => {
          this.applyRefusal.set(refusalReason(err));
          this.applyFailed.set(true);
          this.state.set('ready');
        },
      });
  }

  protected trackById(_index: number, os: OrderSetSummary): string {
    return os.id;
  }

  /** QA D1405: the item's type in the reader's language, never the wire code. */
  protected orderItemType(item: Record<string, unknown>): string {
    return this.labelFor('ORDER_SETS.ITEM_TYPE', String(item['orderType'] ?? '?'));
  }

  protected createdCount(summary: AppliedOrderSetSummary): number {
    return appliedOrderCount(summary);
  }

  protected resultKey(summary: AppliedOrderSetSummary): string {
    return appliedResultKey(appliedOrderCount(summary));
  }

  /**
   * QA D1405: what tells two items of one drug apart: dose, route, frequency,
   * the as-needed interval, the quantity. Empty for an item with none of them.
   */
  protected orderItemDetail(item: Record<string, unknown>): string {
    const interval = item['prnMinIntervalHours'];
    const parts = [
      this.doseText(item),
      this.routeText(item['route']),
      this.codedText('ORDER_SETS.FREQUENCY', item['frequency']),
      this.asNeededText(item),
      interval
        ? this.translate.instant('PRESCRIPTIONS.PRN_MIN_INTERVAL_VALUE', { hours: interval })
        : '',
      item['quantity']
        ? this.translate.instant('ORDER_SETS.QUANTITY_VALUE', {
            value: item['quantity'],
            unit: item['quantityUnit'] ?? '',
          })
        : '',
    ];
    return parts
      .map((p) => String(p).trim())
      .filter((p) => p !== '')
      .join(' · ');
  }

  /** QA D1439: "as needed" is said once; a frequency that already says it is enough. */
  private asNeededText(item: Record<string, unknown>): string {
    if (item['asNeeded'] !== true) return '';
    const frequency = item['frequency'];
    const says =
      typeof frequency === 'string' &&
      (frequency.trim().toUpperCase() === 'PRN' ||
        unTickedFrequencyVerdict(frequency) === 'CONDITIONAL');
    return says ? '' : this.translate.instant('ORDER_SETS.FREQUENCY.PRN');
  }

  /** QA D1438: the route in the reader's language, whichever word the item stored it in. */
  private routeText(route: unknown): string {
    if (typeof route !== 'string') return '';
    const code = route.trim().toUpperCase();
    return this.codedText('ORDER_SETS.ROUTE', ROUTE_CODES[code] ?? code) || route.trim();
  }

  private doseText(item: Record<string, unknown>): string {
    const dose = item['dose'];
    if ((typeof dose === 'string' && dose.trim()) || typeof dose === 'number') {
      // QA D1436: a bare number and its unit read together, as on the order.
      const written = String(dose).trim();
      const unit = item['doseUnit'];
      return unit && /^[\d.,\s-]+$/.test(written) ? `${written} ${unit as string}` : written;
    }
    const min = item['doseRangeMin'];
    const max = item['doseRangeMax'];
    const range = min != null && max != null && min !== max ? `${min}-${max}` : (min ?? max ?? '');
    if (range === '') return '';
    const unit = item['doseUnit'];
    return unit ? `${range as string | number} ${unit as string}` : String(range);
  }

  private codedText(prefix: string, code: unknown): string {
    return typeof code === 'string' && code.trim() ? this.labelFor(prefix, code) : '';
  }

  /** The translation of a coded value, or the value as written when it is not a known code. */
  private labelFor(prefix: string, code: string): string {
    const key = `${prefix}.${code}`;
    const label: string = this.translate.instant(key);
    return label === key ? code : label;
  }

  protected orderItemLabel(item: Record<string, unknown>): string {
    return String(
      item['medicationName'] ?? item['orderName'] ?? item['testName'] ?? item['studyType'] ?? '',
    );
  }
}
