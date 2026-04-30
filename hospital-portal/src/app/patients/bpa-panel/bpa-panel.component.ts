import {
  ChangeDetectionStrategy,
  Component,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  inject,
  input,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, Subscription, takeUntil } from 'rxjs';

import { CdsCardListComponent } from '../../shared/cds-card/cds-card.component';
import { CdsCard } from '../../shared/cds-card/cds-card.model';
import { BpaService } from '../../services/bpa.service';

/**
 * Best-Practice Advisory panel rendered at the top of the patient
 * chart. On every {@code patientId} change it calls
 * {@link BpaService} which posts to
 * {@code /api/cds-services/hms-bpa-protocols} and renders any
 * advisory cards via the shared {@link CdsCardListComponent}.
 *
 * <p>Failure mode: a network error degrades to the empty/error
 * state and never throws — BPAs are advisory and must not block
 * chart load.
 */
@Component({
  selector: 'app-bpa-panel',
  standalone: true,
  imports: [CommonModule, TranslateModule, CdsCardListComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="bpa-panel"
      data-testid="bpa-panel"
      *ngIf="patientId()"
      [attr.data-state]="state()"
    >
      <header class="bpa-panel__header">
        <h2 class="bpa-panel__title">{{ 'BPA.HEADING' | translate }}</h2>
      </header>

      <p *ngIf="state() === 'loading'" class="bpa-panel__loading" data-testid="bpa-panel-loading">
        {{ 'BPA.LOADING' | translate }}
      </p>

      <p *ngIf="state() === 'error'" class="bpa-panel__error" data-testid="bpa-panel-error">
        {{ 'BPA.ERROR' | translate }}
      </p>

      <p *ngIf="state() === 'empty'" class="bpa-panel__empty" data-testid="bpa-panel-empty">
        {{ 'BPA.NONE_ACTIVE' | translate }}
      </p>

      <app-cds-card-list
        *ngIf="state() === 'ready'"
        [cards]="cards()"
        data-testid="bpa-panel-cards"
      />
    </section>
  `,
  styles: [
    `
      .bpa-panel {
        background: var(--surface-2, #fafafa);
        border: 1px solid var(--border, #e5e5e5);
        border-radius: 6px;
        padding: 1rem;
        margin-bottom: 1rem;
      }
      .bpa-panel__title {
        margin: 0 0 0.5rem;
        font-size: 1rem;
        font-weight: 600;
      }
      .bpa-panel__loading,
      .bpa-panel__empty,
      .bpa-panel__error {
        margin: 0;
        font-size: 0.875rem;
        color: var(--text-muted, #666);
      }
      .bpa-panel__error {
        color: var(--danger, #b00020);
      }
    `,
  ],
})
export class BpaPanelComponent implements OnChanges, OnDestroy {
  readonly patientId = input<string | null | undefined>(null);
  readonly encounterId = input<string | null | undefined>(null);

  protected readonly cards = signal<CdsCard[]>([]);
  protected readonly state = signal<'loading' | 'ready' | 'empty' | 'error'>('loading');

  private readonly bpa = inject(BpaService);
  private readonly destroyed$ = new Subject<void>();
  private inFlight?: Subscription;

  ngOnChanges(_changes: SimpleChanges): void {
    const id = this.patientId();
    if (!id) {
      this.cancelInFlight();
      this.cards.set([]);
      this.state.set('empty');
      return;
    }
    this.load(id, this.encounterId() ?? undefined);
  }

  ngOnDestroy(): void {
    this.cancelInFlight();
    this.destroyed$.next();
    this.destroyed$.complete();
  }

  /**
   * Cancel the previous request before issuing a new one. Without this,
   * a stale response from a prior `patientId` could land *after* a newer
   * one and overwrite the panel state for the wrong patient.
   */
  private load(patientId: string, encounterId?: string): void {
    this.cancelInFlight();
    this.state.set('loading');
    this.inFlight = this.bpa
      .evaluate(patientId, encounterId)
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: (cards) => {
          this.cards.set(cards ?? []);
          this.state.set((cards ?? []).length > 0 ? 'ready' : 'empty');
        },
        error: () => {
          this.cards.set([]);
          this.state.set('error');
        },
      });
  }

  private cancelInFlight(): void {
    this.inFlight?.unsubscribe();
    this.inFlight = undefined;
  }
}
