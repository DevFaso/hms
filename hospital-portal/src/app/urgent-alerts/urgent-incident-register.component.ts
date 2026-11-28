import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RoleContextService } from '../core/role-context.service';
import { UrgentAlertIncident, UrgentAlertsService } from '../services/urgent-alerts.service';
import { PageHeaderComponent } from '../shared/page-header/page-header.component';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { refusalReason } from '../shared/refusal-reason';

/** The incidents of one patient opened on one day, read as one case. */
export interface IncidentCase {
  key: string;
  patientId: string | null;
  patientName: string | null;
  open: number;
  openedAt: string;
  lines: UrgentAlertIncident[];
}

/** The steps of the ladder an alert has taken, as translation keys; the reminders are counted apart. */
export function stepKeys(stepsTaken: number | null): string[] {
  const steps = stepsTaken ?? 0;
  const keys: string[] = [];
  if (steps >= 1) keys.push('URGENT_INCIDENTS.STEP_COVER');
  if (steps >= 2) keys.push('URGENT_INCIDENTS.STEP_LEAD');
  if (steps >= 3) keys.push('URGENT_INCIDENTS.STEP_INCIDENT');
  return keys;
}

/** The reminders sent after the incident opened. */
export function reminders(stepsTaken: number | null): number {
  return Math.max(0, (stepsTaken ?? 0) - 3);
}

/** The register's lines as cases, an open case first, then the most recently opened. */
export function groupCases(lines: UrgentAlertIncident[]): IncidentCase[] {
  const cases = new Map<string, IncidentCase>();
  for (const line of lines) {
    let found = cases.get(line.caseKey);
    if (!found) {
      found = {
        key: line.caseKey,
        patientId: line.patientId,
        patientName: line.patientName,
        open: 0,
        openedAt: line.openedAt,
        lines: [],
      };
      cases.set(line.caseKey, found);
    }
    found.lines.push(line);
    if (!line.closedAt) found.open++;
    if (line.openedAt < found.openedAt) found.openedAt = line.openedAt;
  }
  return [...cases.values()].sort((a, b) => {
    if (a.open > 0 !== b.open > 0) return a.open > 0 ? -1 : 1;
    return b.openedAt.localeCompare(a.openedAt);
  });
}

/**
 * QA D4102 (r81): the register of unresolved urgent alerts, for the hospital administrator and the médecin-chef.
 * The last step of the escalation ladder opened an incident and told the administrators to « check the medical
 * cover now », and no screen read it: eighteen identical bell notices, nothing to follow up, nothing saying an
 * incident had closed.
 *
 * The server decides what a reader may see: an administrator gets no patient (the administrator is off the
 * chart), the médecin-chef gets the patient and a link to the chart. Read-only.
 */
