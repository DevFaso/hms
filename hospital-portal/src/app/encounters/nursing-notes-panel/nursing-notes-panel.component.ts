import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
import { NoteAddendaComponent } from '../../shared/note-addenda/note-addenda.component';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Input,
  OnChanges,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { TranslateService } from '@ngx-translate/core';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { TranslateModule } from '@ngx-translate/core';
import { NursingNote, NursingNoteService } from '../../services/nursing-note.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { serverInstant, serverLocalDate } from '../../shared/date-utils';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { CredentialsLabelPipe } from '../../shared/pipes/credentials-label.pipe';
import { HospitalTypeaheadComponent } from '../../shared/hospital-typeahead/hospital-typeahead.component';
import { HospitalResponse } from '../../services/hospital.service';

/** One labelled part of a note, in the order the template writes it. */
export interface NursingNotePart {
  labelKey: string;
  text: string;
}

/**
 * QA D927: the nursing care notes of the visit's patient, newest first, in
 * the encounter detail. A note written since the visit began belongs to it;
 * older ones are hidden unless `since` is not given.
 */
@Component({
  selector: 'app-nursing-notes-panel',
  standalone: true,
  imports: [
    FormControlStyleDirective,
    NoteAddendaComponent,
    HospitalTypeaheadComponent,
    CommonModule,
    FormsModule,
    SubmitGuardDirective,
    TranslateModule,
    ServerTimePipe,
    DateTimePipe,
    EnumLabelPipe,
    CredentialsLabelPipe,
  ],
  templateUrl: './nursing-notes-panel.component.html',
  styleUrl: './nursing-notes-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.Eager,
})
export class NursingNotesPanelComponent implements OnChanges {
  @Input({ required: true }) patientId!: string;
  @Input() hospitalId?: string | null;
  /** The visit's start (server local time); notes documented before it are another visit's. */
  @Input() since?: string | null;
  /**
   * The visit's end (its checkout, server local time), when it is closed.
   * Notes documented after that day are a later visit's (review of #971).
   */
  @Input() until?: string | null;

  /** The server's maximum page (NursingNoteController.MAX_LIMIT). */
  private static readonly CLOSED_VISIT_LIMIT = 50;

  private readonly service = inject(NursingNoteService);
  private readonly destroyRef = inject(DestroyRef);

  /** QA D2583: the notes this hospital wrote take an addendum here; `false` shows them read-only. */
  @Input() allowAddendum = true;
  /** The empty state: « for this visit » in a visit, « for this patient » on the chart and the ward. */
  @Input() emptyKey = 'NURSING_NOTES.EMPTY';

