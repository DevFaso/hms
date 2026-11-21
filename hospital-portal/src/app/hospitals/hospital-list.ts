import { allRows } from '../shared/all-pages';
import {
  Component,
  ElementRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  HOSPITAL_CURRENCIES,
  HOSPITAL_TIME_ZONES,
  HospitalService,
  HospitalResponse,
  HospitalRequest,
} from '../services/hospital.service';
import { OrganizationService, OrganizationResponse } from '../services/organization.service';

import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { OrgTextPipe } from '../shared/pipes/org-text.pipe';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { PhoneFieldDirective } from '../shared/forms/phone-field.directive';
import { missingRequiredMessage } from '../shared/required-fields';
import { refuseFields } from '../shared/forms/invalid-fields';
import { refusedFieldReasons } from '../shared/refusal-reason';

type RequiredHospitalField = 'name' | 'phoneNumber' | 'country' | 'city' | 'address';

/**
 * QA r65 D2500: what the server accepts for a hospital code (HospitalLocaleRules):
 * 2 to 20 letters, digits and hyphens, starting and ending with a letter or a digit.
 */
export const HOSPITAL_CODE_PATTERN = /^[A-Z0-9][A-Z0-9-]{0,18}[A-Z0-9]$/;

/**
 * Every request property of the hospital form and the input it is typed in,
 * so a refusal the server pins on a field (`fieldErrors`: a taken code, a
 * code locked by a first invoice, a phone number, a zip code…) is marked on
 * that field and not only said in a toast.
 */
const FIELD_BY_PROPERTY: Readonly<Record<string, string>> = {
  name: 'hName',
  phoneNumber: 'hPhone',
  email: 'hEmail',
  website: 'hWebsite',
  country: 'hCountry',
  city: 'hCity',
  province: 'hProvince',
  region: 'hRegion',
  sector: 'hSector',
  address: 'hAddress',
  poBox: 'hPoBox',
  state: 'hState',
  zipCode: 'hZip',
  organizationId: 'hOrgId',
  code: 'hCode',
  currencyCode: 'hCurrency',
  timeZone: 'hTimeZone',
};
const FORM_FIELD_IDS: readonly string[] = Object.values(FIELD_BY_PROPERTY);

/** The form's required fields, in the order they appear, with their input ids. */
const REQUIRED_HOSPITAL_FIELDS: readonly {
  key: RequiredHospitalField;
  inputId: string;
  /** The label the form shows for the field (QA D1358). */
  labelKey: string;
}[] = [
  { key: 'name', inputId: 'hName', labelKey: 'DEPARTMENTS.NAME' },
  { key: 'phoneNumber', inputId: 'hPhone', labelKey: 'PATIENTS.PHONE' },
  { key: 'country', inputId: 'hCountry', labelKey: 'HOSPITALS.COUNTRY' },
  { key: 'city', inputId: 'hCity', labelKey: 'HOSPITALS.CITY' },
  { key: 'address', inputId: 'hAddress', labelKey: 'HOSPITALS.ADDRESS' },
];

