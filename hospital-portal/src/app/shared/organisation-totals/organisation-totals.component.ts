import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { HospitalService, OrganisationSummary } from '../../services/hospital.service';
import { CfaPipe } from '../pipes/cfa.pipe';
import { DayMonthPipe } from '../pipes/day-month.pipe';
import { LocaleNumberPipe } from '../pipes/lab-value.pipe';

type TotalsState = 'loading' | 'ready' | 'failed';

/**
 * Platform admin rules 2026-11-20, rule 1b: what an organisation admin
 * (ROLE_ADMIN) reads about its hospitals: totals per hospital for the current
 * month, and nothing about a person.
 *
 * <p>The server decides the hospitals (the caller's own organisation) and
 * withholds a small count in a sensitive category; this card prints what it
 * is given. A withheld count reads « moins de 5 », never a number: the value
 * does not reach the browser at all.
 */
@Component({
  selector: 'app-organisation-totals',
  standalone: true,
  imports: [TranslateModule, CfaPipe, DayMonthPipe, LocaleNumberPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="ot-card" data-testid="organisation-totals" aria-labelledby="ot-title">
      <header class="ot-head">
        <h2 id="ot-title" class="ot-title">{{ 'PLATFORM_SETUP.ORG_TOTALS.TITLE' | translate }}</h2>
        @if (summary(); as shown) {
          <p class="ot-sub" data-testid="organisation-totals-period">
            {{
              'PLATFORM_SETUP.ORG_TOTALS.PERIOD'
                | translate
                  : {
                      from: shown.from | dayMonth: undefined : true,
                      to: shown.to | dayMonth: undefined : true,
                    }
            }}
          </p>
        }
        <p class="ot-note">{{ 'PLATFORM_SETUP.ORG_TOTALS.NOTE' | translate }}</p>
      </header>

      @if (state() === 'loading') {
        <p class="ot-state" role="status">{{ 'PLATFORM_SETUP.ORG_TOTALS.LOADING' | translate }}</p>
      } @else if (state() === 'failed') {
        <div class="ot-failed" role="alert" data-testid="organisation-totals-failed">
          <span>{{ 'PLATFORM_SETUP.ORG_TOTALS.LOAD_FAILED' | translate }}</span>
          <button type="button" class="ot-retry" (click)="load()">
            {{ 'PLATFORM_SETUP.ORG_TOTALS.RETRY' | translate }}
          </button>
        </div>
      } @else {
        @if (summary(); as shown) {
          @if (shown.hospitals.length === 0) {
            <p class="ot-state">{{ 'PLATFORM_SETUP.ORG_TOTALS.EMPTY' | translate }}</p>
          } @else {
            <ul class="ot-list">
              @for (row of shown.hospitals; track row.hospitalId) {
                <li class="ot-row" data-testid="organisation-totals-row">
                  <h3 class="ot-hospital">
                    {{ row.hospitalName }}
                    <span
                      class="ot-currency"
                      [attr.aria-label]="
                        ('PLATFORM_SETUP.HOSPITAL.CURRENCY' | translate) +
                        ('COMMON.COLON' | translate) +
                        ' ' +
                        row.currencyCode
                      "
                      >{{ row.currencyCode }}</span
                    >
                  </h3>
                  <dl class="ot-figures">
                    <div class="ot-figure">
                      <dt>{{ 'PLATFORM_SETUP.ORG_TOTALS.STAFF' | translate }}</dt>
                      <dd>{{ row.activeStaff | localeNumber }}</dd>
                    </div>
                    <div class="ot-figure">
                      <dt>{{ 'PLATFORM_SETUP.ORG_TOTALS.APPOINTMENTS' | translate }}</dt>
                      <dd>{{ row.appointments | localeNumber }}</dd>
                    </div>
                    <div class="ot-figure">
                      <dt>{{ 'PLATFORM_SETUP.ORG_TOTALS.ADMISSIONS' | translate }}</dt>
                      <dd>{{ row.admissions | localeNumber }}</dd>
                    </div>
                    <div class="ot-figure">
                      <dt>{{ 'PLATFORM_SETUP.ORG_TOTALS.INVOICED' | translate }}</dt>
                      <dd>{{ row.invoicedTotal | cfa: row.currencyCode }}</dd>
                    </div>
                    <div class="ot-figure">
                      <dt>{{ 'PLATFORM_SETUP.ORG_TOTALS.COLLECTED' | translate }}</dt>
                      <dd>{{ row.collectedTotal | cfa: row.currencyCode }}</dd>
                    </div>
                    <div class="ot-figure">
                      <dt>{{ 'PLATFORM_SETUP.ORG_TOTALS.DEATHS' | translate }}</dt>
                      @if (row.deaths.suppressed) {
                        <dd class="ot-withheld" data-testid="organisation-totals-withheld">
                          {{
                            'PLATFORM_SETUP.ORG_TOTALS.FEWER_THAN'
                              | translate: { threshold: shown.smallCountThreshold }
                          }}
                        </dd>
                      } @else {
                        <dd>{{ row.deaths.value ?? 0 | localeNumber }}</dd>
                      }
                    </div>
                  </dl>
                </li>
              }
            </ul>
            <p class="ot-note">
              {{
                'PLATFORM_SETUP.ORG_TOTALS.SMALL_COUNTS'
                  | translate: { threshold: shown.smallCountThreshold }
              }}
            </p>
          }
        }
      }
    </section>
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .ot-card {
        background: #fff;
        border: 1px solid var(--border);
        border-radius: 12px;
        padding: 16px;
        margin-bottom: 16px;
        /* A long hospital name wraps rather than widening the card. */
        overflow-wrap: anywhere;
      }
      .ot-head {
        margin-bottom: 12px;
      }
      .ot-title {
        margin: 0;
        font-size: 1.0625rem;
        color: var(--text-primary);
      }
      .ot-sub {
        margin: 2px 0 0;
        font-size: 0.875rem;
        color: var(--text-secondary);
      }
      .ot-note {
        margin: 6px 0 0;
        font-size: 0.8125rem;
        color: #475569;
      }
      .ot-state {
        margin: 0;
        font-size: 0.875rem;
        color: #475569;
      }
      .ot-failed {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px 12px;
        padding: 10px 12px;
        border-radius: 8px;
        background: #fef2f2;
        color: #991b1b;
        font-size: 0.875rem;
      }
      .ot-retry {
        min-height: 36px;
        padding: 6px 12px;
        border: 1px solid #991b1b;
        border-radius: 8px;
        background: #fff;
        color: #991b1b;
        font: inherit;
        font-weight: 600;
        cursor: pointer;
      }
      .ot-list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 10px;
      }
      .ot-row {
        min-width: 0;
        padding: 12px;
        border: 1px solid var(--border);
        border-radius: 8px;
      }
      .ot-hospital {
        margin: 0 0 8px;
        font-size: 0.9375rem;
        font-weight: 600;
        color: var(--text-primary);
      }
      .ot-currency {
        display: inline-block;
        margin-left: 6px;
        padding: 1px 8px;
        border-radius: 999px;
        font-size: 0.75rem;
        font-weight: 600;
        background: var(--primary-tint);
        color: var(--primary-deep);
      }
      .ot-figures {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
        gap: 8px 16px;
        margin: 0;
      }
      .ot-figure {
        min-width: 0;
      }
      .ot-figure dt {
        font-size: 0.8125rem;
        color: #475569;
      }
      .ot-figure dd {
        margin: 2px 0 0;
        font-size: 1rem;
        font-weight: 600;
        color: var(--text-primary);
        font-variant-numeric: tabular-nums;
      }
      .ot-figure dd.ot-withheld {
        font-weight: 500;
        color: #475569;
      }
    `,
  ],
})
export class OrganisationTotalsComponent implements OnInit {
  private readonly hospitals = inject(HospitalService);

  protected readonly state = signal<TotalsState>('loading');
  protected readonly summary = signal<OrganisationSummary | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.state.set('loading');
    this.hospitals.organisationSummary().subscribe({
      next: (summary) => {
        this.summary.set({ ...summary, hospitals: summary?.hospitals ?? [] });
        this.state.set('ready');
      },
      error: () => {
        this.summary.set(null);
        this.state.set('failed');
      },
    });
  }
}
