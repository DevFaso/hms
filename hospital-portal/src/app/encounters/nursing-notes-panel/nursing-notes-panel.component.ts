import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Input,
  OnChanges,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { TranslateModule } from '@ngx-translate/core';
import { NursingNote, NursingNoteService } from '../../services/nursing-note.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { serverInstant, serverLocalDate } from '../../shared/date-utils';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { CredentialsLabelPipe } from '../../shared/pipes/credentials-label.pipe';

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
  imports: [CommonModule, TranslateModule, ServerTimePipe, EnumLabelPipe, CredentialsLabelPipe],
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

  readonly notes = signal<NursingNote[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);

  private request?: Subscription;

  ngOnChanges(): void {
    if (!this.patientId) return;
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
        this.hospitalId ?? undefined,
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
