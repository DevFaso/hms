import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  LabInstrumentService,
  LabInstrumentResponse,
  LabInstrumentRequest,
} from '../../services/lab-instrument.service';
import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-lab-instruments',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, TranslateModule],
  templateUrl: './lab-instruments.html',
  styleUrl: './lab-instruments.scss',
})
export class LabInstrumentsComponent implements OnInit {
  private readonly instrumentService = inject(LabInstrumentService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  loading = signal(true);
  error = signal<string | null>(null);
  instruments = signal<LabInstrumentResponse[]>([]);
  totalElements = signal(0);

  /** Whether user can create/edit/delete instruments. */
  canManage = computed(() => {
    const roles = this.auth.getRoles();
    return (
      roles.includes('ROLE_SUPER_ADMIN') ||
      roles.includes('ROLE_HOSPITAL_ADMIN') ||
      roles.includes('ROLE_LAB_DIRECTOR') ||
      roles.includes('ROLE_LAB_MANAGER')
    );
  });

  showForm = signal(false);
  editingId = signal<string | null>(null);
  saving = signal(false);

  form: LabInstrumentRequest = this.emptyForm();

  ngOnInit(): void {
    this.loadInstruments();
  }

  loadInstruments(): void {
    const hospitalId = this.auth.getHospitalId();
    if (!hospitalId) {
      this.error.set(this.translate.instant('LAB_INSTRUMENTS.NO_HOSPITAL'));
      this.loading.set(false);
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.instrumentService.getInstruments(hospitalId).subscribe({
      next: (page) => {
        this.instruments.set(page.content ?? []);
        this.totalElements.set(page.totalElements ?? 0);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Failed to load instruments', err);
        this.error.set(this.translate.instant('LAB_INSTRUMENTS.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editingId.set(null);
    this.showForm.set(true);
  }

  openEdit(instrument: LabInstrumentResponse): void {
    this.form = {
      name: instrument.name,
      manufacturer: instrument.manufacturer,
      modelNumber: instrument.modelNumber,
      serialNumber: instrument.serialNumber,
      departmentId: instrument.departmentId,
      status: instrument.status,
      installationDate: instrument.installationDate,
      lastCalibrationDate: instrument.lastCalibrationDate,
      nextCalibrationDate: instrument.nextCalibrationDate,
      lastMaintenanceDate: instrument.lastMaintenanceDate,
      nextMaintenanceDate: instrument.nextMaintenanceDate,
      notes: instrument.notes,
    };
    this.editingId.set(instrument.id);
    this.showForm.set(true);
  }

  cancelForm(): void {
    this.showForm.set(false);
    this.editingId.set(null);
    this.form = this.emptyForm();
  }

  save(): void {
    const hospitalId = this.auth.getHospitalId();
    if (!hospitalId) return;
    this.saving.set(true);

    const id = this.editingId();
    const op = id
      ? this.instrumentService.updateInstrument(id, this.form)
      : this.instrumentService.createInstrument(hospitalId, this.form);

    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(id ? 'LAB_INSTRUMENTS.UPDATED' : 'LAB_INSTRUMENTS.CREATED'),
        );
        this.cancelForm();
        this.loadInstruments();
        this.saving.set(false);
      },
      error: (err) => {
        console.error('Save instrument failed', err);
        this.toast.error(this.translate.instant('LAB_INSTRUMENTS.SAVE_ERROR'));
        this.saving.set(false);
      },
    });
  }

  deactivate(instrument: LabInstrumentResponse): void {
    if (!confirm(this.translate.instant('LAB_INSTRUMENTS.CONFIRM_DEACTIVATE'))) return;

    this.instrumentService.deactivateInstrument(instrument.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LAB_INSTRUMENTS.DEACTIVATED'));
        this.loadInstruments();
      },
      error: (err) => {
        console.error('Deactivate instrument failed', err);
        this.toast.error(this.translate.instant('LAB_INSTRUMENTS.DEACTIVATE_ERROR'));
      },
    });
  }

  private emptyForm(): LabInstrumentRequest {
    return {
      name: '',
      serialNumber: '',
      manufacturer: '',
      modelNumber: '',
      status: 'ACTIVE',
    };
  }
}
