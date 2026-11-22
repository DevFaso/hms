import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import {
  ImagingCatalogueExam,
  ImagingCatalogueService,
} from '../../services/imaging-catalogue.service';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { refusalReason } from '../../shared/refusal-reason';
import { RuleStatusComponent } from '../../shared/rule-status/rule-status.component';

/** The three answers to « peut révéler une grossesse » : nobody decided, yes, no. */
export type PregnancyFlagChoice = 'UNDECIDED' | 'YES' | 'NO';

interface ExamDraft {
  flag: PregnancyFlagChoice;
  bodyRegion: string;
}

function choiceOf(flag: boolean | null): PregnancyFlagChoice {
  if (flag === true) return 'YES';
  if (flag === false) return 'NO';
  return 'UNDECIDED';
}

/**
 * QA D3450 / D3254 (r70): the hospital's imaging catalogue, as the hospital admin and the
 * radiologist read it: the exams an imaging order is picked from (the IMAGING lines of the price
 * list), each with its body region and the answer to « peut révéler une grossesse ».
 *
 * A flagged exam is never shown to a family proxy; a cleared one is; an exam nobody decided is
 * read by its name, and held back for a patient who may be pregnant. The rule is provisional: the
 * block says so with the hospital's own sign-off status. Names, codes and prices are edited on the
 * price list, not here.
 */
