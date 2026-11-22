import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import {
  HospitalOnboardingReadiness,
  HospitalService,
  OnboardingStep,
} from '../../services/hospital.service';

/**
 * Where each step of the checklist is done. One table, so the checklist can
 * never name a step it has no page for (`onboarding-checklist.component.spec.ts`
 * resolves every route against the real route table, with the roles that see it).
 */
export const ONBOARDING_STEP_ROUTES: Readonly<Record<OnboardingStep, string>> = {
  // Review of #1246: the hospital's own administrator is registered first («
  // Inscrire », role administrateur d'hôpital); a department is filed under them.
  HOSPITAL_ADMIN: '/users',
  DEPARTMENTS: '/departments',
  STAFF: '/users',
  PROVIDERS_IN_DEPARTMENT: '/staff',
  SESSION_TEMPLATES: '/slot-admin',
  OPEN_SLOTS: '/slot-admin',
  BEDS: '/bed-management',
  PRICE_LIST: '/billing/tarifs',
  PAYERS: '/billing/organismes',
  // V356: « Règles cliniques et validation », where the admin records each sign-off.
  CLINICAL_RULES: '/clinical-rules',
};

interface ChecklistRow {
  step: OnboardingStep;
  done: boolean;
  count: number;
  requiredForBooking: boolean;
  /** V356: the count the step is done at (CLINICAL_RULES), else null. */
  total: number | null;
  route: string;
}

/**
 * QA r65 D2731 (and the platform report's E3): what a hospital still needs
 * before it can work, and above all before a patient can book online.
 *
 * <p>A hospital set up by the book gave its patients a booking form that
 * could never succeed (no clinician attached to a department, no slot
 * published), and nothing warned the admin. This lists the steps, says which
 * ones online booking cannot work without, and links each to the page where
 * it is done. The hospital admin sees it on the dashboard until every step
 * is done; the super-admin sees it on the hospital's page.
 */
