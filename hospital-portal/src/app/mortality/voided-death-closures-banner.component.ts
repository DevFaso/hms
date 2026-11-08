import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnChanges,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule } from '@ngx-translate/core';

import { MortalityService, VoidedDeathClosures } from '../services/mortality.service';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';

/**
 * QA D1516: on the chart, what a death later marked entered in error had closed at this hospital. Nothing was
 * reopened (#1068), so the next clinician sees which stays, visits and appointments to readmit or rebook. Shows
 * nothing when there is nothing, or when the read fails: it is a notice, never a reason the chart does not load.
 */
@Component({
  selector: 'app-voided-death-closures-banner',
  standalone: true,
  imports: [TranslateModule, DatePipe, DateTimePipe, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .voided-closures {
        margin: 0 0 1rem;
        padding: 0.75rem 1rem;
        border-left: 4px solid var(--warning, #b26a00);
        background: var(--warning-bg, #fff4e5);
        border-radius: 6px;
      }
      .voided-closures h2 {
        font-size: 1rem;
        margin: 0 0 0.25rem;
      }
      .voided-closures ul {
        margin: 0.25rem 0 0.5rem 1.25rem;
        padding: 0;
      }
      .voided-closures p {
        margin: 0.25rem 0;
      }
    `,
  ],
  template: `
    @for (voided of closures(); track voided.deathRecordId) {
      <section
        class="voided-closures"
        role="status"
        [attr.aria-labelledby]="'voided-closures-' + voided.deathRecordId"
        data-testid="voided-death-closures"
      >
        <h2 [id]="'voided-closures-' + voided.deathRecordId">
          {{
            'MORTALITY.VOIDED_CLOSURES.TITLE'
              | translate: { date: voided.enteredInErrorAt | serverTime | dateTime }
          }}
        </h2>
        <p>{{ 'MORTALITY.VOIDED_CLOSURES.NOT_REOPENED' | translate }}</p>
        @if (voided.stays.length) {
          <p>{{ 'MORTALITY.VOIDED_CLOSURES.STAYS' | translate }}</p>
          <ul>
            @for (stay of voided.stays; track stay.admissionId) {
              <li data-testid="voided-closure-stay">
                {{
                  'MORTALITY.VOIDED_CLOSURES.STAY_LINE'
                    | translate
                      : {
                          admitted: stay.admittedAt | serverTime | dateTime,
                          closed: stay.closedAt | serverTime | dateTime,
                        }
                }}
              </li>
            }
          </ul>
        }
        @if (voided.visits.length) {
          <p>{{ 'MORTALITY.VOIDED_CLOSURES.VISITS' | translate }}</p>
          <ul>
            @for (visit of voided.visits; track visit.encounterId) {
              <li data-testid="voided-closure-visit">
                {{ visit.visitAt | serverTime | dateTime }}
              </li>
            }
          </ul>
        }
        @if (voided.appointments.length) {
          <p>{{ 'MORTALITY.VOIDED_CLOSURES.APPOINTMENTS' | translate }}</p>
          <ul>
            @for (booking of voided.appointments; track booking.appointmentId) {
              <li data-testid="voided-closure-appointment">
                {{ booking.appointmentDate | serverTime | date: 'mediumDate' }}
              </li>
            }
          </ul>
        }
      </section>
    }
  `,
})
export class VoidedDeathClosuresBannerComponent implements OnChanges {
  private readonly mortality = inject(MortalityService);
  private readonly destroyRef = inject(DestroyRef);

  readonly patientId = input.required<string>();
  readonly closures = signal<VoidedDeathClosures[]>([]);
  private request = 0;

  ngOnChanges(): void {
    const id = this.patientId();
    const ticket = ++this.request;
    this.closures.set([]);
    if (!id) {
      return;
    }
    this.mortality
      .voidedClosures(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (ticket === this.request) {
            this.closures.set(rows ?? []);
          }
        },
        error: () => {
          if (ticket === this.request) {
            this.closures.set([]);
          }
        },
      });
  }
}
