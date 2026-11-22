import {
  ChangeDetectionStrategy,
  Component,
  OnChanges,
  OnDestroy,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import {
  AllergySeverity,
  PatientStoryboard,
  StoryboardService,
} from '../../services/storyboard.service';
import { EnumLabelPipe } from '../pipes/enum-label.pipe';
import { KeyLabelPipe } from '../pipes/key-label.pipe';
import { ServerTimePipe } from '../pipes/server-time.pipe';
import { serverDateOrInstant } from '../date-utils';

type LoadState = 'loading' | 'ready' | 'error' | 'hidden';

const SEVERE: ReadonlySet<AllergySeverity> = new Set(['SEVERE', 'LIFE_THREATENING']);

/**
 * QA r70 D3220 (owner priority): the patient-safety header of every clinical screen. The blood group was recorded
 * on the chart and appeared nowhere a clinical decision is made (the consultation, the nurse station, the lab,
 * pharmacy dispensing, the discharge). One component, so a screen cannot show the identity and forget the group:
 * name, age, sex, latest weight (dated), blood group (always labelled, « Non renseigné » when none) and the
 * allergies.
 *
 * <p>It reads the storyboard ({@code GET /patients/:id/storyboard}), which the server opens to the clinical roles
 * only (doctor, nurse, midwife, pharmacist, lab) and refuses to the front desk (RCPT-CLIN: the blood group is a
 * clinical field). A refused read renders nothing, so a non-clinical viewer of a shared screen sees no empty
 * band; any other failure says the header is unavailable, never a silent gap.
 *
 * <p>`patient-safety-header.test.mjs` keeps it on every clinical screen and keeps the blood group in it.
 */
@Component({
  selector: 'app-patient-safety-header',
  standalone: true,
  imports: [DatePipe, DecimalPipe, TranslateModule, EnumLabelPipe, KeyLabelPipe, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './patient-safety-header.component.html',
  styleUrl: './patient-safety-header.component.scss',
})
export class PatientSafetyHeaderComponent implements OnChanges, OnDestroy {
  readonly patientId = input<string | null | undefined>(null);
  readonly hospitalId = input<string | null | undefined>(null);
  /** Hide the name line where the host already shows the patient's name prominently. */
  readonly showName = input<boolean>(true);

  protected readonly state = signal<LoadState>('loading');
  protected readonly summary = signal<PatientStoryboard | null>(null);

  protected readonly patient = computed(() => this.summary()?.patient ?? null);
  protected readonly allergies = computed(() => this.summary()?.allergies ?? []);
  protected readonly hasSevereAllergy = computed(() =>
    this.allergies().some((a) => !!a.severity && SEVERE.has(a.severity)),
  );
  /** Under a year: months, which is what a child's dose is read against. */
  protected readonly age = computed(() => {
    const p = this.patient();
    if (!p) return null;
    if (p.ageYears != null && p.ageYears >= 1) {
      return { key: 'PATIENT_SAFETY.AGE_YEARS', n: p.ageYears };
    }
    if (p.dateOfBirth) {
      const born = serverDateOrInstant(p.dateOfBirth);
      if (!Number.isNaN(born.getTime())) {
        const now = new Date();
        const months =
          (now.getFullYear() - born.getFullYear()) * 12 + (now.getMonth() - born.getMonth());
        return { key: 'PATIENT_SAFETY.AGE_MONTHS', n: Math.max(0, months) };
      }
    }
    return p.ageYears != null ? { key: 'PATIENT_SAFETY.AGE_YEARS', n: p.ageYears } : null;
  });

  private readonly storyboard = inject(StoryboardService);
  private inFlight?: Subscription;

  ngOnChanges(): void {
    this.inFlight?.unsubscribe();
    const id = this.patientId();
    if (!id) {
      this.summary.set(null);
      this.state.set('hidden');
      return;
    }
    this.state.set('loading');
    // One read per patient; a stale answer for the previous patient is dropped with its subscription.
    this.inFlight = this.storyboard.getStoryboard(id, this.hospitalId() ?? undefined).subscribe({
      next: (s) => {
        this.summary.set(s ?? null);
        this.state.set(s ? 'ready' : 'error');
      },
      error: (err: unknown) => {
        this.summary.set(null);
        const status = err instanceof HttpErrorResponse ? err.status : 0;
        // Refused (a non-clinical role): nothing to show, and nothing to apologise for.
        this.state.set(status === 403 ? 'hidden' : 'error');
      },
    });
  }

  ngOnDestroy(): void {
    this.inFlight?.unsubscribe();
  }

  protected isSevere(severity?: AllergySeverity | null): boolean {
    return !!severity && SEVERE.has(severity);
  }
}
