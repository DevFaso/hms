import { refusalReason } from '../shared/refusal-reason';
import {
  Component,
  computed,
  ElementRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
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
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { blankFields, focusFirstInvalid, refuseFields } from '../shared/forms/invalid-fields';
import { DashPipe } from '../shared/pipes/dash.pipe';
import { missingRequiredMessage } from '../shared/required-fields';
import { SensitivitySelectComponent } from '../shared/sensitivity/sensitivity-select';
import { allRows } from '../shared/all-pages';
import { MedicationCatalogApprovalsComponent } from './medication-catalog-approvals';
import { ReasonDialogComponent } from '../shared/reason-dialog/reason-dialog.component';
import {
  SensitivityCategory,
  sensitivityLabelKey,
} from '../shared/sensitivity/sensitivity-category';

/** The required fields of the medication form (QA D1306). */
const MED_REQUIRED_IDS = ['nameFr', 'genericName'];

/**
 * Go-live D2 (V385): the fields of an entry that prescribing and dispensing read. A change to one
 * of them is not saved on the entry: it is sent for approval, with its reason.
 */
const APPROVED_FIELDS = [
  'nameFr',
  'genericName',
  'brandName',
  'atcCode',
  'form',
  'strength',
  'strengthUnit',
  'rxnormCode',
  'route',
] as const;

@Component({
  selector: 'app-medication-catalog',
  standalone: true,
  imports: [
    MedicationCatalogApprovalsComponent,
    ReasonDialogComponent,
    SensitivitySelectComponent,
    DashPipe,
    SubmitGuardDirective,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    ModalDialogDirective,
  ],
  templateUrl: './medication-catalog.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './medication-catalog.scss',
})
export class MedicationCatalogComponent implements OnInit {
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
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
   * r66 D2901: a platform entry (the national list, no hospital of its own) is
   * shared by every hospital, so only the platform administrator edits or
   * deactivates it (MedicationCatalogItemServiceImpl.requireWritable). The
   * hospital admin edits the hospital's own entries; a platform row shows no
   * button the API would refuse.
   */
  private readonly isPlatformAdmin = computed(() =>
    this.roleContext.hasAnyActiveRoleExactly(['ROLE_SUPER_ADMIN']),
  );

  canManageItem(item: MedicationCatalogItemResponse): boolean {
    return this.canManage() && (!!item.hospitalId || this.isPlatformAdmin());
  }

  /**
   * Round 18 (QA D935): the paediatric dose limits are the pharmacy's, as in a
   * real formulary: the pharmacist (and the hospital admin) sets and approves
   * them in their own dialog, audited server-side.
   */
  readonly canEditDosing = computed(() =>
    this.roleContext.hasAnyActiveRoleExactly(['ROLE_PHARMACIST', 'ROLE_HOSPITAL_ADMIN']),
  );

  /**
   * Go-live D2 (V385): who reads the approval queue and an entry's history, mirroring
   * MedicationCatalogController (those who propose and those who decide). Which of them may
   * approve WHICH version is the server's answer on each version, never guessed here. With the doctor
   * equivalence, as the route guard and the server read it: a médecin-chef who holds ROLE_PHYSICIAN or
   * ROLE_SURGEON decides dose limits, so the panel is theirs too (review of #1360).
   */
  readonly canSeeApprovals = computed(() =>
    this.roleContext.hasAnyActiveRole([
      'ROLE_PHARMACIST',
      'ROLE_HOSPITAL_ADMIN',
      'ROLE_DOCTOR',
      'ROLE_SUPER_ADMIN',
    ]),
  );

  /** The approval panel: closed until asked for, with the number of versions waiting. */
  readonly approvalsOpen = signal(false);
  readonly pendingApprovals = signal<number | null>(null);

  toggleApprovals(): void {
    this.approvalsOpen.update((open) => !open);
  }

  /** How many versions await approval at this hospital (the panel's label). */
  refreshPendingApprovals(): void {
    if (!this.canSeeApprovals()) return;
    // Every page of the queue, counted: never one page taken for the whole (QA D1347).
    allRows((page, size) => this.svc.listCatalogVersions('PENDING_APPROVAL', page, size)).subscribe(
      {
        next: (waiting) => this.pendingApprovals.set(waiting.length),
        error: () => this.pendingApprovals.set(null),
      },
    );
  }

  /** A version was approved, refused or withdrawn: the entries and the count may have moved. */
  onVersionDecided(): void {
    this.loadItems();
    this.refreshPendingApprovals();
  }

  // The history of one entry (go-live D2)
  historyItem = signal<MedicationCatalogItemResponse | null>(null);
  submittingAsIs = signal(false);
  /** The shared reason dialog is open to submit the entry as it stands. */
  askingAsIsReason = signal(false);
  /** Bumped to make the history dialog read its versions again. */
  historyRead = signal(0);

  openHistory(item: MedicationCatalogItemResponse): void {
    this.askingAsIsReason.set(false);
    this.historyItem.set(item);
  }

  closeHistory(): void {
    this.askingAsIsReason.set(false);
    this.historyItem.set(null);
  }

  /**
   * An entry nobody approved yet (it predates the approval circuit) may be submitted as it
   * stands by whoever manages it, unless a version is already waiting.
   */
  canSubmitAsIs(item: MedicationCatalogItemResponse): boolean {
    return (
      this.canManageItem(item) &&
      item.formularyApprovedVersion == null &&
      !item.pendingFormularyVersionId
    );
  }

  /** Submits the entry as it stands, with the reason the shared dialog asked for (never blank). */
  submitAsIs(reason: string): void {
    const item = this.historyItem();
    if (!item) return;
    this.submittingAsIs.set(true);
    this.svc.requestCatalogApproval(item.id, reason).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('PHARMACY.CATALOG_APPROVAL.SUBMITTED_TOAST'));
        this.submittingAsIs.set(false);
        this.askingAsIsReason.set(false);
        this.historyItem.set({ ...item, ...updated });
        this.historyRead.update((n) => n + 1);
        this.onVersionDecided();
      },
      error: (err) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PHARMACY.CATALOG_APPROVAL.ACTION_FAILED'),
        );
        this.submittingAsIs.set(false);
      },
    });
  }

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
  loadFailed = signal(false);
  private readVersion = 0;
  pageCount(flag: 'essentialList' | 'controlled'): number {
    return this.filtered().filter((item) => item[flag]).length;
  }
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
    this.refreshPendingApprovals();
  }

  loadItems(): void {
    const version = ++this.readVersion;
    this.loadFailed.set(false);
    this.loading.set(true);
    const obs = this.searchTerm.trim()
      ? this.svc.searchMedications(this.searchTerm.trim(), this.currentPage, this.pageSize)
      : this.svc.listMedications(this.currentPage, this.pageSize);

    obs.subscribe({
      next: (page) => {
        if (version !== this.readVersion) return;
        const list = page?.content ?? [];
        this.items.set(list);
        this.filtered.set(list);
        this.totalElements = page?.totalElements ?? 0;
        this.totalPages = page?.totalPages ?? 0;
        this.loading.set(false);
      },
      error: () => {
        if (version !== this.readVersion) return;
        this.loadFailed.set(true);
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

  /**
   * Policy 2026-11-20 (provisional): the sensitive category of the medicine in
   * the editor, and the one it had when the editor opened. A prescription of
   * a tagged medicine is hidden from a family proxy, whatever visit wrote it.
   */
  medSensitivity = signal<SensitivityCategory | null>(null);
  private medSensitivityBefore: SensitivityCategory | null = null;
  readonly sensitivityLabelKey = sensitivityLabelKey;

  /** The form, plus the category when it changed: left out, the server keeps the one it has. */
  private withSensitivity(form: MedicationCatalogItemRequest): MedicationCatalogItemRequest {
    const chosen = this.medSensitivity();
    if (chosen === this.medSensitivityBefore) return form;
    return chosen
      ? { ...form, sensitivityCategory: chosen }
      : { ...form, clearSensitivityCategory: true };
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.medSensitivityBefore = null;
    this.medSensitivity.set(null);
    this.editing.set(null);
    this.showModal.set(true);
  }

  openEdit(item: MedicationCatalogItemResponse): void {
    this.editing.set(item);
    this.medSensitivityBefore = item.sensitivityCategory ?? null;
    this.medSensitivity.set(this.medSensitivityBefore);
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
      changeReason: '',
    };
    this.showModal.set(true);
  }

  /**
   * Go-live D2 (V385): whether the editor changes what prescribing and dispensing read. A new
   * entry always does; an edit does when a name, the ATC or RxNorm code, the form, the strength,
   * the route or the controlled flag differs from the entry in force.
   */
  needsApproval(): boolean {
    const existing = this.editing();
    if (!existing) return true;
    const text = (value: unknown) => (typeof value === 'string' ? value.trim() : '');
    return (
      APPROVED_FIELDS.some((field) => text(this.form[field]) !== text(existing[field])) ||
      (this.form.controlled ?? false) !== (existing.controlled ?? false)
    );
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
    // QA D1306 (round 28): a refused field is marked and focused, not only a toast.
    const root = this.host.nativeElement;
    const blank = blankFields(
      { nameFr: this.form.nameFr, genericName: this.form.genericName },
      MED_REQUIRED_IDS,
    );
    if (blank.length > 0) {
      // QA D1358: name the blank fields, not a fixed list.
      const message =
        missingRequiredMessage(this.translate, [
          { labelKey: 'PHARMACY.NAME_FR', value: this.form.nameFr },
          { labelKey: 'PHARMACY.GENERIC_NAME', value: this.form.genericName },
        ]) ?? this.translate.instant('COMMON.SUBMIT_BLOCKED_REQUIRED');
      refuseFields(root, blank, MED_REQUIRED_IDS, this.translate.instant('COMMON.FIELD_REQUIRED'));
      this.toast.error(message);
      return;
    }
    refuseFields(root, [], MED_REQUIRED_IDS, '');
    // The code fields bind their own aria-invalid and hint: focus the field.
    if (!this.isAtcValid()) {
      focusFirstInvalid(root, ['atcCode']);
      this.toast.error(this.translate.instant('PHARMACY.ATC_FORMAT_HINT'));
      return;
    }
    if (!this.isRxNormValid()) {
      focusFirstInvalid(root, ['rxnormCode']);
      this.toast.error(this.translate.instant('PHARMACY.RXNORM_FORMAT_HINT'));
      return;
    }
    const proposes = this.needsApproval();
    if (proposes && !(this.form.changeReason ?? '').trim()) {
      focusFirstInvalid(root, ['changeReason']);
      this.toast.error(this.translate.instant('PHARMACY.CATALOG_APPROVAL.CHANGE_REASON_REQUIRED'));
      return;
    }
    this.saving.set(true);
    const existing = this.editing();
    const body = this.withSensitivity(this.form);
    // An edit answers the entry; a create answers the version awaiting approval. Neither is read here.
    const req$: Observable<unknown> = existing
      ? this.svc.updateMedication(existing.id, body)
      : this.svc.createMedication(body);

    req$.subscribe({
      next: () => {
        // Go-live D2 (V385): what was proposed is not in force yet, and the toast says so.
        let saved = 'PHARMACY.MED_UPDATED';
        if (proposes) {
          saved = existing
            ? 'PHARMACY.CATALOG_APPROVAL.PROPOSED_TOAST_UPDATE'
            : 'PHARMACY.CATALOG_APPROVAL.PROPOSED_TOAST_CREATE';
        }
        this.toast.success(this.translate.instant(saved));
        this.closeModal();
        this.saving.set(false);
        this.loadItems();
        this.refreshPendingApprovals();
      },
      error: (err: unknown) => {
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
      changeReason: '',
    };
  }

  /**
   * Go-live D2 (V385): what the row says of an entry's paediatric limits. A default nobody
   * approved here; limits one person set before the approval circuit (provisional, they judge a
   * dose as before); or the version a second person approved.
   */
  doseBadgeKey(item: MedicationCatalogItemResponse): string {
    if (item.pediatricDoseStatus !== 'APPROVED') return 'PHARMACY.DOSING.BADGE_PENDING';
    return item.doseLimitApprovedVersion
      ? 'PHARMACY.CATALOG_APPROVAL.DOSE_BADGE_APPROVED'
      : 'PHARMACY.CATALOG_APPROVAL.DOSE_BADGE_PROVISIONAL';
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
      changeReason: '',
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
    if (!this.dosingForm.changeReason.trim()) {
      focusFirstInvalid(this.host.nativeElement, ['dose-reason']);
      this.toast.error(this.translate.instant('PHARMACY.CATALOG_APPROVAL.CHANGE_REASON_REQUIRED'));
      return;
    }
    this.dosingSaving.set(true);
    this.svc.updatePediatricDosing(item.id, this.dosingForm).subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant('PHARMACY.CATALOG_APPROVAL.PROPOSED_TOAST_DOSING'),
        );
        this.dosingSaving.set(false);
        this.closeDosing();
        this.loadItems();
        this.refreshPendingApprovals();
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
      changeReason: '',
    };
  }
}
