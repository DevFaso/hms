import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  inject,
  input,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  Subject,
  Subscription,
  debounceTime,
  distinctUntilChanged,
  switchMap,
  takeUntil,
} from 'rxjs';

import {
  AppliedOrderSetSummary,
  OrderSetService,
  OrderSetSummary,
} from '../../services/order-set.service';
import { CdsCardListComponent } from '../../shared/cds-card/cds-card.component';

type State = 'idle' | 'searching' | 'ready' | 'applying' | 'applied' | 'error';

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
  imports: [CommonModule, FormsModule, TranslateModule, CdsCardListComponent],
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

      <ul
        class="order-set-picker__list"
        data-testid="order-set-picker-list"
        *ngIf="state() === 'ready' && results().length > 0"
      >
        <li
          *ngFor="let os of results(); trackBy: trackById"
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
            {{ os.orderCount }} {{ 'ORDER_SETS.ITEMS' | translate }} · {{ os.admissionType }}
          </div>
        </li>
      </ul>

      <p
        *ngIf="state() === 'ready' && results().length === 0"
        class="order-set-picker__empty"
        data-testid="order-set-picker-empty"
      >
        {{ 'ORDER_SETS.NO_RESULTS' | translate }}
      </p>

      <p
        *ngIf="state() === 'searching'"
        class="order-set-picker__loading"
        data-testid="order-set-picker-loading"
      >
        {{ 'ORDER_SETS.SEARCHING' | translate }}
      </p>

      <p
        *ngIf="state() === 'error'"
        class="order-set-picker__error"
        data-testid="order-set-picker-error"
      >
        {{ 'ORDER_SETS.ERROR' | translate }}
      </p>

      <section
        *ngIf="selected() as sel"
        class="order-set-picker__preview"
        data-testid="order-set-picker-preview"
      >
        <h3>{{ sel.name }}</h3>
        <p *ngIf="sel.description">{{ sel.description }}</p>
        <ul>
          <li *ngFor="let item of sel.orderItems; let i = index">
            <strong>{{ orderItemType(item) }}</strong
            >: {{ orderItemLabel(item) }}
          </li>
        </ul>
        <button
          type="button"
          class="btn-primary"
          data-testid="order-set-picker-apply"
          [disabled]="state() === 'applying'"
          (click)="apply(sel)"
        >
          {{ 'ORDER_SETS.APPLY' | translate }}
        </button>
      </section>

      <section
        *ngIf="appliedSummary() as summary"
        class="order-set-picker__applied"
        data-testid="order-set-picker-applied"
      >
        <p>
          {{
            'ORDER_SETS.APPLIED_RESULT'
              | translate
                : {
                    count:
                      summary.prescriptionIds.length +
                      summary.labOrderIds.length +
                      summary.imagingOrderIds.length,
                    skipped: summary.skippedItemCount,
                  }
          }}
        </p>
        <app-cds-card-list [cards]="summary.cdsAdvisories" />
      </section>
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
  protected readonly results = signal<OrderSetSummary[]>([]);
  protected readonly selected = signal<OrderSetSummary | null>(null);
  protected readonly searchTerm = signal<string>('');
  protected readonly appliedSummary = signal<AppliedOrderSetSummary | null>(null);

  private readonly orderSetService = inject(OrderSetService);
  private readonly searchSubject = new Subject<string>();
  private readonly destroyed$ = new Subject<void>();
  private applySub?: Subscription;

  constructor() {
    this.searchSubject
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
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
          this.state.set('searching');
          return this.orderSetService.list(hid, term);
        }),
      )
      .subscribe({
        next: (page) => {
          this.results.set(page.content ?? []);
          this.state.set('ready');
        },
        error: () => {
          this.results.set([]);
          this.state.set('error');
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
  }

  protected apply(os: OrderSetSummary): void {
    this.applySub?.unsubscribe();
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
        error: () => this.state.set('error'),
      });
  }

  protected trackById(_index: number, os: OrderSetSummary): string {
    return os.id;
  }

  protected orderItemType(item: Record<string, unknown>): string {
    return String(item['orderType'] ?? '?');
  }

  protected orderItemLabel(item: Record<string, unknown>): string {
    return String(
      item['medicationName'] ?? item['orderName'] ?? item['testName'] ?? item['studyType'] ?? '',
    );
  }
}
