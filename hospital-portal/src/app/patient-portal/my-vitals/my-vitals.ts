import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  HomeVitalReading,
  PatientPortalService,
  VitalSignSummary,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-my-vitals',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule],
  templateUrl: './my-vitals.html',
  styleUrl: './my-vitals.scss',
})
export class MyVitalsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);

  vitals = signal<VitalSignSummary[]>([]);
  loading = signal(true);

  // Record modal state
  showRecordForm = signal(false);
  submitting = signal(false);
  vitalForm = signal<HomeVitalReading>(this.emptyForm());

  ngOnInit() {
    this.portal.getMyVitals().subscribe({
      next: (v) => {
        this.vitals.set(v);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  getVitalIcon(type: string): string {
    const map: Record<string, string> = {
      BLOOD_PRESSURE: 'vital_signs',
      HEART_RATE: 'heart_check',
      TEMPERATURE: 'thermostat',
      WEIGHT: 'monitor_weight',
      HEIGHT: 'height',
      OXYGEN_SATURATION: 'spo2',
      RESPIRATORY_RATE: 'pulmonology',
      BMI: 'speed',
    };
    return map[type?.toUpperCase()] || 'monitor_heart';
  }

  openRecordForm(): void {
    this.vitalForm.set(this.emptyForm());
    this.showRecordForm.set(true);
  }

  closeRecordForm(): void {
    this.showRecordForm.set(false);
  }

  updateField<K extends keyof HomeVitalReading>(field: K, value: HomeVitalReading[K]): void {
    this.vitalForm.update((f) => ({ ...f, [field]: value }));
  }

  updateNumericField(field: keyof HomeVitalReading, raw: string): void {
    const n = raw ? parseFloat(raw) : undefined;
    this.vitalForm.update((f) => ({ ...f, [field]: n }));
  }

  hasAnyValue(): boolean {
    const f = this.vitalForm();
    return !!(
      f.systolicBpMmHg ||
      f.diastolicBpMmHg ||
      f.heartRateBpm ||
      f.respiratoryRateBpm ||
      f.spo2Percent ||
      f.temperatureCelsius ||
      f.bloodGlucoseMgDl ||
      f.weightKg
    );
  }

  confirmRecord(): void {
    if (!this.hasAnyValue() || this.submitting()) return;
    this.submitting.set(true);

    const dto: HomeVitalReading = {};
    const f = this.vitalForm();
    if (f.systolicBpMmHg) dto.systolicBpMmHg = f.systolicBpMmHg;
    if (f.diastolicBpMmHg) dto.diastolicBpMmHg = f.diastolicBpMmHg;
    if (f.heartRateBpm) dto.heartRateBpm = f.heartRateBpm;
    if (f.respiratoryRateBpm) dto.respiratoryRateBpm = f.respiratoryRateBpm;
    if (f.spo2Percent) dto.spo2Percent = f.spo2Percent;
    if (f.temperatureCelsius) dto.temperatureCelsius = f.temperatureCelsius;
    if (f.bloodGlucoseMgDl) dto.bloodGlucoseMgDl = f.bloodGlucoseMgDl;
    if (f.weightKg) dto.weightKg = f.weightKg;
    if (f.bodyPosition) dto.bodyPosition = f.bodyPosition;
    if (f.notes?.trim()) dto.notes = f.notes!.trim();

    this.portal.recordHomeVital(dto).subscribe({
      next: (saved) => {
        this.vitals.update((list) => [saved, ...list]);
        this.toast.success('Vital reading recorded successfully.');
        this.submitting.set(false);
        this.closeRecordForm();
      },
      error: () => {
        this.toast.error('Failed to record vital reading. Please try again.');
        this.submitting.set(false);
      },
    });
  }

  private emptyForm(): HomeVitalReading {
    return {
      systolicBpMmHg: undefined,
      diastolicBpMmHg: undefined,
      heartRateBpm: undefined,
      respiratoryRateBpm: undefined,
      spo2Percent: undefined,
      temperatureCelsius: undefined,
      bloodGlucoseMgDl: undefined,
      weightKg: undefined,
      bodyPosition: '',
      notes: '',
    };
  }
}
