import { refusalReason } from '../shared/refusal-reason';
import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import {
  PharmacyService,
  MedicationCatalogItemRequest,
  MedicationCatalogItemResponse,
  PediatricDosingRequest,
  PediatricWeightBand,
} from '../services/pharmacy.service';

@Component({
  selector: 'app-medication-catalog',
  standalone: true,
  imports: [FormsModule, TranslateModule, EnumLabelPipe, ModalDialogDirective],
  templateUrl: './medication-catalog.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './medication-catalog.scss',
})
export class MedicationCatalogComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);

  /**
   * QA D50: creating, editing and deleting a catalog entry is the hospital
   * admin's and the super-admin's (MedicationCatalogController POST/PUT/
   * DELETE). Everyone else who may read the catalog (the pharmacist among
   * them) sees it read-only instead of a button the API answers with 403.
   */
  readonly canManage = computed(() =>
    this.roleContext.hasAnyActiveRoleExactly(['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN']),
  );

  /**
   * Round 18 (QA D935): the paediatric dose limits are the pharmacy's, as in a
   * real formulary: the pharmacist (and the hospital admin) sets and approves
   * them in their own dialog, audited server-side.
   */
  readonly canEditDosing = computed(() =>
    this.roleContext.hasAnyActiveRoleExactly(['ROLE_PHARMACIST', 'ROLE_HOSPITAL_ADMIN']),
  );

  /** The dosage forms the editor offers, each with a PHARMACY.FORM_OPTION key. */
  static readonly DOSAGE_FORMS: readonly string[] = [
    'TABLET',
    'CAPSULE',
    'SYRUP',
    'INJECTION',
    'CREAM',
    'OINTMENT',
    'DROPS',
    'SUSPENSION',
    'POWDER',
    'INHALER',
    'SUPPOSITORY',
  ];

  /**
   * The catalog table's form column in the reader's language (QA D434): a
   * known dosage form through its translation key, anything older typed as
   * free text as it was stored, and a dash when there is none.
   */
  formLabel(form: string | null | undefined): string {
    if (!form) return '—';
    return MedicationCatalogComponent.DOSAGE_FORMS.includes(form)
      ? this.translate.instant(`PHARMACY.FORM_OPTION.${form}`)
      : form;
  }

  items = signal<MedicationCatalogItemResponse[]>([]);
  filtered = signal<MedicationCatalogItemResponse[]>([]);
  loading = signal(true);
  searchTerm = '';

  // Pagination
  currentPage = 0;
  pageSize = 20;
  totalElements = 0;
  totalPages = 0;

  // Modal
  showModal = signal(false);
  editing = signal<MedicationCatalogItemResponse | null>(null);
  saving = signal(false);
  form: MedicationCatalogItemRequest = this.emptyForm();

  // Paediatric dosing (round 18, QA D935)
  dosingItem = signal<MedicationCatalogItemResponse | null>(null);
  dosingSaving = signal(false);
  dosingForm: PediatricDosingRequest = this.emptyDosing();

  // Delete
  showDeleteConfirm = signal(false);
  deletingItem = signal<MedicationCatalogItemResponse | null>(null);
  deleting = signal(false);

  ngOnInit(): void {
    this.loadItems();
  }

  loadItems(): void {
    this.loading.set(true);
    const obs = this.searchTerm.trim()
      ? this.svc.searchMedications(this.searchTerm.trim(), this.currentPage, this.pageSize)
      : this.svc.listMedications(this.currentPage, this.pageSize);

    obs.subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.items.set(list);
        this.filtered.set(list);
        this.totalElements = page?.totalElements ?? 0;
        this.totalPages = page?.totalPages ?? 0;
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PHARMACY.CATALOG_LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  onSearch(): void {
    this.currentPage = 0;
    this.loadItems();
  }

  prevPage(): void {
    if (this.currentPage > 0) {
      this.currentPage--;
      this.loadItems();
    }
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages - 1) {
      this.currentPage++;
      this.loadItems();
    }
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(null);
    this.showModal.set(true);
  }

  openEdit(item: MedicationCatalogItemResponse): void {
    this.editing.set(item);
    this.form = {
      nameFr: item.nameFr,
      genericName: item.genericName,
      brandName: item.brandName ?? '',
      atcCode: item.atcCode ?? '',
      form: item.form ?? '',
      strength: item.strength ?? '',
      strengthUnit: item.strengthUnit ?? '',
      rxnormCode: item.rxnormCode ?? '',
      route: item.route ?? '',
      category: item.category ?? '',
      essentialList: item.essentialList ?? false,
      controlled: item.controlled ?? false,
      description: item.description ?? '',
    };
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    this.editing.set(null);
  }

  /**
   * WHO ATC at level 3, 4 or 5: J01 (therapeutic group), J01C (pharmacological
   * subgroup), J01CA (chemical subgroup) or J01CA04 (substance). A 5-character
   * chemical subgroup is a valid ATC code and used to be rejected (D129).
   */
  static readonly ATC_PATTERN = /^[A-Z]\d{2}(?:[A-Z](?:[A-Z](?:\d{2})?)?)?$/;

  /** RxNorm RxCUI: 1–12 digits. */
  static readonly RXNORM_PATTERN = /^\d{1,12}$/;

  isAtcValid(): boolean {
    const value = (this.form.atcCode ?? '').trim().toUpperCase();
    if (value.length === 0) return true;
    return MedicationCatalogComponent.ATC_PATTERN.test(value);
  }

  isRxNormValid(): boolean {
    const value = (this.form.rxnormCode ?? '').trim();
    if (value.length === 0) return true;
    return MedicationCatalogComponent.RXNORM_PATTERN.test(value);
  }

  submitForm(): void {
    if (!this.form.nameFr || !this.form.genericName) {
      this.toast.error(this.translate.instant('PHARMACY.MED_REQUIRED_FIELDS'));
      return;
    }
    if (!this.isAtcValid()) {
      this.toast.error(this.translate.instant('PHARMACY.ATC_FORMAT_HINT'));
      return;
    }
    if (!this.isRxNormValid()) {
      this.toast.error(this.translate.instant('PHARMACY.RXNORM_FORMAT_HINT'));
      return;
    }
    this.saving.set(true);
    const existing = this.editing();
    const req$ = existing
      ? this.svc.updateMedication(existing.id, this.form)
      : this.svc.createMedication(this.form);

    req$.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(existing ? 'PHARMACY.MED_UPDATED' : 'PHARMACY.MED_CREATED'),
        );
        this.closeModal();
        this.saving.set(false);
        this.loadItems();
      },
      error: (err) => {
        const msg =
          refusalReason(err) ??
          this.translate.instant(
            existing ? 'PHARMACY.MED_UPDATE_FAILED' : 'PHARMACY.MED_CREATE_FAILED',
          );
        this.toast.error(msg);
        this.saving.set(false);
      },
    });
  }

  confirmDelete(item: MedicationCatalogItemResponse): void {
    this.deletingItem.set(item);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingItem.set(null);
  }

  executeDelete(): void {
    const item = this.deletingItem();
    if (!item) return;
    this.deleting.set(true);
    this.svc.deleteMedication(item.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.MED_DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadItems();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY.MED_DELETE_FAILED'),
        );
        this.deleting.set(false);
      },
    });
  }

  private emptyForm(): MedicationCatalogItemRequest {
    return {
      nameFr: '',
      genericName: '',
      brandName: '',
      atcCode: '',
      form: '',
      strength: '',
      strengthUnit: '',
      rxnormCode: '',
      route: '',
      category: '',
      essentialList: false,
      controlled: false,
      description: '',
    };
  }

  /** Whether an item has any paediatric limit on file. */
  hasPediatricLimits(item: MedicationCatalogItemResponse): boolean {
    return (
      item.pediatricMaxDoseMgPerKg != null ||
      item.pediatricMaxDailyMgPerKg != null ||
      item.pediatricMaxSingleDoseMg != null ||
      item.pediatricMaxDailyMg != null ||
      (item.pediatricWeightBands?.length ?? 0) > 0
    );
  }

  openDosing(item: MedicationCatalogItemResponse): void {
    this.dosingForm = {
      pediatricMaxDoseMgPerKg: item.pediatricMaxDoseMgPerKg ?? null,
      pediatricMaxDailyMgPerKg: item.pediatricMaxDailyMgPerKg ?? null,
      pediatricMaxSingleDoseMg: item.pediatricMaxSingleDoseMg ?? null,
      pediatricMaxDailyMg: item.pediatricMaxDailyMg ?? null,
      pediatricManualReviewUnderMonths: item.pediatricManualReviewUnderMonths ?? null,
      pediatricWeightBands: (item.pediatricWeightBands ?? []).map((b) => ({ ...b })),
      pediatricDoseSource: item.pediatricDoseSource ?? null,
      pediatricDoseStatus: item.pediatricDoseStatus ?? 'DEFAULT_PENDING_APPROVAL',
    };
    this.dosingItem.set(item);
  }

  closeDosing(): void {
    this.dosingItem.set(null);
  }

  addBand(): void {
    this.dosingForm.pediatricWeightBands = [
      ...this.dosingForm.pediatricWeightBands,
      {
        minWeightKg: null,
        maxWeightKg: null,
        dosePerAdministration: null,
        doseUnit: '',
        maxDosesPerDay: null,
      } as PediatricWeightBand,
    ];
  }

  removeBand(index: number): void {
    this.dosingForm.pediatricWeightBands = this.dosingForm.pediatricWeightBands.filter(
      (_, i) => i !== index,
    );
  }

  saveDosing(): void {
    const item = this.dosingItem();
    if (!item) return;
    this.dosingSaving.set(true);
    this.svc.updatePediatricDosing(item.id, this.dosingForm).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.DOSING.SAVED'));
        this.dosingSaving.set(false);
        this.closeDosing();
        this.loadItems();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY.DOSING.SAVE_FAILED'),
        );
        this.dosingSaving.set(false);
      },
    });
  }

  private emptyDosing(): PediatricDosingRequest {
    return {
      pediatricMaxDoseMgPerKg: null,
      pediatricMaxDailyMgPerKg: null,
      pediatricMaxSingleDoseMg: null,
      pediatricMaxDailyMg: null,
      pediatricManualReviewUnderMonths: null,
      pediatricWeightBands: [],
      pediatricDoseSource: null,
      pediatricDoseStatus: 'DEFAULT_PENDING_APPROVAL',
    };
  }
}
