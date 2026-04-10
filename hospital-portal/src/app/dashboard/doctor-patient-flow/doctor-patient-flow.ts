import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
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
  imports: [CommonModule, RouterLink, TranslateModule],
  templateUrl: './doctor-patient-flow.html',
  styleUrl: './doctor-patient-flow.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DoctorPatientFlowComponent {
  private readonly translate = inject(TranslateService);
  flowData = input<Record<string, PatientFlowItem[]>>({});
  patientSelected = output<string>();

  readonly columns: FlowColumn[] = [
    { key: 'SCHEDULED', label: 'Scheduled', icon: 'event', color: '#6366f1' },
    { key: 'ARRIVED', label: 'Checked In', icon: 'how_to_reg', color: '#0891b2' },
    { key: 'TRIAGE', label: 'Triage', icon: 'monitor_heart', color: '#e11d48' },
    {
      key: 'WAITING_FOR_PHYSICIAN',
      label: 'Waiting for MD',
      icon: 'person_search',
      color: '#d97706',
    },
    { key: 'IN_PROGRESS', label: 'In Encounter', icon: 'stethoscope', color: '#2563eb' },
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

  getTrackKey(item: PatientFlowItem): string {
    return item.encounterId || item.admissionId || item.patientId;
  }

  selectPatient(patientId: string): void {
    this.patientSelected.emit(patientId);
  }

  getSourceLabel(item: PatientFlowItem): string {
    return item.flowSource === 'ADMISSION'
      ? this.translate.instant('DASHBOARD.PATIENT_FLOW_INPATIENT')
      : this.translate.instant('DASHBOARD.PATIENT_FLOW_OUTPATIENT');
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
