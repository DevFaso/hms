import {
  ChangeDetectionStrategy,
  Component,
  LOCALE_ID,
  computed,
  inject,
  input,
} from '@angular/core';
import { formatDate } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

/**
 * QA D4185 (r81): what tells two patients with the same name apart, shown beside the name on every
 * pharmacy row that identifies a patient (the dispensing queue, the dispense, hand-over and take-over
 * panels, the recent fills, the checkout's fill picker and receipt, the claims list):
 *
 *     <app-patient-id-line [mrn]="rx.patient?.mrn" [dateOfBirth]="rx.patient?.dateOfBirth" />
 *
 * « N° de dossier TWP9080 · Né(e) le 12 mars 1990 ». Either part is left out when the server sent none;
 * nothing at all is rendered when both are missing. The record number is the one of the acting hospital
 * (the server never sends another hospital's).
 *
 * `scripts/lib/r81-pharmacy-dispensing.test.mjs` keeps every pharmacy template that names a patient on it.
 */
@Component({
  selector: 'app-patient-id-line',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (mrn() || born()) {
      <span class="patient-id-line" data-testid="patient-id-line">
        @if (mrn(); as number) {
          <span class="patient-id-line__part" data-testid="patient-id-line-mrn">{{
            'PATIENT_ID_LINE.MRN' | translate: { mrn: number }
          }}</span>
        }
        @if (mrn() && born()) {
          <span aria-hidden="true"> · </span>
        }
        @if (born(); as date) {
          <span class="patient-id-line__part" data-testid="patient-id-line-dob">{{
            'PATIENT_ID_LINE.BORN' | translate: { date: date }
          }}</span>
        }
      </span>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .patient-id-line {
      display: block;
      font-size: 0.8125rem;
      line-height: 1.35;
      color: var(--text-secondary, inherit);
      font-weight: 400;
    }
    .patient-id-line__part {
      white-space: nowrap;
    }
  `,
})
export class PatientIdLineComponent {
  private readonly locale = inject(LOCALE_ID);

  readonly mrn = input<string | null | undefined>(null);
  /** The date of birth as the server sends it (yyyy-MM-dd). */
  readonly dateOfBirth = input<string | null | undefined>(null);

  /** The date of birth in the reader's language ("12 mars 1990"), or '' when absent or unreadable. */
  protected readonly born = computed(() => {
    const value = this.dateOfBirth();
    if (!value) return '';
    // A calendar day, not an instant: read as local so no time zone moves it to the day before.
    const day = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
    try {
      return formatDate(
        day ? new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3])) : value,
        'mediumDate',
        this.locale,
      );
    } catch {
      return '';
    }
  });
}
