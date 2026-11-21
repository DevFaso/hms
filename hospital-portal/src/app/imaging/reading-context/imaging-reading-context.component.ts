import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { ImagingReadingContext } from '../../services/imaging.service';
import { ImagingReadingService } from '../../services/imaging-reading.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { LabCorrectionBadgeComponent } from '../../lab/shared/lab-correction-badge.component';

/**
 * r63 (imaging completion): the radiologist read blind. Beside the order and
 * in the report editor, this block shows what the record already holds about
 * the patient: age and sex, a recorded pregnancy (due date) and the order's
 * pregnancy-test flag, the latest released creatinine, and the prior imaging
 * studies at this hospital. Read-only and ungraded: no threshold is applied.
 */
@Component({
  selector: 'app-imaging-reading-context',
  standalone: true,
  imports: [
    DatePipe,
    TranslateModule,
    EnumLabelPipe,
    ServerTimePipe,
    DateTimePipe,
    LabCorrectionBadgeComponent,
  ],
  templateUrl: './imaging-reading-context.component.html',
  styleUrl: './imaging-reading-context.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImagingReadingContextComponent implements OnChanges {
  private readonly reading = inject(ImagingReadingService);

  @Input({ required: true }) orderId!: string;
  /** Offer to open a prior study's signed report (the order drawer); off in the editor. */
  @Input() canOpenPrior = false;
  @Output() readonly openPriorReport = new EventEmitter<string>();

  readonly context = signal<ImagingReadingContext | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);

  private seq = 0;

  ngOnChanges(): void {
    this.load();
  }

  private load(): void {
    const seq = ++this.seq;
    this.context.set(null);
    this.failed.set(false);
    if (!this.orderId) return;
    this.loading.set(true);
    this.reading.readingContext(this.orderId).subscribe({
      next: (ctx) => {
        if (seq !== this.seq) return;
        this.context.set(ctx);
        this.loading.set(false);
      },
      error: () => {
        if (seq !== this.seq) return;
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }
}
