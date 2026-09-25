import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { PatientSnapshot } from '../../services/dashboard.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-patient-snapshot-drawer',
  standalone: true,
  imports: [RouterLink, TranslateModule, EnumLabelPipe],
  templateUrl: './patient-snapshot-drawer.html',
  styleUrl: './patient-snapshot-drawer.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PatientSnapshotDrawerComponent {
  snapshot = input<PatientSnapshot | null>(null);
  isOpen = input(false);
  /** A snapshot read is in flight: the drawer opens on a spinner, not on nothing. */
  loading = input(false);
  /**
   * Why there is no snapshot to draw.
   *
   * `GET /me/patients/{id}/snapshot` is hospital-scoped and refuses with a
   * 404 when no scope resolves. The host used to close the drawer on any
   * failure, so a refusal looked like a dead button; and rendering it as an
   * empty drawer would be the same authorization-failure-as-no-data the house
   * rules out. NO_SCOPE is a different sentence from FAILED because it has a
   * different remedy: pick a hospital, rather than try again.
   */
  loadError = input<'NO_SCOPE' | 'FAILED' | null>(null);
  closed = output<void>();
  /** The Retry in the failure state; the host re-reads the same patient. */
  retryRequested = output<void>();

  // Section collapse states
  allergiesOpen = signal(true);
  diagnosesOpen = signal(true);
  medsOpen = signal(true);
  vitalsOpen = signal(true);
  labsOpen = signal(true);
  ordersOpen = signal(true);
  notesOpen = signal(false);
  teamOpen = signal(false);

  close(): void {
    this.closed.emit();
  }

  retry(): void {
    this.retryRequested.emit();
  }

  toggle(
    section: 'allergies' | 'diagnoses' | 'meds' | 'vitals' | 'labs' | 'orders' | 'notes' | 'team',
  ): void {
    const map = {
      allergies: this.allergiesOpen,
      diagnoses: this.diagnosesOpen,
      meds: this.medsOpen,
      vitals: this.vitalsOpen,
      labs: this.labsOpen,
      orders: this.ordersOpen,
      notes: this.notesOpen,
      team: this.teamOpen,
    };
    map[section].update((v) => !v);
  }
}
