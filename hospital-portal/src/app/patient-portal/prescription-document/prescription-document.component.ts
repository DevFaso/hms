import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  input,
  output,
  viewChild,
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

import { PortalPrescription } from '../../services/patient-portal.service';
import { DialogA11yDirective } from '../../shared/a11y/dialog-a11y.directive';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { printOnly } from '../../shared/print/print-only';

/**
 * QA D2353 (r63): the prescription as a document the patient can show at an
 * outside pharmacy or print (the browser's print offers "Save as PDF"): the
 * hospital, the prescriber, the date, every medicine with its dose, route,
 * frequency, duration, quantity and instructions. Read-only: everything on it
 * comes from the issued prescriptions the portal already reads.
 */
@Component({
  selector: 'app-prescription-document',
  standalone: true,
  imports: [DatePipe, DecimalPipe, ServerTimePipe, TranslateModule, DialogA11yDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './prescription-document.component.html',
  styleUrl: './prescription-document.component.scss',
})
export class PrescriptionDocumentComponent {
  /** The medicines on this document; the first one names the prescriber and the date. */
  readonly items = input.required<PortalPrescription[]>();
  readonly closed = output<void>();

  private readonly sheet = viewChild<ElementRef<HTMLElement>>('sheet');

  readonly head = computed(() => this.items()[0]);
  /** The signature date when there is one, else the day it was written. */
  readonly issuedAt = computed(() => this.head().signedAt || this.head().prescribedDate);

  print(): void {
    const sheet = this.sheet()?.nativeElement;
    if (sheet) printOnly(sheet);
  }

  close(): void {
    this.closed.emit();
  }
}