@Component({
  selector: 'app-onboarding-checklist',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (visible()) {
      <section class="oc-card" data-testid="onboarding-checklist" aria-labelledby="oc-title">
        <header class="oc-head">
          <div>
            <h2 id="oc-title" class="oc-title">
              {{ 'PLATFORM_SETUP.CHECKLIST.TITLE' | translate }}
            </h2>
            <p class="oc-sub">
              {{
                'PLATFORM_SETUP.CHECKLIST.PROGRESS'
                  | translate: { done: doneCount(), total: rows().length }
              }}
            </p>
          </div>
          <p
            class="oc-booking"
            [class.oc-booking-ok]="readiness()?.patientBookable"
            role="status"
            data-testid="onboarding-booking-state"
          >
            <span class="material-symbols-outlined" aria-hidden="true">{{
              readiness()?.patientBookable ? 'event_available' : 'event_busy'
            }}</span>
            {{
              (readiness()?.patientBookable
                ? 'PLATFORM_SETUP.CHECKLIST.BOOKABLE'
                : 'PLATFORM_SETUP.CHECKLIST.NOT_BOOKABLE'
              ) | translate
            }}
          </p>
        </header>
        <ol class="oc-list">
          @for (row of rows(); track row.step) {
            <li class="oc-row" [class.oc-row-done]="row.done" [attr.data-step]="row.step">
              <span class="material-symbols-outlined oc-mark" aria-hidden="true">{{
                row.done ? 'check_circle' : 'radio_button_unchecked'
              }}</span>
              <div class="oc-body">
                <span class="oc-step">
                  {{ 'PLATFORM_SETUP.CHECKLIST.STEP.' + row.step + '.TITLE' | translate }}
                  @if (row.requiredForBooking) {
                    <span class="oc-tag">{{
                      'PLATFORM_SETUP.CHECKLIST.NEEDED_FOR_BOOKING' | translate
                    }}</span>
                  }
                </span>
                <span class="oc-state">
                  @if (row.done) {
                    {{
                      'PLATFORM_SETUP.CHECKLIST.STEP.' + row.step + '.DONE'
                        | translate: { count: row.count, total: row.total }
                    }}
                  } @else {
                    {{
                      'PLATFORM_SETUP.CHECKLIST.STEP.' + row.step + '.TODO'
                        | translate: { count: row.count, total: row.total }
                    }}
                  }
                </span>
              </div>
              <a
                class="oc-link"
                [routerLink]="row.route"
                [queryParams]="linkParams()"
                [attr.aria-label]="
                  ('PLATFORM_SETUP.CHECKLIST.OPEN' | translate) +
                  ('COMMON.COLON' | translate) +
                  ' ' +
                  ('PLATFORM_SETUP.CHECKLIST.STEP.' + row.step + '.TITLE' | translate)
                "
              >
                {{ 'PLATFORM_SETUP.CHECKLIST.OPEN' | translate }}
              </a>
            </li>
          }
        </ol>
      </section>
    } @else if (failed() && !hideWhenComplete()) {
      <p class="oc-failed" role="alert" data-testid="onboarding-checklist-failed">
        {{ 'PLATFORM_SETUP.CHECKLIST.LOAD_FAILED' | translate }}
      </p>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .oc-card {
        background: #fff;
        border: 1px solid var(--border);
        border-radius: 12px;
        padding: 16px;
        margin-bottom: 16px;
        /* A long word (a module name, a code) wraps rather than widening the card. */
        overflow-wrap: anywhere;
      }
      .oc-head > div,
      .oc-row {
        min-width: 0;
      }
      .oc-head {
        display: flex;
        flex-wrap: wrap;
        gap: 8px 16px;
        align-items: flex-start;
        justify-content: space-between;
        margin-bottom: 12px;
      }
      .oc-title {
        margin: 0;
        font-size: 1.0625rem;
        color: var(--text-primary);
      }
      .oc-sub {
        margin: 2px 0 0;
        font-size: 0.875rem;
        color: var(--text-secondary);
      }
      .oc-booking {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        min-width: 0;
        max-width: 100%;
        margin: 0;
        padding: 6px 10px;
        border-radius: 8px;
        font-size: 0.875rem;
        font-weight: 600;
        background: #fef3c7;
        color: #7c2d12;
      }
      .oc-booking-ok {
        background: var(--primary-soft);
        color: var(--primary-deep);
      }
      .oc-booking .material-symbols-outlined {
        font-size: 1.125rem;
      }
      .oc-list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 8px;
      }
      .oc-row {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 6px 12px;
        padding: 10px 12px;
        border: 1px solid var(--border);
        border-radius: 8px;
      }
      .oc-row-done {
        background: #f8fafc;
      }
      .oc-mark {
        color: #b45309;
        flex: none;
      }
      .oc-row-done .oc-mark {
        color: var(--primary);
      }
      .oc-body {
        flex: 1 1 220px;
        min-width: 0;
        display: grid;
        gap: 2px;
      }
      .oc-step {
        font-weight: 600;
        color: var(--text-primary);
        overflow-wrap: anywhere;
      }
      .oc-tag {
        display: inline-block;
        margin-left: 6px;
        padding: 1px 8px;
        border-radius: 999px;
        font-size: 0.75rem;
        font-weight: 600;
        background: var(--primary-tint);
        color: var(--primary-deep);
      }
      .oc-state {
        font-size: 0.875rem;
        color: #475569;
        overflow-wrap: anywhere;
      }
      .oc-link {
        flex: 0 1 auto;
        min-width: 0;
        font-size: 0.875rem;
        font-weight: 600;
        color: var(--primary-dark);
        text-decoration: underline;
      }
      .oc-failed {
        margin: 0 0 16px;
        padding: 10px 12px;
        border-radius: 8px;
        background: #fef2f2;
        color: #991b1b;
        font-size: 0.875rem;
      }
    `,
  ],
})
export class OnboardingChecklistComponent {
  private readonly hospitals = inject(HospitalService);

  /** The hospital the checklist is about; nothing is asked (or shown) without one. */
  readonly hospitalId = input<string | null | undefined>(null);
  /**
   * True on a super-admin's page: each link carries `?hospitalId=`, which the
   * shell's scope gate honours, so the step opens on THIS hospital and not on
   * the all-hospitals directory.
   */
  readonly scopedLinks = input(false);
  /** True on the dashboard: a hospital with every step done shows no checklist at all. */
  readonly hideWhenComplete = input(false);

  protected readonly readiness = signal<HospitalOnboardingReadiness | null>(null);
  protected readonly failed = signal(false);

  protected readonly rows = computed<ChecklistRow[]>(() =>
    (Array.isArray(this.readiness()?.items) ? this.readiness()!.items : [])
      // A step this build has no page for is not shown rather than linked nowhere.
      .filter((item) => !!ONBOARDING_STEP_ROUTES[item.step])
      .map((item) => ({
        ...item,
        total: item.total ?? null,
        route: ONBOARDING_STEP_ROUTES[item.step],
      })),
  );
  protected readonly doneCount = computed(() => this.rows().filter((row) => row.done).length);
  protected readonly visible = computed(() => {
    const rows = this.rows();
    if (rows.length === 0) return false;
    return !(this.hideWhenComplete() && rows.every((row) => row.done));
  });
  protected readonly linkParams = computed(() =>
    this.scopedLinks() && this.hospitalId() ? { hospitalId: this.hospitalId() } : null,
  );

  constructor() {
    effect(() => {
      const id = this.hospitalId();
      untracked(() => this.load(id ?? null));
    });
  }

  private load(hospitalId: string | null): void {
    this.readiness.set(null);
    this.failed.set(false);
    if (!hospitalId) return;
    this.hospitals.onboardingReadiness(hospitalId).subscribe({
      next: (readiness) => {
        // A late answer for a hospital the page has since left is dropped.
        if (this.hospitalId() === hospitalId) this.readiness.set(readiness);
      },
      error: (err: { status?: number } | null) => {
        // 404: a facility that has no checklist (a pharmacy, a laboratory): nothing to show.
        if (this.hospitalId() === hospitalId) this.failed.set(err?.status !== 404);
      },
    });
  }
}
