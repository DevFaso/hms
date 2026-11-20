import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HospitalResponse, HospitalService } from '../../services/hospital.service';
import {
  ImagingPacsService,
  PacsConnectionTest,
  PacsSettings,
} from '../../services/imaging-pacs.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { labelledRefusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { map, Observable } from 'rxjs';
import { ImagingReportingSettingsComponent } from '../reporting-settings/imaging-reporting-settings.component';

/** Bound to the form: strings throughout so ngModel never sees undefined. */
interface PacsForm {
  enabled: boolean;
  dicomwebBaseUrl: string;
  viewerUrlTemplate: string;
  authUsername: string;
  authPassword: string;
  clearPassword: boolean;
}

/** The refusal's field names → their labels (QA D808). */
const FIELD_LABELS: Readonly<Record<string, string>> = {
  enabled: 'IMAGING_PACS.SETTINGS.ENABLED',
  dicomwebBaseUrl: 'IMAGING_PACS.SETTINGS.BASE_URL',
  viewerUrlTemplate: 'IMAGING_PACS.SETTINGS.VIEWER_TEMPLATE',
  authUsername: 'IMAGING_PACS.SETTINGS.USERNAME',
  authPassword: 'IMAGING_PACS.SETTINGS.PASSWORD',
};

/** The refusal's field names → their controls' ids. */
const FIELD_IDS: Readonly<Record<string, string>> = {
  dicomwebBaseUrl: 'pacs-base-url',
  viewerUrlTemplate: 'pacs-viewer-template',
  authUsername: 'pacs-username',
  authPassword: 'pacs-password',
};
const ALL_FIELD_IDS = Object.values(FIELD_IDS);

/**
 * A hospital's PACS connection (V317): the DICOMweb address the in-app
 * viewer reads through, its credentials (the password is write-only: the
 * page only learns whether one is stored), the external viewer link, and a
 * connection test. A hospital admin manages their own hospital; the
 * super-admin picks one.
 */
@Component({
  selector: 'app-pacs-settings',
  standalone: true,
  imports: [FormsModule, TranslateModule, ImagingReportingSettingsComponent],
  templateUrl: './pacs-settings.component.html',
  styleUrl: './pacs-settings.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PacsSettingsComponent implements OnInit {
  private readonly pacs = inject(ImagingPacsService);
  private readonly hospitalService = inject(HospitalService);
  private readonly roles = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly isSuperAdmin = this.roles.isSuperAdmin;
  readonly hospitals = signal<HospitalResponse[]>([]);
  readonly hospitalId = signal<string>('');
  readonly settings = signal<PacsSettings | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly testing = signal(false);
  readonly testResult = signal<PacsConnectionTest | null>(null);
  readonly loadFailed = signal(false);

  form: PacsForm = emptyForm();

  ngOnInit(): void {
    const directory: Observable<HospitalResponse[]> = this.isSuperAdmin()
      ? this.hospitalService.list()
      : this.hospitalService.getMyHospitalAsResponse().pipe(map((h) => [h]));
    directory.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (list) => {
        this.hospitals.set(list);
        if (list.length === 1) this.selectHospital(list[0].id);
      },
      error: () => this.loadFailed.set(true),
    });
  }

  selectHospital(id: string): void {
    this.hospitalId.set(id);
    this.settings.set(null);
    this.testResult.set(null);
    this.form = emptyForm();
    if (!id) return;
    this.loading.set(true);
    this.loadFailed.set(false);
    this.pacs
      .settings(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (s) => {
          this.apply(s);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.loadFailed.set(true);
        },
      });
  }

  save(): void {
    const id = this.hospitalId();
    if (!id || this.saving()) return;
    // Switching the viewer on needs the address it reads through (the server
    // refuses it too: imaging.pacs.url.required).
    if (this.form.enabled && !this.form.dicomwebBaseUrl.trim()) {
      refuseFields(
        this.host.nativeElement,
        ['pacs-base-url'],
        ALL_FIELD_IDS,
        this.translate.instant('IMAGING_PACS.SETTINGS.BASE_URL_REQUIRED'),
      );
      return;
    }
    refuseFields(this.host.nativeElement, [], ALL_FIELD_IDS);
    this.saving.set(true);
    this.pacs
      .saveSettings(id, {
        enabled: this.form.enabled,
        dicomwebBaseUrl: this.form.dicomwebBaseUrl.trim() || null,
        viewerUrlTemplate: this.form.viewerUrlTemplate.trim() || null,
        authUsername: this.form.authUsername.trim() || null,
        authPassword: this.form.authPassword || null,
        clearPassword: this.form.clearPassword,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (s) => {
          this.apply(s);
          this.saving.set(false);
          this.toast.success(this.translate.instant('IMAGING_PACS.SETTINGS.SAVED'));
        },
        error: (err: unknown) => {
          this.saving.set(false);
          const refused = refusedFieldReasons(err, FIELD_IDS);
          if (refused.length > 0) {
            refuseFields(
              this.host.nativeElement,
              refused.map(([fieldId, message]) => ({ id: fieldId, message })),
              ALL_FIELD_IDS,
            );
          }
          this.toast.error(
            labelledRefusalReason(err, this.translate, FIELD_LABELS) ??
              this.translate.instant('IMAGING_PACS.SETTINGS.SAVE_FAILED'),
          );
        },
      });
  }

  test(): void {
    const id = this.hospitalId();
    if (!id || this.testing()) return;
    this.testing.set(true);
    this.testResult.set(null);
    this.pacs
      .testConnection(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (r) => {
          this.testResult.set(r);
          this.testing.set(false);
        },
        error: () => {
          this.testResult.set({ result: 'UNREACHABLE', source: null });
          this.testing.set(false);
        },
      });
  }

  private apply(s: PacsSettings): void {
    this.settings.set(s);
    this.form = {
      enabled: s.enabled,
      dicomwebBaseUrl: s.dicomwebBaseUrl ?? '',
      viewerUrlTemplate: s.viewerUrlTemplate ?? '',
      authUsername: s.authUsername ?? '',
      authPassword: '',
      clearPassword: false,
    };
  }
}

function emptyForm(): PacsForm {
  return {
    enabled: false,
    dicomwebBaseUrl: '',
    viewerUrlTemplate: '',
    authUsername: '',
    authPassword: '',
    clearPassword: false,
  };
}
