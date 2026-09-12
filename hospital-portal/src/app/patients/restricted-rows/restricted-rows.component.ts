import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { RestrictedRows } from '../../services/patient.service';

/**
 * E9 #64 — what a read withheld under decision D3, rendered as
 * <i>Dossier restreint (hôpital, département, n)</i> with one
 * <i>Ouvrir avec motif</i> action. The rows themselves never reach the
 * browser; the backend sends only where they are and how many.
 *
 * <p>Renders nothing for an empty list, so an in-hospital chart is
 * unchanged. The action is an output: the host page owns the break-glass
 * banner and opens its declaration modal, so the reason is stated once, in
 * the same textarea, whichever row prompted it.
 */
@Component({
  selector: 'app-restricted-rows',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './restricted-rows.component.html',
  styleUrl: './restricted-rows.component.scss',
})
export class RestrictedRowsComponent {
  readonly rows = input<RestrictedRows[]>([]);

  /** "Ouvrir avec motif": the host opens the break-the-glass declaration. */
  readonly openWithReason = output<void>();

  protected trackRow(row: RestrictedRows): string {
    return `${row.hospitalId ?? row.hospitalName ?? ''}|${row.departmentName ?? ''}`;
  }
}
