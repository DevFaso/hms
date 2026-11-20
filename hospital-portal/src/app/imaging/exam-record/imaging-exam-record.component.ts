import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnChanges,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { actionRoles } from '../../core/action-permissions';
import {
  ImagingExamRecordRequest,
  ImagingOrderResponse,
  ImagingOrderStatus,
  ImagingService,
} from '../../services/imaging.service';
import { IMAGING_EXAM_WIDER_ROLES, IMAGING_TECHNICIAN_ROLES } from '../../shared/imaging-access';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { ReasonDialogComponent } from '../../shared/reason-dialog/reason-dialog.component';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

/** An order a patient can still be checked in for: placed and not yet done (ImagingExamRecord). */
const CHECK_IN_OPEN: readonly ImagingOrderStatus[] = [
  'ORDERED',
  'PENDING_AUTHORIZATION',
  'SCHEDULED',
  'IN_PROGRESS',
];
/** A study whose record can still be written: started, and not yet read. */
const RECORD_OPEN: readonly ImagingOrderStatus[] = ['IN_PROGRESS', 'COMPLETED'];

/**
 * Policy rule 3 (owner 2026-11-20, PROVISIONAL until the clinic's radiologist or medical director
 * signs it off): the exam record of an imaging order, in the request drawer.
 *
 * Whoever runs the exam (the imaging technician, a radiologist or a doctor) checks the patient
 * in and records how it went: a technical note, the contrast given and the radiation dose. Who
 * performed the exam is whoever started it, stamped by the server from the session; it fills the
 * report's « réalisé par », which no screen ever filled. A technician also discontinues a started
 * exam, with a reason. Every reader of the order sees the record; nothing here writes a report.
 */
