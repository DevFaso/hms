import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PatientFlowItem } from '../../services/dashboard.service';

interface FlowColumn {
  key: string;
  label: string;
  icon: string;
  color: string;
}

@Component({
  selector: 'app-doctor-patient-flow',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './doctor-patient-flow.html',
  styleUrl: './doctor-patient-flow.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DoctorPatientFlowComponent {
  flowData = input<Record<string, PatientFlowItem[]>>({});
  patientSelected = output<string>();

  readonly columns: FlowColumn[] = [
    { key: 'ARRIVED', label: 'Checked In', icon: 'how_to_reg', color: '#0891b2' },
    { key: 'IN_PROGRESS', label: 'In Encounter', icon: 'stethoscope', color: '#2563eb' },
    {
      key: 'WAITING_FOR_PHYSICIAN',
      label: 'Waiting for MD',
      icon: 'person_search',
      color: '#d97706',
    },
    {
      key: 'AWAITING_RESULTS',
      label: 'Awaiting Results',
      icon: 'hourglass_empty',
      color: '#7c3aed',
    },
    {
      key: 'READY_FOR_DISCHARGE',
      label: 'Ready to Discharge',
      icon: 'exit_to_app',
      color: '#059669',
    },
    { key: 'COMPLETED', label: 'Completed', icon: 'task_alt', color: '#059669' },
    { key: 'CANCELLED', label: 'Cancelled', icon: 'cancel', color: '#94a3b8' },
  ];

  totalPatients = computed(() => {
    const data = this.flowData();
    return Object.values(data).reduce((sum, arr) => sum + arr.length, 0);
  });

  getColumnItems(key: string): PatientFlowItem[] {
    return this.flowData()[key] ?? [];
  }

  selectPatient(patientId: string): void {
    this.patientSelected.emit(patientId);
  }

  getElapsedClass(minutes: number): string {
    if (minutes > 30) return 'elapsed-red';
    if (minutes > 15) return 'elapsed-amber';
    return 'elapsed-green';
  }

  getInitials(name: string): string {
    const parts = (name ?? '').trim().split(' ');
    if (parts.length >= 2) return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase();
    return (parts[0]?.[0] ?? '?').toUpperCase();
  }
}
