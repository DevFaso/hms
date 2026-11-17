import {
  ChangeDetectionStrategy,
  Component,
  Input,
  OnChanges,
  OnDestroy,
  effect,
  inject,
} from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { PatientService } from '../services/patient.service';
import { RoleContextService } from '../core/role-context.service';
import { PATIENT_IDENTITY_EDIT_ROLES } from './patient-chart/chart-access';

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  selector: 'app-national-unique-id-view',
  standalone: true,
  imports: [TranslateModule],
  // QA D2031: the label and the value were glued and unstyled in the header.
  styles: [
    `
      .iu-view {
        display: inline-flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 0.5rem;
      }
      .iu-label {
        font-weight: 600;
      }
      .iu-value {
        font-variant-numeric: tabular-nums;
      }
    `,
  ],
  template: `@if (masked) {
    <div class="iu-view" data-testid="national-iu-view">
      <span class="iu-label">{{ 'NATIONAL_IU.LABEL' | translate }}</span>
      <span class="iu-value">{{ fullValue ?? masked }}</span>
      <!-- QA D2100: one toggle whose label switches. Swapping two buttons with
           @if destroyed the focused element and dropped focus to the page h1;
           aria-disabled (not [disabled]) keeps focus on it while the request runs. -->
      @if (canReveal() || fullValue) {
        <button
          type="button"
          class="btn-secondary"
          data-testid="national-iu-toggle"
          (click)="toggle()"
          [attr.aria-disabled]="busy ? 'true' : null"
        >
          {{ (fullValue ? 'NATIONAL_IU.HIDE' : 'NATIONAL_IU.SHOW_FULL') | translate }}
        </button>
      }
      @if (failed) {
        <small role="alert">{{ 'NATIONAL_IU.UNAVAILABLE' | translate }}</small>
      }
    </div>
  }`,
})
export class NationalUniqueIdViewComponent implements OnChanges, OnDestroy {
  private readonly patients = inject(PatientService);
  private readonly roles = inject(RoleContextService);
  @Input() patientId!: string;
  @Input() masked?: string | null;
  fullValue: string | null = null;
  busy = false;
  failed = false;
  private epoch = 0;
  constructor() {
    effect(() => {
      this.roles.effectiveHospitalIdForRequest();
      this.canReveal();
      this.clear();
    });
  }
  canReveal(): boolean {
    return this.roles.hasAnyActiveRole(PATIENT_IDENTITY_EDIT_ROLES);
  }
  ngOnChanges(): void {
    this.clear();
  }
  ngOnDestroy(): void {
    this.clear();
  }
  clear(): void {
    this.epoch++;
    this.fullValue = null;
    this.busy = false;
    this.failed = false;
  }
  toggle(): void {
    if (this.fullValue) this.clear();
    else this.reveal();
  }
  reveal(): void {
    if (!this.canReveal() || this.busy) return;
    const epoch = ++this.epoch;
    this.busy = true;
    this.patients.revealNationalUniqueId(this.patientId).subscribe({
      next: (result) => {
        if (epoch !== this.epoch) return;
        this.fullValue = result.nationalUniqueId;
        this.busy = false;
      },
      error: () => {
        if (epoch === this.epoch) {
          this.busy = false;
          this.failed = true;
        }
      },
    });
  }
}