@Component({
  selector: 'app-imaging-catalogue',
  standalone: true,
  imports: [
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    ServerTimePipe,
    DateTimePipe,
    RuleStatusComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="img-catalogue" data-testid="imaging-catalogue">
      <h2 class="img-catalogue__title">{{ 'IMAGING_CATALOGUE.TITLE' | translate }}</h2>
      <p class="img-catalogue__intro">{{ 'IMAGING_CATALOGUE.INTRO' | translate }}</p>
      <p class="img-catalogue__rule" data-testid="imaging-catalogue-rule">
        <app-rule-status ruleKey="PREGNANCY_REVEALING_IMAGING">{{
          'IMAGING_CATALOGUE.PROVISIONAL' | translate
        }}</app-rule-status>
      </p>
      @if (loading()) {
        <p class="img-catalogue__note" role="status">{{ 'COMMON.LOADING' | translate }}</p>
      } @else if (loadFailed()) {
        <p class="img-catalogue__note" role="alert" data-testid="imaging-catalogue-failed">
          {{ loadFailure() ?? ('IMAGING_CATALOGUE.LOAD_FAILED' | translate) }}
        </p>
        <button type="button" class="btn-secondary" (click)="load()">
          {{ 'IMAGING_CATALOGUE.RETRY' | translate }}
        </button>
      } @else if (!exams().length) {
        <p class="img-catalogue__note" data-testid="imaging-catalogue-empty">
          {{ 'IMAGING_CATALOGUE.EMPTY' | translate }}
        </p>
      } @else {
        <ul class="img-catalogue__list">
          @for (exam of exams(); track exam.id) {
            <li class="img-catalogue__exam" [attr.data-testid]="'catalogue-exam-' + exam.code">
              <div class="img-catalogue__name">
                <strong>{{ exam.label }}</strong>
                <span class="img-catalogue__meta"
                  >{{ exam.code }}
                  @if (exam.modality) {
                    · {{ exam.modality | enumLabel: 'imagingModality' }}
                  }
                </span>
              </div>
              <div class="img-catalogue__field">
                <label [attr.for]="'cat-region-' + exam.id">{{
                  'IMAGING_CATALOGUE.BODY_REGION' | translate
                }}</label>
                <input
                  type="text"
                  maxlength="150"
                  [id]="'cat-region-' + exam.id"
                  [attr.data-testid]="'catalogue-region-' + exam.code"
                  [disabled]="!exam.canEdit"
                  [ngModel]="draftOf(exam).bodyRegion"
                  (ngModelChange)="setRegion(exam, $event)"
                />
              </div>
              <div class="img-catalogue__field">
                <label [attr.for]="'cat-flag-' + exam.id">{{
                  'IMAGING_CATALOGUE.FLAG' | translate
                }}</label>
                <select
                  [id]="'cat-flag-' + exam.id"
                  [attr.data-testid]="'catalogue-flag-' + exam.code"
                  [disabled]="!exam.canEdit"
                  [ngModel]="draftOf(exam).flag"
                  (ngModelChange)="setFlag(exam, $event)"
                >
                  <option value="UNDECIDED">
                    {{ 'IMAGING_CATALOGUE.FLAG_UNDECIDED' | translate }}
                  </option>
                  <option value="YES">{{ 'IMAGING_CATALOGUE.FLAG_YES' | translate }}</option>
                  <option value="NO">{{ 'IMAGING_CATALOGUE.FLAG_NO' | translate }}</option>
                </select>
              </div>
              <p class="img-catalogue__status" [attr.data-testid]="'catalogue-status-' + exam.code">
                {{ statusKey(exam) | translate }}
                @if (exam.pregnancyFlagDecidedAt) {
                  {{
                    'IMAGING_CATALOGUE.DECIDED_BY'
                      | translate
                        : {
                            name:
                              exam.pregnancyFlagDecidedBy ||
                              ('IMAGING_CATALOGUE.SOMEONE' | translate),
                            date: exam.pregnancyFlagDecidedAt | serverTime | dateTime,
                          }
                  }}
                }
              </p>
              @if (exam.canEdit) {
                <div class="img-catalogue__actions">
                  <button
                    type="button"
                    class="btn-primary btn-sm"
                    [attr.data-testid]="'catalogue-save-' + exam.code"
                    [disabled]="!dirty(exam) || savingId() === exam.id"
                    (click)="save(exam)"
                  >
                    {{ 'COMMON.SAVE' | translate }}
                  </button>
                </div>
              }
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    .img-catalogue {
      min-width: 0;
    }
    .img-catalogue__title {
      margin: 0 0 8px;
      font-size: 1.1rem;
    }
    .img-catalogue__intro,
    .img-catalogue__note,
    .img-catalogue__rule {
      margin: 0 0 12px;
      color: var(--text-secondary);
      overflow-wrap: anywhere;
    }
    .img-catalogue__list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: 12px;
    }
    .img-catalogue__exam {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(0, 1.2fr) minmax(0, 1.4fr) auto;
      gap: 8px 16px;
      align-items: end;
      padding: 12px;
      border: 1px solid var(--border-color, #e2e8f0);
      border-radius: 8px;
      background: var(--surface, #fff);
      min-width: 0;
    }
    .img-catalogue__name {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
      align-self: center;
      overflow-wrap: anywhere;
    }
    .img-catalogue__meta {
      color: var(--text-secondary);
      font-size: 0.85rem;
    }
    .img-catalogue__field {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
    }
    .img-catalogue__field label {
      font-size: 0.85rem;
      font-weight: 600;
      overflow-wrap: anywhere;
    }
    .img-catalogue__field input,
    .img-catalogue__field select {
      width: 100%;
      min-width: 0;
      box-sizing: border-box;
    }
    .img-catalogue__status {
      grid-column: 1 / -1;
      margin: 0;
      color: var(--text-secondary);
      font-size: 0.85rem;
      overflow-wrap: anywhere;
    }
    .img-catalogue__actions {
      grid-column: 4;
      grid-row: 1;
      align-self: end;
    }
    @media (max-width: 900px) {
      .img-catalogue__exam {
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      }
      .img-catalogue__name {
        grid-column: 1 / -1;
      }
      .img-catalogue__actions {
        grid-column: 1 / -1;
        grid-row: auto;
        justify-self: start;
      }
    }
    @media (max-width: 520px) {
      .img-catalogue__exam {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class ImagingCatalogueComponent implements OnInit {
  private readonly api = inject(ImagingCatalogueService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  readonly exams = signal<ImagingCatalogueExam[]>([]);
  readonly loading = signal(false);
  readonly loadFailed = signal(false);
  /** The server's own words when the read is refused (a role held at another hospital). */
  readonly loadFailure = signal<string | null>(null);
  readonly savingId = signal<string | null>(null);
  /** What the reader changed and has not saved, by exam id. */
  private readonly drafts = signal<Record<string, ExamDraft>>({});

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.loadFailure.set(null);
    this.api
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (exams) => {
          this.exams.set(exams ?? []);
          this.drafts.set({});
          this.loading.set(false);
        },
        error: (err) => {
          this.loading.set(false);
          this.loadFailed.set(true);
          this.loadFailure.set(refusalReason(err));
        },
      });
  }

  draftOf(exam: ImagingCatalogueExam): ExamDraft {
    return (
      this.drafts()[exam.id] ?? {
        flag: choiceOf(exam.mayRevealPregnancy),
        bodyRegion: exam.bodyRegion ?? '',
      }
    );
  }

  setFlag(exam: ImagingCatalogueExam, flag: PregnancyFlagChoice): void {
    this.drafts.update((all) => ({ ...all, [exam.id]: { ...this.draftOf(exam), flag } }));
  }

  setRegion(exam: ImagingCatalogueExam, bodyRegion: string): void {
    this.drafts.update((all) => ({ ...all, [exam.id]: { ...this.draftOf(exam), bodyRegion } }));
  }

  dirty(exam: ImagingCatalogueExam): boolean {
    const draft = this.draftOf(exam);
    return (
      draft.flag !== choiceOf(exam.mayRevealPregnancy) ||
      draft.bodyRegion.trim() !== (exam.bodyRegion ?? '')
    );
  }

  /** What the exam's answer means for a family proxy, in words. */
  statusKey(exam: ImagingCatalogueExam): string {
    if (exam.mayRevealPregnancy === true) return 'IMAGING_CATALOGUE.STATUS_YES';
    if (exam.mayRevealPregnancy === false) return 'IMAGING_CATALOGUE.STATUS_NO';
    return exam.namedAsPregnancyStudy
      ? 'IMAGING_CATALOGUE.STATUS_UNDECIDED_NAMED'
      : 'IMAGING_CATALOGUE.STATUS_UNDECIDED';
  }

  save(exam: ImagingCatalogueExam): void {
    if (this.savingId() || !this.dirty(exam)) return;
    const draft = this.draftOf(exam);
    const flags: Record<PregnancyFlagChoice, boolean | null> = {
      UNDECIDED: null,
      YES: true,
      NO: false,
    };
    this.savingId.set(exam.id);
    this.api
      .update(exam.id, {
        mayRevealPregnancy: flags[draft.flag],
        bodyRegion: draft.bodyRegion.trim() || null,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (saved) => {
          this.savingId.set(null);
          this.exams.update((list) => list.map((e) => (e.id === saved.id ? saved : e)));
          this.drafts.update((all) => {
            const rest = { ...all };
            delete rest[exam.id];
            return rest;
          });
          this.toast.success(this.translate.instant('IMAGING_CATALOGUE.SAVED'));
        },
        error: (err) => {
          this.savingId.set(null);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('IMAGING_CATALOGUE.SAVE_FAILED'),
          );
        },
      });
  }
}
