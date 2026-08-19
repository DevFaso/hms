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
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, Subscription, takeUntil } from 'rxjs';

import { CdsCardListComponent } from '../../shared/cds-card/cds-card.component';
import { CdsCard } from '../../shared/cds-card/cds-card.model';
import { BpaService } from '../../services/bpa.service';
import {
  CdsAcknowledgementAction,
  CdsAcknowledgementService,
} from '../../services/cds-acknowledgement.service';
import { ToastService } from '../../core/toast.service';

/**
 * Best-Practice Advisory panel rendered at the top of the patient
 * chart. Pairs the inline {@link CdsCardListComponent} with a modal
 * pop-up that auto-opens for {@code critical} cards (gap #18 polish):
 * the clinician must acknowledge or override with a documented reason
 * before the modal closes; the recorded {@code CdsAcknowledgement}
 * suppresses the same card on subsequent rule-engine runs.
 *
 * <p>Failure mode: a network error degrades to the empty/error
 * state and never throws — BPAs are advisory and must not block
 * chart load.
 */
@Component({
  selector: 'app-bpa-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, CdsCardListComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './bpa-panel.component.html',
  styleUrls: ['./bpa-panel.component.scss'],
})
export class BpaPanelComponent implements OnChanges, OnDestroy {
  readonly patientId = input<string | null | undefined>(null);
  readonly encounterId = input<string | null | undefined>(null);

  protected readonly cards = signal<CdsCard[]>([]);
  protected readonly state = signal<'loading' | 'ready' | 'empty' | 'error'>('loading');
  protected readonly criticalCard = signal<CdsCard | null>(null);
  protected reason = '';
  protected readonly submitting = signal(false);

  private readonly bpa = inject(BpaService);
  private readonly ackService = inject(CdsAcknowledgementService);
  private readonly toast = inject(ToastService);
  private readonly destroyed$ = new Subject<void>();
  private inFlight?: Subscription;

  ngOnChanges(_changes: SimpleChanges): void {
    const id = this.patientId();
    if (!id) {
      this.cancelInFlight();
      this.cards.set([]);
      this.state.set('empty');
      this.criticalCard.set(null);
      return;
    }
    this.load(id, this.encounterId() ?? undefined);
  }

  ngOnDestroy(): void {
    this.cancelInFlight();
    this.destroyed$.next();
    this.destroyed$.complete();
  }

  protected acknowledge(): void {
    this.submitDecision('ACKNOWLEDGED');
  }

  protected override(): void {
    if (!this.reason.trim()) {
      this.toast.error('Please provide a reason to override this critical advisory.');
      return;
    }
    this.submitDecision('OVERRIDDEN');
  }

  protected dismissModal(): void {
    this.criticalCard.set(null);
    this.reason = '';
  }

  private submitDecision(action: CdsAcknowledgementAction): void {
    const card = this.criticalCard();
    const patientId = this.patientId();
    if (!card || !patientId || this.submitting()) return;

    this.submitting.set(true);
    this.ackService
      .record({
        patientId,
        cardUuid: card.uuid ?? null,
        cardSummary: card.summary,
        indicator: card.indicator,
        action,
        reason: this.reason.trim() || null,
      })
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.dismissModal();
          this.cards.update((list) => list.filter((c) => c !== card));
          if (this.cards().length === 0) {
            this.state.set('empty');
          }
        },
        error: () => {
          this.submitting.set(false);
          this.toast.error('Could not record advisory acknowledgement.');
        },
      });
  }

  /**
   * Cancel the previous request before issuing a new one. Without this,
   * a stale response from a prior `patientId` could land *after* a newer
   * one and overwrite the panel state for the wrong patient.
   */
  private load(patientId: string, encounterId?: string): void {
    this.cancelInFlight();
    this.state.set('loading');
    this.criticalCard.set(null);
    this.reason = '';
    this.inFlight = this.bpa
      .evaluate(patientId, encounterId)
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: (cards) => {
          const list = cards ?? [];
          this.cards.set(list);
          this.state.set(list.length > 0 ? 'ready' : 'empty');
          const critical = list.find((c) => c.indicator === 'critical');
          if (critical) {
            this.criticalCard.set(critical);
          }
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
