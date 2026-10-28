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
import { TranslateModule } from '@ngx-translate/core';
import { NursingNote, NursingNoteService } from '../../services/nursing-note.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

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
  imports: [CommonModule, TranslateModule, ServerTimePipe],
  templateUrl: './nursing-notes-panel.component.html',
  styleUrl: './nursing-notes-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.Eager,
})
export class NursingNotesPanelComponent implements OnChanges {
  @Input({ required: true }) patientId!: string;
  @Input() hospitalId?: string | null;
  /** The visit's start (server local time); notes documented before it are another visit's. */
  @Input() since?: string | null;

  private readonly service = inject(NursingNoteService);
  private readonly destroyRef = inject(DestroyRef);

  readonly notes = signal<NursingNote[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);

  ngOnChanges(): void {
    if (!this.patientId) return;
    this.loading.set(true);
    this.failed.set(false);
    this.service
      .listRecent(this.patientId, this.hospitalId ?? undefined)
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
    if (!this.since) return sorted;
    // The visit's day, from its start: a note from earlier that day still belongs.
    const from = new Date(this.since.slice(0, 10) + 'T00:00:00').getTime();
    return Number.isNaN(from) ? sorted : sorted.filter((n) => stamp(n) >= from);
  }
}

function stamp(note: NursingNote): number {
  const at = note.documentedAt ?? note.eventOccurredAt;
  const t = at ? new Date(at).getTime() : 0;
  return Number.isNaN(t) ? 0 : t;
}
