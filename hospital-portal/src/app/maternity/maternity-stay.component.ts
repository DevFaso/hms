import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnChanges,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { actionRoles } from '../core/action-permissions';
import { AcuityLevel, AdmissionResponse, AdmissionService } from '../services/admission.service';
import { BedResponse, BedService } from '../services/bed.service';
import { nowLocalDatetime, toServerDateTime } from '../shared/date-utils';
import { refusalReason, refusedFieldReasons } from '../shared/refusal-reason';
import { refuseFields } from '../shared/forms/invalid-fields';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import {
  MIDWIFE_ONLY_ROLES,
  WARD_WIDE_BED_ROLES,
  bedsAMidwifeAssigns,
  isNeonatalCot,
} from '../shared/midwife-beds';

/** The admission a maternity record opens: the mother's labour, or her newborn. */
export type MaternityStayKind = 'LABOR_DELIVERY' | 'NEWBORN';

/**
 * QA D2594 (r65): a labour admission and a delivery left the mother and the newborn with no
 * hospital stay and no bed: Maternité « 0 sur 12 occupés », neither on the nurse station nor the
 * bed board, so the ward nurse never saw their alerts or due vitals, and the chart said « Aucune
 * visite active ». This card says so on the labour screen and admits the patient there (the stay
 * opens its INPATIENT visit, D98), then offers the bed to the roles that assign one.
 *
 * The acuity is chosen by the clinician: nothing here picks a clinical level for her. Who may do
 * what is the endpoints' own rule (core/action-permissions): a midwife admits a woman in labour
 * (policy 2).
 *
 * Policy rule 2 (owner 2026-11-20, PROVISIONAL until a named sage-femme or obstetrician signs it
 * off): she also admits the newborn of a delivery she attended, as its responsible clinician, and
 * assigns the beds and cots of maternity wards. A neonatal (NICU) cot for a newborn is assigned
 * too and a physician is asked to review: a task, never a refusal. The server decides whose
 * newborn it is and which ward (`MidwifeNewbornCare`); its refusal is shown as it is worded.
 */
