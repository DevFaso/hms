import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PatientSnapshot } from '../../services/dashboard.service';

@Component({
  selector: 'app-patient-snapshot-drawer',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './patient-snapshot-drawer.html',
  styleUrl: './patient-snapshot-drawer.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PatientSnapshotDrawerComponent {
  snapshot = input<PatientSnapshot | null>(null);
  isOpen = input(false);
  closed = output<void>();

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
