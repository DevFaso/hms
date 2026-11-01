import { refusalReason } from '../../shared/refusal-reason';
import { DialogFocusDirective } from '../../shared/a11y/dialog-focus.directive';
import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EncounterService, UnseenVisitOutcome } from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';

/**
 * QA D788: the desk closes a visit no clinician has seen. It is "left without
 * being seen" or cancelled, always with a reason, and is never counted as a
 * consultation (the check-out is refused for it).
 */
@Component({
  selector: 'app-close-unseen-dialog',
  standalone: true,
  imports: [DialogFocusDirective, FormsModule, TranslateModule],
  templateUrl: './close-unseen-dialog.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: [
    '../walkin-dialog/walkin-dialog.component.scss',
    './close-unseen-dialog.component.scss',
  ],
})
export class CloseUnseenDialogComponent implements OnInit {
  @Input({ required: true }) encounterId!: string;
  @Input() patientName: string | null = null;
  @Input() initialOutcome: UnseenVisitOutcome = 'LEFT_WITHOUT_BEING_SEEN';
  @Output() dismissed = new EventEmitter<void>();
  @Output() closed = new EventEmitter<void>();

  private readonly encounterService = inject(EncounterService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly outcomes: UnseenVisitOutcome[] = ['LEFT_WITHOUT_BEING_SEEN', 'CANCELLED'];
  readonly outcome = signal<UnseenVisitOutcome>('LEFT_WITHOUT_BEING_SEEN');
  readonly reason = signal('');
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.outcome.set(this.initialOutcome);
  }

  submit(): void {
    const reason = this.reason().trim();
    if (!reason) {
      this.error.set(this.translate.instant('RECEPTION.CLOSE_UNSEEN_REASON_REQUIRED'));
      return;
    }
    this.error.set(null);
    this.saving.set(true);
    this.encounterService
      .closeUnseen(this.encounterId, { outcome: this.outcome(), reason })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.toast.success(this.translate.instant('RECEPTION.CLOSE_UNSEEN_DONE'));
          this.closed.emit();
        },
        error: (err) => {
          this.saving.set(false);
          this.error.set(
            refusalReason(err) ?? this.translate.instant('RECEPTION.CLOSE_UNSEEN_FAILED'),
          );
        },
      });
  }
}