@Component({
  selector: 'app-maternity-stay',
  standalone: true,
  imports: [FormsModule, TranslateModule, RouterLink, EnumLabelPipe, SubmitGuardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!hidden()) {
      <div class="maternity-stay" [attr.data-testid]="'maternity-stay-' + kind()">
        @if (loading()) {
          <p class="muted">{{ 'MATERNITY_STAY.LOADING' | translate }}</p>
        } @else if (failed()) {
          <!-- Review of #1237: an unknown stay is never shown as « non admis(e) ». -->
          <p class="maternity-stay__warn" role="alert" data-testid="maternity-stay-failed">
            <strong>{{ patientName() }}</strong> — {{ 'MATERNITY_STAY.LOAD_FAILED' | translate }}
          </p>
        } @else if (!stay()) {
          <p class="maternity-stay__warn" data-testid="maternity-stay-none">
            <strong>{{ patientName() }}</strong> — {{ 'MATERNITY_STAY.NOT_ADMITTED' | translate }}
          </p>
          @if (canAdmit()) {
            <form class="maternity-stay__form" (ngSubmit)="admit()">
              <label [for]="'stay-acuity-' + patientId()"
                >{{ 'MATERNITY_STAY.ACUITY' | translate }} *</label
              >
              <select
                [id]="'stay-acuity-' + patientId()"
                name="acuity"
                required
                data-testid="maternity-stay-acuity"
                [(ngModel)]="acuity"
              >
                <option value="">{{ 'MATERNITY_STAY.ACUITY_PICK' | translate }}</option>
                @for (level of acuityLevels; track level) {
                  <option [value]="level">{{ level | enumLabel: 'acuityLevel' }}</option>
                }
              </select>
              <button
                type="submit"
                class="btn-primary"
                data-testid="maternity-stay-admit"
                [disabled]="saving()"
                appSubmitGuard
                submitGuardRequired
              >
                {{
                  (kind() === 'NEWBORN'
                    ? 'MATERNITY_STAY.ADMIT_NEWBORN'
                    : 'MATERNITY_STAY.ADMIT_MOTHER'
                  ) | translate
                }}
              </button>
            </form>
          } @else {
            <p class="muted" data-testid="maternity-stay-who-admits">
              {{
                (kind() === 'NEWBORN'
                  ? 'MATERNITY_STAY.NEWBORN_BY_DOCTOR'
                  : 'MATERNITY_STAY.BY_CLINICIAN'
                ) | translate
              }}
            </p>
          }
          @if (midwifeOnly() && kind() === 'NEWBORN') {
            <p class="muted maternity-stay__rule" data-testid="maternity-stay-midwife-newborn-rule">
              {{ 'MATERNITY_STAY.MIDWIFE_NEWBORN_RULE' | translate }}
            </p>
          }
        } @else if (stay(); as s) {
          @if (s.roomBed) {
            <p class="maternity-stay__ok" data-testid="maternity-stay-bed">
              <strong>{{ patientName() }}</strong> —
              {{ 'MATERNITY_STAY.IN_BED' | translate: { bed: s.roomBed } }}
            </p>
          } @else {
            <p class="maternity-stay__warn" data-testid="maternity-stay-no-bed">
              <strong>{{ patientName() }}</strong> — {{ 'MATERNITY_STAY.NO_BED' | translate }}
            </p>
            @if (canAssignBed()) {
              <form class="maternity-stay__form" (ngSubmit)="assignBed(s)">
                <label [for]="'stay-bed-' + patientId()"
                  >{{ 'MATERNITY_STAY.BED' | translate }} *</label
                >
                <select
                  [id]="'stay-bed-' + patientId()"
                  name="bed"
                  required
                  data-testid="maternity-stay-bed-select"
                  [(ngModel)]="bedId"
                >
                  <option value="">{{ 'MATERNITY_STAY.BED_PICK' | translate }}</option>
                  @for (bed of beds(); track bed.id) {
                    <option [value]="bed.id">{{ bed.label }}</option>
                  }
                </select>
                <button
                  type="submit"
                  class="btn-primary"
                  data-testid="maternity-stay-assign"
                  [disabled]="saving()"
                  appSubmitGuard
                  submitGuardRequired
                >
                  {{ 'MATERNITY_STAY.ASSIGN_BED' | translate }}
                </button>
              </form>
              @if (!bedsLoading() && beds().length === 0) {
                <p class="muted">{{ 'MATERNITY_STAY.NO_FREE_BED' | translate }}</p>
              }
              @if (midwifeOnly()) {
                <p class="muted maternity-stay__rule" data-testid="maternity-stay-midwife-bed-rule">
                  {{
                    (kind() === 'NEWBORN'
                      ? 'MATERNITY_STAY.MIDWIFE_COT_RULE'
                      : 'MATERNITY_STAY.MIDWIFE_BED_RULE'
                    ) | translate
                  }}
                </p>
              }
            } @else {
              <p class="muted" data-testid="maternity-stay-who-assigns">
                {{ 'MATERNITY_STAY.BED_BY_NURSE' | translate }}
                <a routerLink="/admissions">{{ 'MATERNITY_STAY.OPEN_ADMISSIONS' | translate }}</a>
              </p>
            }
          }
        }
      </div>
    }
  `,
  styles: `
    .maternity-stay {
      margin: 8px 0;
      padding: 10px 12px;
      border: 1px solid #e2e8f0;
      border-radius: 6px;
      min-width: 0;
    }
    .maternity-stay__warn {
      margin: 0 0 6px;
      color: #92400e;
      overflow-wrap: anywhere;
    }
    .maternity-stay__ok {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .maternity-stay__form {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      min-width: 0;
    }
    .maternity-stay__form select {
      min-width: 0;
      max-width: 100%;
    }
    .maternity-stay__rule {
      margin: 6px 0 0;
      overflow-wrap: anywhere;
    }
  `,
})
export class MaternityStayComponent implements OnChanges {
  readonly patientId = input.required<string>();
  readonly patientName = input<string | null | undefined>('');
  readonly hospitalId = input<string | null | undefined>(null);
  readonly kind = input<MaternityStayKind>('LABOR_DELIVERY');
  /** When the stay began (the labour admission, server local time); now when not given. */
  readonly admittedAt = input<string | null | undefined>(null);
  /**
   * Review of #1237: for a past delivery, the card shows only when the patient has had no stay since this
   * time (the birth); a stay since then, open or ended, hides it. Never a backdated re-admission.
   */
  readonly onlyIfNoStaySince = input<string | null | undefined>(null);
  readonly hidden = signal(false);
  readonly failed = signal(false);

  private readonly admissions = inject(AdmissionService);
  private readonly bedService = inject(BedService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly acuityLevels: AcuityLevel[] = [
    'LEVEL_1_MINIMAL',
    'LEVEL_2_MODERATE',
    'LEVEL_3_MAJOR',
    'LEVEL_4_SEVERE',
    'LEVEL_5_CRITICAL',
  ];

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly stay = signal<AdmissionResponse | null>(null);
  readonly beds = signal<BedResponse[]>([]);
  readonly bedsLoading = signal(false);
  acuity: AcuityLevel | '' = '';
  bedId = '';

  /**
   * The endpoint's roles: a physician, or a midwife for a woman in labour (policy 2) and for the
   * newborn of a delivery she attended (policy rule 2; the server refuses any other newborn).
   */
  readonly canAdmit = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('admissionCreate')),
  );
  readonly canAssignBed = computed(() =>
    this.roleContext.hasAnyActiveRole(actionRoles('admissionAssignBed')),
  );
  /** A midwife who is neither a nurse nor a physician: her bed picker is the maternity wards'. */
  readonly midwifeOnly = computed(
    () =>
      this.roleContext.hasAnyActiveRole([...MIDWIFE_ONLY_ROLES]) &&
      !this.roleContext.hasAnyActiveRole([...WARD_WIDE_BED_ROLES]),
  );

  ngOnChanges(): void {
    this.load();
  }

  private load(): void {
    const patientId = this.patientId();
    if (!patientId) return;
    this.loading.set(true);
    this.failed.set(false);
    this.hidden.set(false);
    this.admissions
      .getCurrentByPatient(patientId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (stay) => {
          this.stay.set(stay ?? null);
          if (!stay && this.onlyIfNoStaySince()) {
            this.checkStaySince(patientId, this.onlyIfNoStaySince()!);
            return;
          }
          this.loading.set(false);
          if (stay && !stay.roomBed && this.canAssignBed()) this.loadBeds();
        },
        error: () => {
          this.stay.set(null);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }

  /** A stay admitted at or after `since` (the birth) hides the card of a past delivery. */
  private checkStaySince(patientId: string, since: string): void {
    this.admissions
      .getByPatient(patientId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (stays) => {
          // Server local times of one format compare as strings.
          const sinceKey = since.slice(0, 16);
          this.hidden.set(
            (stays ?? []).some(
              (s) => !!s.admissionDateTime && s.admissionDateTime.slice(0, 16) >= sinceKey,
            ),
          );
          this.loading.set(false);
        },
        error: () => {
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }

  private loadBeds(): void {
    this.bedsLoading.set(true);
    this.bedService
      .getAvailableBeds(this.patientId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (beds) => {
          const free = beds ?? [];
          this.beds.set(
            this.midwifeOnly() ? bedsAMidwifeAssigns(free, this.kind() === 'NEWBORN') : free,
          );
          this.bedsLoading.set(false);
        },
        error: () => {
          this.beds.set([]);
          this.bedsLoading.set(false);
        },
      });
  }

  admit(): void {
    const hospitalId = this.hospitalId() ?? this.roleContext.effectiveHospitalIdForRequest();
    const staffId = this.auth.getUserProfile()?.staffId;
    if (!this.acuity || !hospitalId || !staffId || this.saving()) return;
    const newborn = this.kind() === 'NEWBORN';
    this.saving.set(true);
    this.admissions
      .create({
        patientId: this.patientId(),
        hospitalId,
        admittingProviderId: staffId,
        admissionType: this.kind(),
        acuityLevel: this.acuity,
        admissionDateTime: this.admittedAt() || toServerDateTime(nowLocalDatetime()),
        // Stored in the clinician's language, as every typed reason is.
        chiefComplaint: this.translate.instant(
          newborn ? 'MATERNITY_STAY.REASON_NEWBORN' : 'MATERNITY_STAY.REASON_LABOUR',
        ),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (stay) => {
          this.saving.set(false);
          this.toast.success(
            this.translate.instant('MATERNITY_STAY.ADMITTED', {
              patient: this.patientName() ?? '',
            }),
          );
          this.stay.set(stay);
          if (!stay.roomBed && this.canAssignBed()) this.loadBeds();
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.markRefused(err, { acuityLevel: `stay-acuity-${this.patientId()}` });
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('MATERNITY_STAY.ADMIT_FAILED'),
          );
        },
      });
  }

  assignBed(stay: AdmissionResponse): void {
    if (!this.bedId || this.saving()) return;
    // Policy rule 2: a neonatal cot assigned by a midwife asks a physician to review.
    const physicianAsked =
      this.midwifeOnly() && isNeonatalCot(this.beds().find((bed) => bed.id === this.bedId));
    this.saving.set(true);
    this.admissions
      .assignBed(stay.id, this.bedId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.saving.set(false);
          this.stay.set(updated);
          this.toast.success(
            this.translate.instant(
              physicianAsked
                ? 'MATERNITY_STAY.BED_ASSIGNED_PHYSICIAN_ASKED'
                : 'MATERNITY_STAY.BED_ASSIGNED',
              { bed: updated.roomBed ?? '' },
            ),
          );
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.markRefused(err, { bedId: `stay-bed-${this.patientId()}` });
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('MATERNITY_STAY.BED_FAILED'),
          );
        },
      });
  }

  /** A 400's `fieldErrors`, each at the field it names, with the server's own reason. */
  private markRefused(err: unknown, idByField: Readonly<Record<string, string>>): void {
    const refused = refusedFieldReasons(err, idByField);
    if (!refused.length) return;
    refuseFields(
      this.host.nativeElement,
      refused.map(([id, message]) => ({ id, message })),
      Object.values(idByField),
    );
  }
}
