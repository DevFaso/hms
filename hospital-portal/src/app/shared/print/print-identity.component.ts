import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  HostListener,
  Input,
  inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { EnumLabelPipe } from '../pipes/enum-label.pipe';
import { ServerTimePipe } from '../pipes/server-time.pipe';
import { DateTimePipe } from '../pipes/date-time.pipe';

/**
 * QA D2642 / D2643 (r65): the header of a printed clinical document. A sheet
 * carried to another doctor must say which hospital issued it and whom it is
 * about: the hospital (and its address), the patient's name, date of birth,
 * sex and N° de dossier, the prescriber, the clinical question, the exam date
 * and when it was printed. Paper only: on screen the page already shows these
 * where they belong. Used inside a block printed with `printOnly()` (the
 * shared print isolation); a field the caller does not have is left out, never
 * printed as an empty label.
 */
@Component({
  selector: 'app-print-identity',
  standalone: true,
  imports: [DatePipe, TranslateModule, EnumLabelPipe, ServerTimePipe, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="print-identity" data-testid="print-identity">
      @if (hospitalName) {
        <p class="pi-hospital">
          <strong>{{ hospitalName }}</strong>
          @if (hospitalAddress) {
            <span> · {{ hospitalAddress }}</span>
          }
        </p>
      }
      @if (title) {
        <p class="pi-title">{{ title }}</p>
      }
      <dl class="pi-grid">
        <div>
          <dt>{{ 'PRINT_IDENTITY.PATIENT' | translate }}</dt>
          <dd data-testid="print-identity-patient">{{ patientName || '—' }}</dd>
        </div>
        <div>
          <dt>{{ 'PATIENTS.DATE_OF_BIRTH' | translate }}</dt>
          <dd data-testid="print-identity-dob">
            {{ dateOfBirth ? (dateOfBirth | serverTime | date: 'mediumDate') : '—' }}
          </dd>
        </div>
        @if (sex) {
          <div>
            <dt>{{ 'PRINT_IDENTITY.SEX' | translate }}</dt>
            <dd>{{ sex | enumLabel: 'gender' }}</dd>
          </div>
        }
        <div>
          <dt>{{ 'IMAGING.MRN' | translate }}</dt>
          <dd data-testid="print-identity-mrn">{{ mrn || '—' }}</dd>
        </div>
        <div>
          <dt>{{ 'IMAGING.ORDERING_PROVIDER' | translate }}</dt>
          <dd data-testid="print-identity-prescriber">{{ prescriber || '—' }}</dd>
        </div>
        @if (examDate) {
          <div>
            <dt>{{ 'PRINT_IDENTITY.EXAM_DATE' | translate }}</dt>
            <dd>{{ examDate | serverTime | dateTime }}</dd>
          </div>
        }
        @if (clinicalQuestion) {
          <div class="pi-wide">
            <dt>{{ 'IMAGING.CLINICAL_QUESTION' | translate }}</dt>
            <dd>{{ clinicalQuestion }}</dd>
          </div>
        }
      </dl>
      <p class="pi-printed">
        {{ 'PRINT_IDENTITY.PRINTED_ON' | translate: { date: (printedAt | serverTime | dateTime) } }}
      </p>
    </header>
  `,
  styles: [
    `
      :host {
        display: none;
      }
      @media print {
        :host {
          display: block;
          margin-bottom: 12px;
          color: #000;
        }
      }
      .print-identity {
        border-bottom: 1px solid #000;
        padding-bottom: 8px;
        font-size: 10.5pt;
      }
      .pi-hospital,
      .pi-title,
      .pi-printed {
        margin: 0 0 4px;
      }
      .pi-title {
        font-size: 13pt;
        font-weight: 700;
      }
      .pi-grid {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 4px 16px;
        margin: 6px 0;
      }
      .pi-grid div {
        min-width: 0;
      }
      .pi-wide {
        grid-column: 1 / -1;
      }
      dt {
        font-size: 8.5pt;
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }
      dd {
        margin: 0;
        overflow-wrap: anywhere;
      }
      .pi-printed {
        font-size: 8.5pt;
      }
    `,
  ],
})
export class PrintIdentityComponent {
  @Input() hospitalName?: string | null;
  @Input() hospitalAddress?: string | null;
  @Input() title?: string | null;
  @Input() patientName?: string | null;
  @Input() dateOfBirth?: string | null;
  @Input() sex?: string | null;
  @Input() mrn?: string | null;
  @Input() prescriber?: string | null;
  @Input() clinicalQuestion?: string | null;
  @Input() examDate?: string | null;
  private readonly cdr = inject(ChangeDetectorRef);

  /** When the sheet was printed: stamped again as the browser starts the print. */
  printedAt = new Date();

  @HostListener('window:beforeprint')
  stampPrintTime(): void {
    this.printedAt = new Date();
    this.cdr.detectChanges();
  }
}