@Component({
  selector: 'app-hospital-list',
  standalone: true,
  imports: [
    PhoneFieldDirective,
    SubmitGuardDirective,
    ModalDialogDirective,
    FormsModule,
    RouterLink,
    TranslateModule,
    OrgTextPipe,
  ],
  templateUrl: './hospital-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './hospital-list.scss',
})
export class HospitalListComponent implements OnInit {
  private readonly hospitalService = inject(HospitalService);
  private readonly orgService = inject(OrganizationService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /**
   * Copilot review fix — the lifecycle detail page is gated to
   * ROLE_SUPER_ADMIN, so the row-level "open detail" link is hidden
   * for other roles to avoid a click that would always 403.
   */
  readonly isSuperAdmin = this.roleContext.isSuperAdmin;

  hospitals = signal<HospitalResponse[]>([]);
  filtered = signal<HospitalResponse[]>([]);
  loading = signal(true);
  searchTerm = '';

  /** Organizations loaded for the dropdown */
  organizations = signal<OrganizationResponse[]>([]);

  // Create / Edit
  showModal = signal(false);
  editing = signal<HospitalResponse | null>(null);
  saving = signal(false);
  form: HospitalRequest = this.emptyForm();
  /** QA D817: set by a refused submit, so the empty required fields are marked. */
  showMissing = signal(false);

  /** QA r65 D2501: the currencies and time zones a hospital may be given. */
  readonly currencies = HOSPITAL_CURRENCIES;
  /** The offered zones, plus the one this hospital already has when it is not among them. */
  timeZones(): readonly string[] {
    const current = this.form.timeZone;
    return current && !HOSPITAL_TIME_ZONES.includes(current)
      ? [current, ...HOSPITAL_TIME_ZONES]
      : HOSPITAL_TIME_ZONES;
  }
  /**
   * A zone as the form lists it: its IANA id and its offset today
   * (« Africa/Ouagadougou (GMT) », « Africa/Nairobi (GMT+3) »), so two zones
   * that share a clock are told apart and one with another clock is seen at once.
   */
  /** The currency's name and its ISO 4217 code: « Franc CFA de l'UEMOA (F CFA) · XOF ». */
  currencyLabel(currency: string): string {
    const name = this.translate.instant('PLATFORM_SETUP.HOSPITAL.CURRENCY_NAME.' + currency);
    return `${name} · ${currency}`;
  }

  zoneLabel(zone: string): string {
    try {
      const offset = new Intl.DateTimeFormat('en-GB', {
        timeZone: zone,
        timeZoneName: 'shortOffset',
      })
        .formatToParts(new Date())
        .find((part) => part.type === 'timeZoneName')?.value;
      return offset ? `${zone} (${offset})` : zone;
    } catch {
      return zone;
    }
  }
  /** QA r65 D2500: set by a refused submit when the typed code is malformed. */
  codeInvalid = signal(false);
  /** QA D3059 (r68): the locks the server enforces, read when the edit form opens. */
  readonly codeLocked = signal(false);
  readonly currencyLocked = signal(false);

  // Delete
  showDeleteConfirm = signal(false);
  deletingHospital = signal<HospitalResponse | null>(null);
  deleting = signal(false);

  ngOnInit(): void {
    this.loadHospitals();
    // QA D1347: every active organization (all pages), not the first 100.
    allRows((page, size) => this.orgService.list(page, size, true)).subscribe({
      next: (list) => this.organizations.set(list),
    });
  }

  loadHospitals(): void {
    this.loading.set(true);
    this.hospitalService.list().subscribe({
      next: (data) => {
        this.hospitals.set(data);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('HOSPITALS.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.hospitals());
      return;
    }
    this.filtered.set(
      this.hospitals().filter(
        (h) =>
          h.name.toLowerCase().includes(term) ||
          (h.code?.toLowerCase().includes(term) ?? false) ||
          (h.city?.toLowerCase().includes(term) ?? false) ||
          (h.country?.toLowerCase().includes(term) ?? false) ||
          (h.phoneNumber?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  // ---------- Create ----------
  openCreate(): void {
    this.showMissing.set(false);
    this.codeInvalid.set(false);
    this.form = this.emptyForm();
    this.editing.set(null);
    this.showModal.set(true);
  }

  // ---------- Edit ----------
  openEdit(hospital: HospitalResponse): void {
    this.showMissing.set(false);
    this.editing.set(hospital);
    this.form = {
      name: hospital.name,
      address: hospital.address ?? '',
      city: hospital.city,
      state: hospital.state ?? '',
      zipCode: hospital.zipCode ?? '',
      country: hospital.country,
      province: hospital.province ?? '',
      region: hospital.region ?? '',
      sector: hospital.sector ?? '',
      poBox: hospital.poBox ?? '',
      phoneNumber: hospital.phoneNumber,
      email: hospital.email ?? '',
      website: hospital.website ?? '',
      organizationId: hospital.organizationId ?? '',
      active: hospital.active,
      code: hospital.code ?? '',
      currencyCode: hospital.currencyCode || 'XOF',
      timeZone: hospital.timeZone ?? '',
    };
    this.codeInvalid.set(false);
    this.codeLocked.set(false);
    this.currencyLocked.set(false);
    this.hospitalService.getById(hospital.id).subscribe({
      next: (fresh) => {
        if (this.editing()?.id !== hospital.id) return;
        this.codeLocked.set(fresh.codeLocked === true);
        this.currencyLocked.set(fresh.currencyLocked === true);
      },
      // Unknown: the fields stay open and the server still refuses a locked change.
      error: () => undefined,
    });
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    this.editing.set(null);
  }

  /** QA D734/D747: a value counts only when it has more than spaces. */
  hasText(value: string | null | undefined): boolean {
    return !!value?.trim();
  }

  /** QA D817: the required fields still empty, in the order the form shows them. */
  missingFields(): RequiredHospitalField[] {
    return REQUIRED_HOSPITAL_FIELDS.filter((f) => !this.hasText(this.form[f.key])).map(
      (f) => f.key,
    );
  }

  /** Whether to mark this field: only after a refused submit, and only while empty. */
  isMissing(field: RequiredHospitalField): boolean {
    return this.showMissing() && !this.hasText(this.form[field]);
  }

  submitForm(): void {
    // QA D734/D747: the street is required (a P.O. box no longer stands in
    // for it); say so here instead of after the round trip.
    // QA D817: the toast said a P.O. box could replace the street, and no
    // field was marked. It now names the street, and the empty fields are
    // highlighted with the first one focused.
    const missing = this.missingFields();
    if (missing.length > 0) {
      this.showMissing.set(true);
      // QA D1358: name the blank fields, not a fixed list.
      this.toast.error(
        missingRequiredMessage(
          this.translate,
          REQUIRED_HOSPITAL_FIELDS.map((f) => ({
            labelKey: f.labelKey,
            value: this.form[f.key]?.trim(),
          })),
        ) ?? this.translate.instant('COMMON.SUBMIT_BLOCKED_REQUIRED'),
      );
      // Each blank field is marked under itself, and the first one focused.
      refuseFields(
        this.host.nativeElement,
        REQUIRED_HOSPITAL_FIELDS.filter((f) => missing.includes(f.key)).map((f) => f.inputId),
        FORM_FIELD_IDS,
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      return;
    }
    this.showMissing.set(false);

    // QA r65 D2500: a typed code is checked here first (the server refuses the
    // same values); none typed means "generate one" on create, "keep it" on edit.
    const code = (this.form.code ?? '').trim().toUpperCase();
    if (code && !HOSPITAL_CODE_PATTERN.test(code)) {
      this.codeInvalid.set(true);
      // Marked under the field (and focused), not only said in a toast.
      const message = this.translate.instant('PLATFORM_SETUP.HOSPITAL.CODE_INVALID');
      refuseFields(this.host.nativeElement, ['hCode'], FORM_FIELD_IDS, message);
      this.toast.error(message);
      return;
    }
    this.codeInvalid.set(false);
    refuseFields(this.host.nativeElement, [], FORM_FIELD_IDS);
    this.saving.set(true);

    // Remove empty optional strings before sending
    const payload: HospitalRequest = { ...this.form };
    if (!payload.organizationId) delete payload.organizationId;
    if (code) payload.code = code;
    else delete payload.code;
    // An empty time zone is sent as such: it means "the organisation's".
    payload.timeZone = payload.timeZone ?? '';

    const existing = this.editing();
    const request$ = existing
      ? this.hospitalService.update(existing.id, payload)
      : this.hospitalService.create(payload);

    request$.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(existing ? 'HOSPITALS.UPDATED' : 'HOSPITALS.CREATED'),
        );
        this.closeModal();
        this.saving.set(false);
        this.loadHospitals();
      },
      error: (err) => {
        const body = err?.error;
        let msg = this.translate.instant(
          existing ? 'HOSPITALS.UPDATE_FAILED' : 'HOSPITALS.CREATE_FAILED',
        );
        if (body?.fieldErrors) {
          msg = Object.values(body.fieldErrors).join('. ');
        } else if (body?.message) {
          msg = body.message;
        }
        // QA r65 D2500 / D2501: a refused code, currency or time zone (taken,
        // locked by a first invoice, unsupported) is marked on its own field.
        const refused = refusedFieldReasons(err, FIELD_BY_PROPERTY);
        refuseFields(
          this.host.nativeElement,
          refused.map(([id, message]) => ({ id, message })),
          FORM_FIELD_IDS,
        );
        this.codeInvalid.set(refused.some(([id]) => id === 'hCode'));
        this.toast.error(msg);
        this.saving.set(false);
      },
    });
  }

  // ---------- Delete ----------
  confirmDelete(hospital: HospitalResponse): void {
    this.deletingHospital.set(hospital);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingHospital.set(null);
  }

  executeDelete(): void {
    const hospital = this.deletingHospital();
    if (!hospital) return;
    this.deleting.set(true);
    this.hospitalService.delete(hospital.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('HOSPITALS.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadHospitals();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('HOSPITALS.DELETE_FAILED'));
        this.deleting.set(false);
      },
    });
  }

  // ---------- Helpers ----------
  private emptyForm(): HospitalRequest {
    return {
      name: '',
      address: '',
      city: '',
      state: '',
      zipCode: '',
      country: '',
      province: '',
      region: '',
      sector: '',
      poBox: '',
      phoneNumber: '',
      email: '',
      website: '',
      organizationId: '',
      active: true,
      code: '',
      currencyCode: 'XOF',
      timeZone: '',
    };
  }
}
