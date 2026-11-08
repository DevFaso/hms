import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../core/toast.service';
import { MortalityService } from '../services/mortality.service';
import { BackdropCloseDirective } from '../shared/a11y/backdrop-close.directive';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { refuseFields } from '../shared/forms/invalid-fields';
import { refusalReason, refusedFieldReasons } from '../shared/refusal-reason';

/** The request's fields, by the control that shows them. */
const FIELD_IDS = {
  reason: 'dd-reason',
  assessedAlive: 'dd-alive',
  identityVerified: 'dd-identity',
};

/**
 * The urgent dispute of a recorded death, from the deceased patient's chart: a doctor who has the patient in front
 * of them, alive. Reason and both statements are required, here and on the server.
 */
@Component({
  selector: 'app-death-dispute-dialog',
  standalone: true,
  imports: [
    FormsModule,
    TranslateModule,
    BackdropCloseDirective,
    ModalDialogDirective,
    SubmitGuardDirective,
  ],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: `
    <div
      class="modal-backdrop"
      tabindex="-1"
      appBackdropClose
      (backdropClose)="dismissed.emit()"
      (keydown.escape)="dismissed.emit()"
    >
      <div
        class="modal"
        appModalDialog
        aria-labelledby="death-dispute-title"
        (escaped)="dismissed.emit()"
      >
        <div class="modal-header">
          <h2 id="death-dispute-title">{{ 'MORTALITY.DISPUTE' | translate }}</h2>
          <button class="modal-close" (click)="dismissed.emit()">&times;</button>
        </div>
        <div class="modal-body">
          <p class="modal-hint warn">{{ 'MORTALITY.DISPUTE_HINT' | translate }}</p>
          <div class="field">
            <label for="dd-reason">{{ 'MORTALITY.DISPUTE_REASON' | translate }} *</label>
            <input required id="dd-reason" type="text" maxlength="1000" [(ngModel)]="reason" />
          </div>
          <div class="checkbox-field">
            <input required id="dd-alive" type="checkbox" [(ngModel)]="assessedAlive" />
            <label for="dd-alive">{{ 'MORTALITY.DISPUTE_ASSESSED_ALIVE' | translate }} *</label>
          </div>
          <div class="checkbox-field">
            <input required id="dd-identity" type="checkbox" [(ngModel)]="identityVerified" />
            <label for="dd-identity"
              >{{ 'MORTALITY.DISPUTE_IDENTITY_VERIFIED' | translate }} *</label
            >
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" (click)="dismissed.emit()">
            {{ 'COMMON.CANCEL' | translate }}
          </button>
          <button
            submitGuardRequired
            class="btn-danger"
            data-testid="death-dispute-submit"
            (click)="submit()"
            [disabled]="submitting()"
            [appSubmitGuard]="!reason.trim() || !assessedAlive || !identityVerified"
            [submitGuardFields]="['dd-reason', 'dd-alive', 'dd-identity']"
          >
            {{ 'MORTALITY.DISPUTE' | translate }}
          </button>
        </div>
      </div>
    </div>
  `,
})
export class DeathDisputeDialogComponent {
  private readonly mortality = inject(MortalityService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly patientId = input.required<string>();
  readonly dismissed = output<void>();
  readonly disputed = output<void>();

  reason = '';
  assessedAlive = false;
  identityVerified = false;
  submitting = signal(false);

  async submit(): Promise<void> {
    const reason = this.reason.trim();
    if (!reason || !this.assessedAlive || !this.identityVerified) return;
    if (
      !(await this.confirm.ask({
        titleKey: 'MORTALITY.DISPUTE_CONFIRM',
        confirmKey: 'MORTALITY.DISPUTE',
      }))
    )
      return;
    this.submitting.set(true);
    this.mortality
      .disputeDeath(this.patientId(), {
        reason,
        assessedAlive: this.assessedAlive,
        identityVerified: this.identityVerified,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('MORTALITY.DISPUTE_STATUS'));
          this.submitting.set(false);
          this.disputed.emit();
        },
        error: (err: unknown) => {
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('MORTALITY.DISPUTE_FAILED'),
          );
          this.submitting.set(false);
          // Each field the server named carries its own reason.
          const refused = refusedFieldReasons(err, FIELD_IDS);
          if (refused.length > 0) {
            refuseFields(this.host.nativeElement, [], Object.values(FIELD_IDS), '');
            for (const [id, text] of [...refused].reverse()) {
              refuseFields(this.host.nativeElement, [id], [id], text);
            }
          }
        },
      });
  }
}
