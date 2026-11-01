import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DatePipe } from '@angular/common';
import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

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
import { labelledRefusalReason, refusalReason } from '../../shared/refusal-reason';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';

/**
 * QA D1193 (round 22): a field the server refuses is named with its label, not
 * shown as the bare constraint ("ne doit pas être vide").
 */
const SAVE_FIELD_LABELS: Readonly<Record<string, string>> = {
  name: 'LAB_INSTRUMENTS.NAME',
  serialNumber: 'LAB_INSTRUMENTS.SERIAL',
  manufacturer: 'LAB_INSTRUMENTS.MANUFACTURER',
  modelNumber: 'LAB_INSTRUMENTS.MODEL',
  notes: 'LAB_INSTRUMENTS.NOTES',
};

@Component({
  selector: 'app-lab-instruments',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    ModalDialogDirective,
    DatePipe,
    ServerTimePipe,
    RouterModule,
    FormsModule,
    TranslateModule,
  ],
  templateUrl: './lab-instruments.html',
  changeDetection: ChangeDetectionStrategy.Eager,
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
        // QA D879: the server's reason (a duplicate serial number, a field
        // limit) is what the user needs; the generic text is the fallback.
        this.toast.error(
          labelledRefusalReason(err, this.translate, SAVE_FIELD_LABELS) ??
            this.translate.instant('LAB_INSTRUMENTS.SAVE_ERROR'),
        );
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
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('LAB_INSTRUMENTS.DEACTIVATE_ERROR'),
        );
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
