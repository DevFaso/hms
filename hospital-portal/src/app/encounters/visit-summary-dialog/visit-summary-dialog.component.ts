import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  input,
  output,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { TranslateModule } from '@ngx-translate/core';
import { AfterVisitSummary } from '../../services/encounter.service';
import {
  VisitSummarySectionsComponent,
  hasDocumentSections,
} from '../visit-summary-sections/visit-summary-sections.component';
import { printOnly } from '../../shared/print/print-only';

@Component({
  selector: 'app-visit-summary-dialog',
  standalone: true,
  imports: [CommonModule, TranslateModule, ModalDialogDirective, VisitSummarySectionsComponent],
  templateUrl: './visit-summary-dialog.component.html',
  styleUrl: './visit-summary-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VisitSummaryDialogComponent {
  readonly summary = input.required<AfterVisitSummary>();
  readonly closed = output<void>();
  readonly hasSections = computed(() => hasDocumentSections(this.summary()));

  private readonly dialog = viewChild.required<ElementRef<HTMLElement>>('dialog');

  /** Polish 2026-11-13: the summary alone on paper, without the page behind the dialog. */
  print(): void {
    printOnly(this.dialog().nativeElement);
  }
}