@Component({
  selector: 'app-urgent-incident-register',
  standalone: true,
  imports: [TranslateModule, DatePipe, RouterLink, PageHeaderComponent, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="incident-page" data-testid="urgent-incident-register">
      <app-page-header
        [title]="'URGENT_INCIDENTS.TITLE' | translate"
        [subtitle]="'URGENT_INCIDENTS.SUBTITLE' | translate"
        icon="emergency"
      >
        <button type="button" class="btn-secondary" [disabled]="loading()" (click)="load()">
          <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
          {{ 'URGENT_INCIDENTS.REFRESH' | translate }}
        </button>
      </app-page-header>

      @if (loading()) {
        <p class="incident-note" role="status">{{ 'common.loading' | translate }}</p>
      } @else if (error(); as message) {
        <p
          class="incident-note incident-note--error"
          role="alert"
          data-testid="urgent-incidents-failed"
        >
          {{ message }}
          <button type="button" class="btn-secondary" (click)="load()">
            {{ 'URGENT_INCIDENTS.RETRY' | translate }}
          </button>
        </p>
      } @else {
        <p class="incident-summary" data-testid="urgent-incidents-summary">
          {{
            (openCount() > 1 ? 'URGENT_INCIDENTS.OPEN_MANY' : 'URGENT_INCIDENTS.OPEN_ONE')
              | translate: { count: openCount() }
          }}
        </p>
        <div
          class="incident-filters"
          role="group"
          [attr.aria-label]="'URGENT_INCIDENTS.FILTER' | translate"
        >
          <button
            type="button"
            class="incident-filter"
            [class.active]="!showClosed()"
            [attr.aria-pressed]="!showClosed()"
            (click)="showClosed.set(false)"
          >
            {{ 'URGENT_INCIDENTS.FILTER_OPEN' | translate }}
          </button>
          <button
            type="button"
            class="incident-filter"
            [class.active]="showClosed()"
            [attr.aria-pressed]="showClosed()"
            (click)="showClosed.set(true)"
          >
            {{ 'URGENT_INCIDENTS.FILTER_ALL' | translate }}
          </button>
        </div>

        @if (visibleCases().length === 0) {
          <p class="incident-note" role="status" data-testid="urgent-incidents-empty">
            {{
              (showClosed() ? 'URGENT_INCIDENTS.EMPTY_ALL' : 'URGENT_INCIDENTS.EMPTY_OPEN')
                | translate
            }}
          </p>
        }

        @for (c of visibleCases(); track c.key; let index = $index) {
          <article
            class="incident-case"
            [class.is-open]="c.open > 0"
            data-testid="urgent-incident-case"
          >
            <header class="case-head">
              <h2 class="case-title">
                @if (c.patientName && c.patientId) {
                  <a [routerLink]="['/patients', c.patientId]">{{ c.patientName }}</a>
                } @else {
                  {{ 'URGENT_INCIDENTS.CASE' | translate: { number: index + 1 } }}
                }
              </h2>
              <span class="case-status" [class.is-open]="c.open > 0">
                {{
                  (c.open > 0 ? 'URGENT_INCIDENTS.STATUS_OPEN' : 'URGENT_INCIDENTS.STATUS_CLOSED')
                    | translate
                }}
              </span>
              <span class="case-count">
                {{
                  (c.lines.length > 1
                    ? 'URGENT_INCIDENTS.ALERTS_MANY'
                    : 'URGENT_INCIDENTS.ALERTS_ONE'
                  ) | translate: { count: c.lines.length }
                }}
              </span>
            </header>
            <ul class="case-lines">
              @for (line of visibleLines(c); track line.id) {
                <li class="incident-line" [class.is-open]="!line.closedAt">
                  <dl>
                    <div>
                      <dt>{{ 'URGENT_INCIDENTS.SOURCE' | translate }}</dt>
                      <dd>{{ line.sourceLabel }}</dd>
                    </div>
                    <div>
                      <dt>{{ 'URGENT_INCIDENTS.OPENED_AT' | translate }}</dt>
                      <dd>
                        {{ line.openedAt | serverTime | date: 'short' }}
                        ·
                        {{
                          'URGENT_INCIDENTS.UNACKNOWLEDGED_FOR'
                            | translate: { minutes: line.minutesUnacknowledged }
                        }}
                      </dd>
                    </div>
                    <div>
                      <dt>{{ 'URGENT_INCIDENTS.STEPS' | translate }}</dt>
                      <dd>
                        @if (steps(line).length === 0) {
                          {{ 'URGENT_INCIDENTS.STEPS_UNKNOWN' | translate }}
                        }
                        @for (step of steps(line); track step; let last = $last) {
                          {{ step | translate }}{{ last ? '' : ', ' }}
                        }
                        @if (reminderCount(line) > 0) {
                          ,
                          {{
                            (reminderCount(line) > 1
                              ? 'URGENT_INCIDENTS.REMINDERS_MANY'
                              : 'URGENT_INCIDENTS.REMINDERS_ONE'
                            ) | translate: { count: reminderCount(line) }
                          }}
                        }
                        @if (line.lastStepAt) {
                          ({{ line.lastStepAt | serverTime | date: 'shortTime' }})
                        }
                        ·
                        {{
                          (line.recipientsTold > 1
                            ? 'URGENT_INCIDENTS.TOLD_MANY'
                            : 'URGENT_INCIDENTS.TOLD_ONE'
                          ) | translate: { count: line.recipientsTold }
                        }}
                      </dd>
                    </div>
                    <div>
                      <dt>{{ 'URGENT_INCIDENTS.CLOSED' | translate }}</dt>
                      <dd>
                        @if (!line.closedAt) {
                          <strong class="still-open">{{
                            'URGENT_INCIDENTS.NOT_CLOSED' | translate
                          }}</strong>
                        } @else {
                          {{ line.closedAt | serverTime | date: 'short' }}
                          ·
                          @if (line.resolution === 'NO_LONGER_URGENT') {
                            {{ 'URGENT_INCIDENTS.RESOLVED_BY_RECORD' | translate }}
                          } @else if (line.acknowledgedByName) {
                            {{
                              'URGENT_INCIDENTS.ACKNOWLEDGED_BY'
                                | translate: { name: line.acknowledgedByName }
                            }}
                          } @else {
                            {{ 'URGENT_INCIDENTS.ACKNOWLEDGED' | translate }}
                          }
                        }
                      </dd>
                    </div>
                  </dl>
                </li>
              }
            </ul>
          </article>
        }
      }
    </section>
  `,
  styles: `
    .incident-page {
      max-width: 960px;
      margin: 0 auto;
      padding: 24px 16px;
    }
    .incident-note {
      color: var(--text-secondary, #475569);
    }
    .incident-note--error {
      color: #b91c1c;
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
    }
    .incident-summary {
      font-weight: 600;
      color: #1e293b;
    }
    .incident-filters {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 12px 0 16px;
    }
    .incident-filter {
      min-height: 44px;
      padding: 0 16px;
      border-radius: 999px;
      border: 1px solid #cbd5e1;
      background: #fff;
      color: #1e293b;
      cursor: pointer;
    }
    .incident-filter.active {
      background: #1e293b;
      border-color: #1e293b;
      color: #fff;
    }
    .incident-case {
      border: 1px solid #e2e8f0;
      border-radius: 8px;
      background: #fff;
      padding: 12px 16px;
      margin-bottom: 12px;
    }
    .incident-case.is-open {
      border-left: 4px solid #dc2626;
    }
    .case-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 12px;
    }
    .case-title {
      font-size: 1rem;
      font-weight: 600;
      margin: 0;
      color: #1e293b;
      overflow-wrap: anywhere;
    }
    .case-status {
      padding: 2px 10px;
      border-radius: 999px;
      font-size: 0.75rem;
      font-weight: 700;
      background: #e2e8f0;
      color: #334155;
    }
    .case-status.is-open {
      background: #b91c1c;
      color: #fff;
    }
    .case-count {
      font-size: 0.8rem;
      color: var(--text-secondary, #475569);
    }
    .case-lines {
      list-style: none;
      margin: 12px 0 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .incident-line {
      border-top: 1px solid #e2e8f0;
      padding-top: 8px;
    }
    .incident-line dl {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 8px 16px;
      margin: 0;
    }
    .incident-line dt {
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.03em;
      color: var(--text-secondary, #475569);
    }
    .incident-line dd {
      margin: 0;
      font-size: 0.875rem;
      color: #1e293b;
      overflow-wrap: anywhere;
    }
    .still-open {
      color: #b91c1c;
    }
  `,
})
export class UrgentIncidentRegisterComponent {
  private readonly alerts = inject(UrgentAlertsService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

  /** Only the latest read may write: a response issued under the previous hospital is dropped. */
  private request = 0;

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly lines = signal<UrgentAlertIncident[]>([]);
  readonly showClosed = signal(false);

  readonly cases = computed(() => groupCases(this.lines()));
  readonly openCount = computed(() => this.lines().filter((line) => !line.closedAt).length);
  readonly visibleCases = computed(() =>
    this.showClosed() ? this.cases() : this.cases().filter((c) => c.open > 0),
  );

  constructor() {
    // The register is of one hospital: it is read again, from empty, when the scope moves.
    effect(() => {
      this.roleContext.effectiveHospitalIdForRequest();
      untracked(() => this.load());
    });
  }

  load(): void {
    const request = ++this.request;
    this.loading.set(true);
    this.error.set(null);
    this.lines.set([]);
    this.alerts.incidents().subscribe({
      next: (lines) => {
        if (request !== this.request) return;
        this.lines.set(lines ?? []);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (request !== this.request) return;
        this.loading.set(false);
        this.error.set(
          refusalReason(err) ?? this.translate.instant('URGENT_INCIDENTS.LOAD_FAILED'),
        );
      },
    });
  }

  visibleLines(c: IncidentCase): UrgentAlertIncident[] {
    return this.showClosed() ? c.lines : c.lines.filter((line) => !line.closedAt);
  }

  steps(line: UrgentAlertIncident): string[] {
    return stepKeys(line.stepsTaken);
  }

  reminderCount(line: UrgentAlertIncident): number {
    return reminders(line.stepsTaken);
  }
}
