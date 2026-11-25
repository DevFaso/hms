import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

import { ServerTimePipe } from '../pipes/server-time.pipe';
import { BloodGroupStatus } from './blood-group-status';

/**
 * QA D3901 (r77, High): the one way a screen shows the patient's blood group.
 *
 * The record used to print the group a doctor TYPED in red bold, as if verified, for a patient the laboratory had
 * grouped B+ (the typed A+ was ABO-incompatible with him). Here:
 * - the laboratory's confirmed group is the one shown, with « confirmé par le laboratoire » and where and when;
 * - a typed group alone is shown plainly and labelled « déclaré, non vérifié », never in the confirmed style;
 * - a typed group that differs from the confirmed one is shown as a visible warning beside it;
 * - the allo-immunisation (the blood bank's antibody flag, which follows the patient across hospitals) is an alert,
 *   on the reads of one chart (`showAntibodies`).
 *
 * `compact` is for a list cell: the group, then a short label. `scripts/blood-group-status.test.mjs` keeps every
 * screen that shows the group on this component.
 */
@Component({
  selector: 'app-blood-group-status',
  standalone: true,
  imports: [DatePipe, TranslateModule, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './blood-group-status.component.html',
  styleUrl: './blood-group-status.component.scss',
})
export class BloodGroupStatusComponent {
  readonly status = input<BloodGroupStatus | null | undefined>(null);
  /** The typed group of a response that carries no status (an older payload): shown as declared. */
  readonly declared = input<string | null | undefined>(null);
  readonly compact = input<boolean>(false);
  readonly showAntibodies = input<boolean>(true);

  protected readonly confirmed = computed(() => this.status()?.confirmed || null);
  protected readonly declaredGroup = computed(
    () => this.status()?.declared || this.declared() || null,
  );
  protected readonly discrepancy = computed(
    () =>
      !!this.confirmed() &&
      !!this.declaredGroup() &&
      (this.status()?.discrepancy ?? this.confirmed() !== this.declaredGroup()),
  );
  protected readonly alloImmunised = computed(
    () => this.showAntibodies() && this.status()?.alloImmunised === true,
  );
}
