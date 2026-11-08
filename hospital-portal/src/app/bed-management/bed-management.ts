import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { HospitalTypeaheadComponent } from '../shared/hospital-typeahead/hospital-typeahead.component';
import {
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  BedService,
  WardResponse,
  WardRequest,
  BedResponse,
  BedRequest,
  BedStatus,
  WardType,
} from '../services/bed.service';
import { ToastService } from '../core/toast.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { MeasurePipe } from '../shared/pipes/measure.pipe';
import { DepartmentLookupService, DepartmentOption } from '../services/department-lookup.service';
import { RoleContextService } from '../core/role-context.service';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { refusalReason } from '../shared/refusal-reason';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
/**
 * Ward & bed administration (P0 #4). First writer for the V25 ward/bed
 * schema — makes the dashboard occupancy tiles real.
 */
@Component({
  selector: 'app-bed-management',
  standalone: true,
  imports: [
    HospitalTypeaheadComponent,
    SubmitGuardDirective,
    ModalDialogDirective,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    MeasurePipe,
  ],
  templateUrl: './bed-management.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './bed-management.scss',
})
export class BedManagementComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly bedService = inject(BedService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);
  private readonly departmentLookup = inject(DepartmentLookupService);
  private readonly roleContext = inject(RoleContextService);

  private readonly hospitalsService = inject(HospitalService);
  readonly isNetworkView = computed(
    () => this.roleContext.isSuperAdmin() && this.roleContext.globalView(),
  );
  readonly hospitals = signal<HospitalResponse[]>([]);
  readonly hospitalNamesFailed = signal(false);
  private readonly hospitalNames = computed(
    () => new Map(this.hospitals().map((h) => [h.id, h.name])),
  );
  readonly formHospitalId = signal<string | null>(null);
  readonly formHospitalName = signal('');
  private bedHospitalId: string | null = null;
  private bedWardId: string | null = null;
  private departmentRequest = 0;

  hospitalName(id: string): string {
    return this.hospitalNames().get(id) ?? id;
  }
  loadHospitalNames(): void {
    this.hospitalNamesFailed.set(false);
    this.hospitalsService
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (hospitals) => this.hospitals.set(hospitals),
        error: () => this.hospitalNamesFailed.set(true),
      });
  }
  chooseWardHospital(hospital: HospitalResponse): void {
    if (this.editingWardId()) return;
    this.formHospitalId.set(hospital.id);
    this.formHospitalName.set(hospital.name);
    this.wardForm.departmentId = null;
    this.loadDepartments();
  }
  clearWardHospital(): void {
    if (this.editingWardId()) return;
    this.formHospitalId.set(null);
    this.formHospitalName.set('');
    this.wardForm.departmentId = null;
    this.loadDepartments();
  }
  bedTargetHospitalName(): string {
    return this.bedHospitalId ? this.hospitalName(this.bedHospitalId) : '';
  }
  private scopeFor(hospitalId: string | null): [] | [string] {
    return this.isNetworkView() && hospitalId ? [hospitalId] : [];
  }
  private hasTarget(hospitalId: string | null): boolean {
    if (!this.isNetworkView() || hospitalId) return true;
    this.toast.error(this.translate.instant('BEDS_NETWORK.TARGET_REQUIRED'));
    return false;
  }

  /** QA D431: a ward belongs to a department; the form never offered one. */
  readonly departments = signal<DepartmentOption[]>([]);

  readonly wardTypes: WardType[] = [
    'GENERAL',
    'SURGICAL',
    'MATERNITY',
    'PEDIATRIC',
    'ICU',
    'CCU',
    'NICU',
    'PSYCHIATRIC',
    'ISOLATION',
    'PRIVATE',
    'SEMI_PRIVATE',
    'EMERGENCY',
    'RECOVERY',
  ];
  /** Manually settable states — OCCUPIED only ever comes from assignment. */
  readonly manualBedStatuses: BedStatus[] = [
    'AVAILABLE',
    'RESERVED',
    'MAINTENANCE',
    'OUT_OF_SERVICE',
  ];

  wards = signal<WardResponse[]>([]);
  beds = signal<BedResponse[]>([]);
  selectedWardId = signal<string | null>(null);
  showInactive = signal(false);
  loading = signal(false);
  bedsLoading = signal(false);
  saving = signal(false);

  selectedWard = computed(() => this.wards().find((w) => w.id === this.selectedWardId()) ?? null);
  readonly wardSearch = signal('');
  readonly wardTypeFilter = signal<WardType | ''>('');
  readonly wardSort = signal<'name' | 'available' | 'occupied'>('name');
  readonly bedSearch = signal('');
  readonly bedFilter = signal<BedStatus | ''>('');
  readonly loadError = signal(false);
  readonly bedsError = signal(false);
  private wardRequest = 0;
  private bedRequest = 0;
  readonly allBedStatuses: BedStatus[] = [
    'AVAILABLE',
    'OCCUPIED',
    'RESERVED',
    'MAINTENANCE',
    'OUT_OF_SERVICE',
  ];
  readonly totals = computed(() =>
    this.wards()
      .filter((w) => w.active)
      .reduce(
        (sum, w) => ({
          wards: sum.wards + 1,
          beds: sum.beds + w.totalBeds,
          available: sum.available + w.availableBeds,
          occupied: sum.occupied + w.occupiedBeds,
        }),
        { wards: 0, beds: 0, available: 0, occupied: 0 },
      ),
  );
  readonly occupancyRate = computed(() =>
    this.totals().beds ? Math.round((this.totals().occupied / this.totals().beds) * 1000) / 10 : 0,
  );
  readonly filteredWards = computed(() => {
    const term = this.wardSearch().toLowerCase().trim();
    return this.wards()
      .filter(
        (w) =>
          (!this.wardTypeFilter() || w.wardType === this.wardTypeFilter()) &&
          (!term ||
            [
              w.code,
              w.name,
              w.departmentName,
              this.isNetworkView() ? this.hospitalName(w.hospitalId) : null,
            ].some((v) => v?.toLowerCase().includes(term))),
      )
      .sort((a, b) =>
        this.wardSort() === 'available'
          ? b.availableBeds - a.availableBeds
          : this.wardSort() === 'occupied'
            ? b.occupiedBeds - a.occupiedBeds
            : a.name.localeCompare(b.name),
      );
  });
  readonly filteredBeds = computed(() => {
    const term = this.bedSearch().toLowerCase().trim();
    return this.beds().filter(
      (b) =>
        (!this.bedFilter() || b.status === this.bedFilter()) &&
        (!term ||
          [b.bedNumber, b.roomNumber, b.bedType, b.notes].some((v) =>
            v?.toLowerCase().includes(term),
          )),
    );
  });

  resetWardFilters(): void {
    this.wardSearch.set('');
    this.wardTypeFilter.set('');
    this.wardSort.set('name');
  }
  closeWard(): void {
    this.selectedWardId.set(null);
    this.beds.set([]);
    ++this.bedRequest;
    this.bedsLoading.set(false);
  }
  retryBeds(): void {
    const id = this.selectedWardId();
    if (id) this.loadBeds(id);
  }

  /* Ward modal */
  wardModalOpen = signal(false);
  editingWardId = signal<string | null>(null);
  wardForm: WardRequest = this.emptyWardForm();

  /* Bed modal */
  bedModalOpen = signal(false);
  editingBedId = signal<string | null>(null);
  bedForm: BedRequest = this.emptyBedForm();

  ngOnInit(): void {
    if (this.isNetworkView()) this.loadHospitalNames();
    this.loadWards();
  }

  loadWards(): void {
    const request = ++this.wardRequest;
    this.loading.set(true);
    this.loadError.set(false);
    this.bedService
      .getWards(this.showInactive())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (wards) => {
          if (request !== this.wardRequest) return;
          this.wards.set(wards);
          this.loading.set(false);
          const selected = this.selectedWardId();
          if (selected && !wards.some((w) => w.id === selected)) {
            this.selectedWardId.set(null);
            this.beds.set([]);
          }
        },
        error: () => {
          if (request !== this.wardRequest) return;
          this.loadError.set(true);
          this.wards.set([]);
          this.closeWard();
          this.toast.error(this.translate.instant('BEDS.TOAST.LOAD_FAILED'));
          this.loading.set(false);
        },
      });
  }

  toggleInactive(): void {
    this.showInactive.update((v) => !v);
    this.loadWards();
  }

  selectWard(ward: WardResponse): void {
    this.selectedWardId.set(ward.id);
    this.beds.set([]);
    this.bedSearch.set('');
    this.bedFilter.set('');
    this.loadBeds(ward.id);
  }

  private loadBeds(wardId: string): void {
    const request = ++this.bedRequest;
    this.bedsLoading.set(true);
    this.bedsError.set(false);
    this.bedService
      .getBeds(wardId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (beds) => {
          if (request !== this.bedRequest || wardId !== this.selectedWardId()) return;
          this.beds.set(beds);
          this.bedsLoading.set(false);
        },
        error: () => {
          if (request !== this.bedRequest || wardId !== this.selectedWardId()) return;
          this.beds.set([]);
          this.bedsError.set(true);
          this.toast.error(this.translate.instant('BEDS.TOAST.LOAD_FAILED'));
          this.bedsLoading.set(false);
        },
      });
  }

  private refreshAll(): void {
    this.loadWards();
    const wardId = this.selectedWardId();
    if (wardId) this.loadBeds(wardId);
  }

  /* ── Ward CRUD ─────────────────────────────────────────── */

  private loadDepartments(): void {
    const request = ++this.departmentRequest;
    const hid = this.isNetworkView()
      ? this.formHospitalId()
      : (this.roleContext.effectiveHospitalIdForRequest() ?? this.roleContext.activeHospitalId);
    this.departments.set([]);
    if (!hid) {
      this.departments.set([]);
      return;
    }
    this.departmentLookup
      .getActiveDepartments(hid)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          if (request === this.departmentRequest) this.departments.set(list);
        },
        error: () => {
          if (request === this.departmentRequest) this.departments.set([]);
        },
      });
  }

  openCreateWard(): void {
    this.formHospitalId.set(null);
    this.formHospitalName.set('');
    this.loadDepartments();
    this.editingWardId.set(null);
    this.wardForm = this.emptyWardForm();
    this.wardModalOpen.set(true);
  }

  openEditWard(ward: WardResponse): void {
    this.formHospitalId.set(ward.hospitalId);
    this.formHospitalName.set(this.hospitalName(ward.hospitalId));
    this.loadDepartments();
    this.editingWardId.set(ward.id);
    this.wardForm = {
      name: ward.name,
      code: ward.code,
      wardType: ward.wardType,
      floor: ward.floor,
      description: ward.description,
      departmentId: ward.departmentId,
      active: ward.active,
    };
    this.wardModalOpen.set(true);
  }

  closeWardModal(): void {
    this.wardModalOpen.set(false);
  }

  submitWard(): void {
    if (!this.hasTarget(this.formHospitalId())) return;
    if (!this.wardForm.name?.trim() || !this.wardForm.code?.trim() || !this.wardForm.wardType) {
      this.toast.error(this.translate.instant('BEDS.TOAST.WARD_REQUIRED'));
      return;
    }
    this.saving.set(true);
    const editingId = this.editingWardId();
    const request$ = editingId
      ? this.bedService.updateWard(
          editingId,
          this.wardForm,
          ...this.scopeFor(this.formHospitalId()),
        )
      : this.bedService.createWard(this.wardForm, ...this.scopeFor(this.formHospitalId()));
    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BEDS.TOAST.WARD_SAVED'));
        this.saving.set(false);
        this.closeWardModal();
        this.refreshAll();
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('BEDS.TOAST.SAVE_FAILED'));
        this.saving.set(false);
      },
    });
  }

  async deleteWard(ward: WardResponse): Promise<void> {
    const hospitalId = ward.hospitalId;
    if (!this.hasTarget(hospitalId)) return;
    // QA CX-010: one click removed the ward.
    const confirmed = await this.confirm.ask({
      titleKey: 'BEDS.DELETE_WARD_CONFIRM',
      params: {
        name: this.isNetworkView()
          ? `${ward.name} (${this.hospitalName(ward.hospitalId)})`
          : ward.name,
      },
      confirmKey: 'COMMON.DELETE',
    });
    if (!confirmed || this.destroyRef.destroyed) return;
    this.bedService
      .deleteWard(ward.id, ...this.scopeFor(hospitalId))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('BEDS.TOAST.WARD_DELETED'));
          if (this.selectedWardId() === ward.id) {
            this.selectedWardId.set(null);
            this.beds.set([]);
          }
          this.loadWards();
        },
        // Backend rejects deleting a ward that still has beds — surface that.
        error: (err: unknown) =>
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('BEDS.TOAST.WARD_DELETE_FAILED'),
          ),
      });
  }

  /* ── Bed CRUD ──────────────────────────────────────────── */

  openCreateBed(): void {
    this.bedHospitalId = this.selectedWard()?.hospitalId ?? null;
    this.bedWardId = this.selectedWardId();
    if (!this.selectedWardId()) return;
    this.editingBedId.set(null);
    this.bedForm = this.emptyBedForm();
    this.bedModalOpen.set(true);
  }

  openEditBed(bed: BedResponse): void {
    this.bedHospitalId = this.selectedWard()?.hospitalId ?? null;
    this.bedWardId = this.selectedWardId();
    this.editingBedId.set(bed.id);
    this.bedForm = {
      bedNumber: bed.bedNumber,
      bedType: bed.bedType,
      floor: bed.floor,
      roomNumber: bed.roomNumber,
      notes: bed.notes,
      active: bed.active,
    };
    this.bedModalOpen.set(true);
  }

  closeBedModal(): void {
    this.bedModalOpen.set(false);
  }

  submitBed(): void {
    if (!this.hasTarget(this.bedHospitalId)) return;
    const wardId = this.bedWardId ?? this.selectedWardId();
    if (!wardId) return;
    if (!this.bedForm.bedNumber?.trim()) {
      this.toast.error(this.translate.instant('BEDS.TOAST.BED_REQUIRED'));
      return;
    }
    this.saving.set(true);
    const editingId = this.editingBedId();
    const request$ = editingId
      ? this.bedService.updateBed(editingId, this.bedForm, ...this.scopeFor(this.bedHospitalId))
      : this.bedService.createBed(wardId, this.bedForm, ...this.scopeFor(this.bedHospitalId));
    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BEDS.TOAST.BED_SAVED'));
        this.saving.set(false);
        this.closeBedModal();
        this.refreshAll();
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('BEDS.TOAST.SAVE_FAILED'));
        this.saving.set(false);
      },
    });
  }

  setBedStatus(bed: BedResponse, status: string): void {
    const hospitalId = this.selectedWard()?.hospitalId ?? null;
    if (
      !this.hasTarget(hospitalId) ||
      (this.isNetworkView() && bed.wardId !== this.selectedWardId())
    )
      return;
    if (!status || status === bed.status) return;
    const request$ = this.isNetworkView()
      ? this.bedService.updateBedStatus(bed.id, status as BedStatus, undefined, hospitalId!)
      : this.bedService.updateBedStatus(bed.id, status as BedStatus);
    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BEDS.TOAST.STATUS_UPDATED'));
        this.refreshAll();
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('BEDS.TOAST.SAVE_FAILED'));
        this.refreshAll(); // revert the select to the server state
      },
    });
  }

  async deleteBed(bed: BedResponse): Promise<void> {
    const hospitalId = this.selectedWard()?.hospitalId ?? null;
    if (
      !this.hasTarget(hospitalId) ||
      (this.isNetworkView() && bed.wardId !== this.selectedWardId())
    )
      return;
    const confirmed = await this.confirm.ask({
      titleKey: 'BEDS.DELETE_BED_CONFIRM',
      params: {
        name: this.isNetworkView()
          ? `${bed.bedNumber} (${this.hospitalName(hospitalId!)})`
          : bed.bedNumber,
      },
      confirmKey: 'COMMON.DELETE',
    });
    if (!confirmed || this.destroyRef.destroyed) return;
    this.bedService
      .deleteBed(bed.id, ...this.scopeFor(hospitalId))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('BEDS.TOAST.BED_DELETED'));
          this.refreshAll();
        },
        error: (err: unknown) =>
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('BEDS.TOAST.BED_DELETE_FAILED'),
          ),
      });
  }

  bedStatusClass(status: string): string {
    return 'bed-status-' + status.toLowerCase();
  }

  private emptyWardForm(): WardRequest {
    return { name: '', code: '', wardType: 'GENERAL', floor: null, description: null };
  }

  private emptyBedForm(): BedRequest {
    return { bedNumber: '', bedType: null, floor: null, roomNumber: null, notes: null };
  }
}