@Component({
  selector: 'app-imaging-exam-record',
  standalone: true,
  imports: [
    FormsModule,
    TranslateModule,
    SubmitGuardDirective,
    ReasonDialogComponent,
    DateTimePipe,
    ServerTimePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (visible()) {
      <section class="exam-record" data-testid="imaging-exam-record">
        <h3 class="exam-record__title">{{ 'IMAGING_EXAM.TITLE' | translate }}</h3>
        @if (technician) {
          <p class="field-hint exam-record__rule" data-testid="imaging-exam-technician-rule">
            {{ 'IMAGING_EXAM.TECHNICIAN_RULE' | translate }}
          </p>
        }
        @if (physiciansOnly()) {
          <p class="field-hint exam-record__rule" data-testid="imaging-exam-ultrasound-physicians">
            {{ 'IMAGING_EXAM.ULTRASOUND_PHYSICIANS_ONLY' | translate }}
          </p>
        }
        <dl class="exam-record__facts">
          @if (order().checkedInAt; as at) {
            <div data-testid="imaging-exam-checked-in">
              <dt>{{ 'IMAGING_EXAM.CHECKED_IN' | translate }}</dt>
              <dd>
                {{ at | serverTime | dateTime }}
                @if (order().checkedInByName) {
                  · {{ order().checkedInByName }}
                }
              </dd>
            </div>
          }
          @if (order().examStartedAt; as at) {
            <div data-testid="imaging-exam-started">
              <dt>{{ 'IMAGING_EXAM.STARTED' | translate }}</dt>
              <dd>{{ at | serverTime | dateTime }}</dd>
            </div>
          }
          @if (order().performedByName) {
            <div data-testid="imaging-exam-performed-by">
              <dt>{{ 'IMAGING_EXAM.PERFORMED_BY' | translate }}</dt>
              <dd>{{ order().performedByName }}</dd>
            </div>
          }
          @if (!canRecord()) {
            @if (order().examTechnicalNote) {
              <div data-testid="imaging-exam-note-read">
                <dt>{{ 'IMAGING_EXAM.TECHNICAL_NOTE' | translate }}</dt>
                <dd class="exam-record__text">{{ order().examTechnicalNote }}</dd>
              </div>
            }
            @if (hasContrastRecord()) {
              <div data-testid="imaging-exam-contrast-read">
                <dt>{{ 'IMAGING_EXAM.CONTRAST_GIVEN' | translate }}</dt>
                <dd class="exam-record__text">
                  {{ (order().examContrastAdministered ? 'COMMON.YES' : 'COMMON.NO') | translate }}
                  @if (order().examContrastDetails) {
                    · {{ order().examContrastDetails }}
                  }
                </dd>
              </div>
            }
            @if (hasDose()) {
              <div data-testid="imaging-exam-dose-read">
                <dt>{{ 'IMAGING_EXAM.DOSE' | translate }}</dt>
                <dd>{{ order().examRadiationDoseMgy }} mGy</dd>
              </div>
            }
          }
        </dl>

        @if (canCheckIn() || canDiscontinue()) {
          <div class="exam-record__actions">
            @if (canCheckIn()) {
              <button
                type="button"
                class="btn-secondary"
                data-testid="imaging-exam-check-in"
                [disabled]="saving()"
                (click)="checkIn()"
              >
                <span class="material-symbols-outlined" aria-hidden="true">how_to_reg</span>
                {{ 'IMAGING_EXAM.CHECK_IN' | translate }}
              </button>
            }
            @if (canDiscontinue()) {
              <button
                type="button"
                class="btn-secondary"
                data-testid="imaging-exam-discontinue"
                [disabled]="saving()"
                (click)="discontinuing.set(true)"
              >
                <span class="material-symbols-outlined" aria-hidden="true">block</span>
                {{ 'IMAGING_EXAM.DISCONTINUE' | translate }}
              </button>
            }
          </div>
        }

        @if (canRecord()) {
          <form class="exam-record__form" (ngSubmit)="save()">
            <div class="exam-record__field">
              <label [for]="noteId">{{ 'IMAGING_EXAM.TECHNICAL_NOTE' | translate }}</label>
              <textarea
                [id]="noteId"
                name="technicalNote"
                rows="3"
                maxlength="2000"
                data-testid="imaging-exam-note"
                [(ngModel)]="technicalNote"
              ></textarea>
              <span class="field-hint">{{ 'IMAGING_EXAM.TECHNICAL_NOTE_HINT' | translate }}</span>
            </div>
            <div class="exam-record__field exam-record__field--inline">
              <input
                type="checkbox"
                [id]="contrastId"
                name="contrastAdministered"
                data-testid="imaging-exam-contrast"
                [(ngModel)]="contrastAdministered"
              />
              <label [for]="contrastId">{{ 'IMAGING_EXAM.CONTRAST_GIVEN' | translate }}</label>
            </div>
            @if (contrastAdministered) {
              <div class="exam-record__field">
                <label [for]="contrastDetailsId">{{
                  'IMAGING_EXAM.CONTRAST_DETAILS' | translate
                }}</label>
                <input
                  type="text"
                  [id]="contrastDetailsId"
                  name="contrastDetails"
                  maxlength="1000"
                  data-testid="imaging-exam-contrast-details"
                  [(ngModel)]="contrastDetails"
                />
              </div>
            }
            <div class="exam-record__field">
              <label [for]="doseId">{{ 'IMAGING_EXAM.DOSE' | translate }} (mGy)</label>
              <input
                type="number"
                [id]="doseId"
                name="radiationDoseMgy"
                min="0"
                step="0.001"
                inputmode="decimal"
                data-testid="imaging-exam-dose"
                [(ngModel)]="radiationDoseMgy"
              />
            </div>
            <button
              type="submit"
              class="btn-primary"
              data-testid="imaging-exam-save"
              [disabled]="saving()"
              [appSubmitGuard]="
                nothingEntered() ? ('IMAGING_EXAM.NOTHING_TO_SAVE' | translate) : false
              "
              [submitGuardFields]="[noteId]"
            >
              {{ 'IMAGING_EXAM.SAVE' | translate }}
            </button>
          </form>
        }
      </section>
      @if (discontinuing()) {
        <app-reason-dialog
          titleKey="IMAGING_EXAM.DISCONTINUE_TITLE"
          messageKey="IMAGING_EXAM.DISCONTINUE_MESSAGE"
          confirmKey="IMAGING_EXAM.DISCONTINUE"
          dismissKey="COMMON.BACK"
          [busy]="saving()"
          (confirmed)="discontinue($event)"
          (cancelled)="discontinuing.set(false)"
        />
      }
    }
  `,
  styles: `
    .exam-record {
      margin: 12px 0;
      padding: 12px;
      border: 1px solid #e2e8f0;
      border-radius: 6px;
      min-width: 0;
    }
    .exam-record__title {
      margin: 0 0 8px;
      font-size: 1rem;
    }
    .exam-record__rule {
      margin: 0 0 8px;
      overflow-wrap: anywhere;
    }
    .exam-record__facts {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 8px 16px;
      margin: 0 0 8px;
      min-width: 0;
    }
    .exam-record__facts dt {
      font-size: 0.8rem;
      color: #64748b;
    }
    .exam-record__facts dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .exam-record__text {
      white-space: pre-wrap;
    }
    .exam-record__actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 8px;
    }
    .exam-record__form {
      display: grid;
      gap: 10px;
      min-width: 0;
    }
    .exam-record__field {
      display: grid;
      gap: 4px;
      min-width: 0;
    }
    .exam-record__field--inline {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .exam-record__field textarea,
    .exam-record__field input[type='text'],
    .exam-record__field input[type='number'] {
      width: 100%;
      max-width: 100%;
      min-width: 0;
      box-sizing: border-box;
    }
    .exam-record__form button[type='submit'] {
      justify-self: start;
    }
  `,
})
export class ImagingExamRecordComponent implements OnChanges {
  readonly order = input.required<ImagingOrderResponse>();
  /** The order as the server answered after a check-in, a save or a discontinuation. */
  readonly changed = output<ImagingOrderResponse>();

  private readonly imaging = inject(ImagingService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private static nextId = 0;
  private readonly uid = ++ImagingExamRecordComponent.nextId;
  readonly noteId = `imaging-exam-note-${this.uid}`;
  readonly contrastId = `imaging-exam-contrast-${this.uid}`;
  readonly contrastDetailsId = `imaging-exam-contrast-details-${this.uid}`;
  readonly doseId = `imaging-exam-dose-${this.uid}`;

  readonly saving = signal(false);
  readonly discontinuing = signal(false);

  technicalNote = '';
  contrastAdministered = false;
  contrastDetails = '';
  radiationDoseMgy: number | null = null;

  private readonly runsExams = this.roleContext.hasAnyActiveRole(actionRoles('imagingExamRecord'));
  private readonly checksIn = this.roleContext.hasAnyActiveRole(actionRoles('imagingExamCheckIn'));
  /** Acts on the exam as a technician: the role, and neither a doctor's nor a radiologist's reach. */
  readonly technician =
    this.roleContext.hasAnyActiveRole([...IMAGING_TECHNICIAN_ROLES]) &&
    !this.roleContext.hasAnyActiveRole([...IMAGING_EXAM_WIDER_ROLES]);

  readonly canCheckIn = computed(
    () => this.checksIn && !this.order().checkedInAt && CHECK_IN_OPEN.includes(this.order().status),
  );
  /**
   * Owner decision 2026-11-20 (provisional): a technician performs an ultrasound only where the
   * hospital's setting allows it; the order says so and the server refuses otherwise.
   */
  readonly physiciansOnly = computed(
    () => this.technician && this.order().technicianMayPerform === false,
  );
  readonly canRecord = computed(
    () => this.runsExams && !this.physiciansOnly() && RECORD_OPEN.includes(this.order().status),
  );
  /** The server lets a technician call off an exam only once it is in progress. */
  readonly canDiscontinue = computed(
    () => this.technician && !this.physiciansOnly() && this.order().status === 'IN_PROGRESS',
  );
  readonly hasContrastRecord = computed(
    () =>
      this.order().examContrastAdministered !== null &&
      this.order().examContrastAdministered !== undefined,
  );
  readonly hasDose = computed(
    () =>
      this.order().examRadiationDoseMgy !== null && this.order().examRadiationDoseMgy !== undefined,
  );
  /** Nothing to show and nothing to do: the section is left out. */
  readonly visible = computed(() => this.canAct() || this.hasRecord() || this.physiciansOnly());
  private readonly canAct = computed(
    () => this.canCheckIn() || this.canRecord() || this.canDiscontinue(),
  );
  private readonly hasRecord = computed(() => {
    const order = this.order();
    return [
      order.checkedInAt,
      order.examStartedAt,
      order.performedByName,
      order.examTechnicalNote,
      this.hasContrastRecord(),
      this.hasDose(),
    ].some(Boolean);
  });

  ngOnChanges(): void {
    const order = this.order();
    this.technicalNote = order.examTechnicalNote ?? '';
    this.contrastAdministered = order.examContrastAdministered === true;
    this.contrastDetails = order.examContrastDetails ?? '';
    this.radiationDoseMgy = order.examRadiationDoseMgy ?? null;
  }

  /** An exam record with nothing in it is not saved: the note field says so. */
  nothingEntered(): boolean {
    return (
      !this.technicalNote.trim() &&
      !this.contrastAdministered &&
      (this.radiationDoseMgy === null || this.radiationDoseMgy === undefined)
    );
  }

  checkIn(): void {
    if (this.saving()) return;
    this.write(this.imaging.checkInPatient(this.order().id), 'IMAGING_EXAM.CHECKED_IN_DONE');
  }

  save(): void {
    if (this.saving() || this.nothingEntered()) return;
    const body: ImagingExamRecordRequest = {
      technicalNote: this.technicalNote.trim() || null,
      contrastAdministered: this.contrastAdministered,
      contrastDetails: this.contrastAdministered ? this.contrastDetails.trim() || null : null,
      radiationDoseMgy: this.radiationDoseMgy ?? null,
    };
    this.write(this.imaging.recordExam(this.order().id, body), 'IMAGING_EXAM.SAVED');
  }

  /** The reason is the cancel reason the status write requires. */
  discontinue(reason: string): void {
    if (this.saving()) return;
    this.write(
      this.imaging.updateOrderStatus(this.order().id, {
        status: 'CANCELLED',
        cancellationReason: reason,
      }),
      'IMAGING_EXAM.DISCONTINUED',
      () => this.discontinuing.set(false),
    );
  }

  private write(
    request: ReturnType<ImagingService['checkInPatient']>,
    doneKey: string,
    after?: () => void,
  ): void {
    this.saving.set(true);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.toast.success(this.translate.instant(doneKey));
        after?.();
        this.changed.emit(updated);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        const refused = refusedFieldReasons(err, {
          technicalNote: this.noteId,
          contrastDetails: this.contrastDetailsId,
          radiationDoseMgy: this.doseId,
        });
        if (refused.length) {
          refuseFields(
            this.host.nativeElement,
            refused.map(([id, message]) => ({ id, message })),
            [this.noteId, this.contrastDetailsId, this.doseId],
          );
        }
        this.toast.error(refusalReason(err) ?? this.translate.instant('IMAGING_EXAM.SAVE_FAILED'));
      },
    });
  }
}