  private readonly roleContext = inject(RoleContextService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly notes = signal<NursingNote[]>([]);
  /** QA D2583 (r65): the note whose addendum form is open, its text, attestation and save state. */
  readonly addendumFor = signal<string | null>(null);
  addendumText = '';
  addendumAttested = false;
  readonly addendumSaving = signal(false);
  readonly loading = signal(false);
  readonly failed = signal(false);

  private request?: Subscription;
  private lastPatientId?: string;
  private lastInputHospitalId: string | null | undefined;
  readonly localHospital = signal<HospitalResponse | null>(null);
  readonly choosingHospital = signal(false);

  needsLocalHospital(): boolean {
    return (
      !this.hospitalId && this.roleContext.isSuperAdmin() && !this.roleContext.hasHospitalScope()
    );
  }

  selectLocalHospital(hospital: HospitalResponse): void {
    if (this.addendumFor() || this.addendumSaving()) return;
    this.localHospital.set(hospital);
    this.choosingHospital.set(false);
    this.ngOnChanges();
  }

  private effectiveHospitalId(): string | undefined {
    return this.hospitalId ?? (this.needsLocalHospital() ? this.localHospital()?.id : undefined);
  }

  ngOnChanges(): void {
    const inputHospitalId = this.hospitalId ?? null;
    if (this.lastPatientId !== this.patientId || this.lastInputHospitalId !== inputHospitalId) {
      this.localHospital.set(null);
      this.choosingHospital.set(false);
      this.lastPatientId = this.patientId;
      this.lastInputHospitalId = inputHospitalId;
    }
    if (!this.patientId) return;
    if (this.needsLocalHospital() && !this.localHospital()) {
      this.request?.unsubscribe();
      this.notes.set([]);
      this.loading.set(false);
      this.failed.set(false);
      return;
    }
    // Review of #971: the panel is reused when another visit is opened; the
    // previous patient's request is dropped so its late answer cannot put
    // that patient's notes under this visit.
    this.request?.unsubscribe();
    this.notes.set([]);
    this.loading.set(true);
    this.failed.set(false);
    this.request = this.service
      // A closed visit reads further back: the newest notes may all be a later visit's.
      .listRecent(
        this.patientId,
        this.effectiveHospitalId(),
        ...(this.until ? [NursingNotesPanelComponent.CLOSED_VISIT_LIMIT] : []),
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.notes.set(this.withinVisit(list ?? []));
          this.loading.set(false);
        },
        error: () => {
          this.notes.set([]);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }

  /**
   * QA D2583: an addendum is offered on a note of the acting hospital (the
   * server loads the note by id AND hospital), to the roles that may write one.
   */
  canAddend(note: NursingNote): boolean {
    if (!this.allowAddendum || this.needsLocalHospital()) return false;
    if (!this.roleContext.hasAnyActiveRole(NursingNoteService.ADDENDUM_ROLES)) return false;
    const hospitalId = this.effectiveHospitalId();
    return !hospitalId || !note.hospitalId || note.hospitalId === hospitalId;
  }

  openAddendum(note: NursingNote): void {
    this.addendumFor.set(note.id);
    this.addendumText = '';
    this.addendumAttested = false;
  }

  cancelAddendum(): void {
    this.addendumFor.set(null);
    this.addendumText = '';
    this.addendumAttested = false;
  }

  saveAddendum(note: NursingNote): void {
    const content = this.addendumText.trim();
    if (!content || this.addendumSaving()) return;
    this.addendumSaving.set(true);
    this.service
      .appendAddendum(
        note.id,
        content,
        this.addendumAttested,
        this.effectiveHospitalId() ?? note.hospitalId,
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          // The server answers with the whole note, its addenda included.
          this.notes.update((list) =>
            list.map((n) => (n.id === note.id ? { ...n, ...updated, id: n.id } : n)),
          );
          this.addendumSaving.set(false);
          this.cancelAddendum();
          this.toast.success(this.translate.instant('NURSING_NOTES.ADDENDUM_SAVED'));
        },
        error: (err: unknown) => {
          this.addendumSaving.set(false);
          this.markRefused(err, `nn-addendum-${note.id}`);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('NURSING_NOTES.ADDENDUM_FAILED'),
          );
        },
      });
  }

  /** The server's own reason at the field it names (NursingNoteAddendumRequestDTO.content). */
  private markRefused(err: unknown, id: string): void {
    const refused = refusedFieldReasons(err, { content: id });
    if (!refused.length) return;
    refuseFields(
      this.host.nativeElement,
      refused.map(([fieldId, message]) => ({ id: fieldId, message })),
      [id],
    );
  }

  /** The labelled parts of a note: DAR or SOAPIE, then the narrative. */
  parts(note: NursingNote): NursingNotePart[] {
    const all: [string, string | null | undefined][] =
      note.template === 'DAR'
        ? [
            ['NURSING_NOTES.DATA', note.dataSubjective ?? note.dataObjective],
            ['NURSING_NOTES.ACTION', note.actionSummary ?? note.dataImplementation],
            ['NURSING_NOTES.RESPONSE', note.responseSummary ?? note.dataEvaluation],
          ]
        : [
            ['NURSING_NOTES.SUBJECTIVE', note.dataSubjective],
            ['NURSING_NOTES.OBJECTIVE', note.dataObjective],
            ['NURSING_NOTES.ASSESSMENT', note.dataAssessment],
            ['NURSING_NOTES.PLAN', note.dataPlan],
            ['NURSING_NOTES.IMPLEMENTATION', note.dataImplementation],
            ['NURSING_NOTES.EVALUATION', note.dataEvaluation],
          ];
    all.push(['NURSING_NOTES.EDUCATION', note.educationSummary]);
    all.push(['NURSING_NOTES.NARRATIVE', note.narrative]);
    return all
      .filter(([, text]) => !!text && text.trim().length > 0)
      .map(([labelKey, text]) => ({ labelKey, text: (text as string).trim() }));
  }

  private withinVisit(list: NursingNote[]): NursingNote[] {
    const sorted = [...list].sort((a, b) => stamp(b) - stamp(a));
    // The visit's days, from the start of its first to the end of its last:
    // a note from earlier on the arrival day, or charted late on the checkout
    // day, still belongs; one from a later day is another visit's.
    const from = this.since
      ? new Date(`${serverLocalDate(this.since)}T00:00:00`).getTime()
      : Number.NaN;
    const to = this.until
      ? new Date(`${serverLocalDate(this.until)}T23:59:59.999`).getTime()
      : Number.NaN;
    return sorted.filter(
      (n) => (Number.isNaN(from) || stamp(n) >= from) && (Number.isNaN(to) || stamp(n) <= to),
    );
  }
}

function stamp(note: NursingNote): number {
  const at = note.documentedAt ?? note.eventOccurredAt;
  const t = at ? serverInstant(at).getTime() : 0;
  return Number.isNaN(t) ? 0 : t;
}
