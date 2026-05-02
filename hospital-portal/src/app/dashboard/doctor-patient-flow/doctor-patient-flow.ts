import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';
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
  imports: [CommonModule, TranslateModule],
  templateUrl: './doctor-patient-flow.html',
  styleUrl: './doctor-patient-flow.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DoctorPatientFlowComponent implements OnInit, OnDestroy {
  private readonly translate = inject(TranslateService);

  // Bumped on every language change so computed labels re-evaluate.
  private readonly langTick = signal(0);
  private langSub?: Subscription;

  flowData = input<Record<string, PatientFlowItem[]>>({});
  patientSelected = output<string>();

  readonly columns = computed<FlowColumn[]>(() => {
    this.langTick();
    const t = (key: string) => this.translate.instant(key);
    return [
      { key: 'SCHEDULED', label: t('DASHBOARD.FLOW_COL.SCHEDULED'), icon: 'event', color: '#6366f1' },
      { key: 'ARRIVED', label: t('DASHBOARD.FLOW_COL.CHECKED_IN'), icon: 'how_to_reg', color: '#0891b2' },
      { key: 'TRIAGE', label: t('DASHBOARD.FLOW_COL.TRIAGE'), icon: 'monitor_heart', color: '#e11d48' },
      {
        key: 'WAITING_FOR_PHYSICIAN',
        label: t('DASHBOARD.FLOW_COL.WAITING_FOR_MD'),
        icon: 'person_search',
        color: '#d97706',
      },
      { key: 'IN_PROGRESS', label: t('DASHBOARD.FLOW_COL.IN_ENCOUNTER'), icon: 'stethoscope', color: '#2563eb' },
      {
        key: 'AWAITING_RESULTS',
        label: t('DASHBOARD.FLOW_COL.AWAITING_RESULTS'),
        icon: 'hourglass_empty',
        color: '#7c3aed',
      },
      {
        key: 'READY_FOR_DISCHARGE',
        label: t('DASHBOARD.FLOW_COL.READY_TO_DISCHARGE'),
        icon: 'exit_to_app',
        color: '#059669',
      },
      { key: 'COMPLETED', label: t('DASHBOARD.FLOW_COL.COMPLETED'), icon: 'task_alt', color: '#059669' },
      { key: 'CANCELLED', label: t('DASHBOARD.FLOW_COL.CANCELLED'), icon: 'cancel', color: '#94a3b8' },
    ];
  });

  ngOnInit(): void {
    this.langSub = this.translate.onLangChange.subscribe(() => {
      this.langTick.update((v) => v + 1);
    });
  }

  ngOnDestroy(): void {
    this.langSub?.unsubscribe();
  }

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
