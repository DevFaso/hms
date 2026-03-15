import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnChanges,
  SimpleChanges,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReceptionService, FrontDeskPatientSnapshot } from './reception.service';

@Component({
  selector: 'app-patient-snapshot-drawer',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './patient-snapshot-drawer.html',
  styleUrl: './patient-snapshot-drawer.scss',
})
export class PatientSnapshotDrawerComponent implements OnChanges {
  @Input() patientId: string | null = null;
  @Output() panelClosed = new EventEmitter<void>();

  private readonly receptionService = inject(ReceptionService);

  snapshot = signal<FrontDeskPatientSnapshot | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['patientId'] && this.patientId) {
      this.loadSnapshot(this.patientId);
    }
  }

  private loadSnapshot(patientId: string): void {
    this.loading.set(true);
    this.error.set(null);
    this.snapshot.set(null);
    this.receptionService.getPatientSnapshot(patientId).subscribe({
      next: (s) => {
        this.snapshot.set(s);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load patient snapshot.');
        this.loading.set(false);
      },
    });
  }
}
