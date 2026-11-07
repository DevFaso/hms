import {
  Component,
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
import { DepartmentLookupService, DepartmentOption } from '../services/department-lookup.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * Ward & bed administration (P0 #4). First writer for the V25 ward/bed
 * schema — makes the dashboard occupancy tiles real.
 */
@Component({
  selector: 'app-bed-management',
  standalone: true,
  imports: [FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './bed-management.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './bed-management.scss',
})
export class BedManagementComponent implements OnInit {
  private readonly bedService = inject(BedService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly departmentLookup = inject(DepartmentLookupService);
  private readonly roleContext = inject(RoleContextService);

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
            [w.code, w.name, w.departmentName].some((v) => v?.toLowerCase().includes(term))),
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
    this.loadWards();
  }

  loadWards(): void {
    const request = ++this.wardRequest;
    this.loading.set(true);
    this.loadError.set(false);
    this.bedService.getWards(this.showInactive()).subscribe({
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
    this.bedService.getBeds(wardId).subscribe({
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
    const hid =
      this.roleContext.effectiveHospitalIdForRequest() ?? this.roleContext.activeHospitalId;
    if (!hid) {
      this.departments.set([]);
      return;
    }
    this.departmentLookup.getActiveDepartments(hid).subscribe({
      next: (list) => this.departments.set(list),
      error: () => this.departments.set([]),
    });
  }

  openCreateWard(): void {
    this.loadDepartments();
    this.editingWardId.set(null);
    this.wardForm = this.emptyWardForm();
    this.wardModalOpen.set(true);
  }

  openEditWard(ward: WardResponse): void {
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
    if (!this.wardForm.name?.trim() || !this.wardForm.code?.trim() || !this.wardForm.wardType) {
      this.toast.error(this.translate.instant('BEDS.TOAST.WARD_REQUIRED'));
      return;
    }
    this.saving.set(true);
    const editingId = this.editingWardId();
    const request$ = editingId
      ? this.bedService.updateWard(editingId, this.wardForm)
      : this.bedService.createWard(this.wardForm);
    request$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BEDS.TOAST.WARD_SAVED'));
        this.saving.set(false);
        this.closeWardModal();
        this.refreshAll();
      },
      error: () => {
        this.toast.error(this.translate.instant('BEDS.TOAST.SAVE_FAILED'));
        this.saving.set(false);
      },
    });
  }

  deleteWard(ward: WardResponse): void {
    this.bedService.deleteWard(ward.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BEDS.TOAST.WARD_DELETED'));
        if (this.selectedWardId() === ward.id) {
          this.selectedWardId.set(null);
          this.beds.set([]);
        }
        this.loadWards();
      },
      // Backend rejects deleting a ward that still has beds — surface that.
      error: () => this.toast.error(this.translate.instant('BEDS.TOAST.WARD_DELETE_FAILED')),
    });
  }

  /* ── Bed CRUD ──────────────────────────────────────────── */

  openCreateBed(): void {
    if (!this.selectedWardId()) return;
    this.editingBedId.set(null);
    this.bedForm = this.emptyBedForm();
    this.bedModalOpen.set(true);
  }

  openEditBed(bed: BedResponse): void {
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
    const wardId = this.selectedWardId();
    if (!wardId) return;
    if (!this.bedForm.bedNumber?.trim()) {
      this.toast.error(this.translate.instant('BEDS.TOAST.BED_REQUIRED'));
      return;
    }
    this.saving.set(true);
    const editingId = this.editingBedId();
    const request$ = editingId
      ? this.bedService.updateBed(editingId, this.bedForm)
      : this.bedService.createBed(wardId, this.bedForm);
    request$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BEDS.TOAST.BED_SAVED'));
        this.saving.set(false);
        this.closeBedModal();
        this.refreshAll();
      },
      error: () => {
        this.toast.error(this.translate.instant('BEDS.TOAST.SAVE_FAILED'));
        this.saving.set(false);
      },
    });
  }

  setBedStatus(bed: BedResponse, status: string): void {
    if (!status || status === bed.status) return;
    this.bedService.updateBedStatus(bed.id, status as BedStatus).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BEDS.TOAST.STATUS_UPDATED'));
        this.refreshAll();
      },
      error: () => {
        this.toast.error(this.translate.instant('BEDS.TOAST.SAVE_FAILED'));
        this.refreshAll(); // revert the select to the server state
      },
    });
  }

  deleteBed(bed: BedResponse): void {
    this.bedService.deleteBed(bed.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BEDS.TOAST.BED_DELETED'));
        this.refreshAll();
      },
      error: () => this.toast.error(this.translate.instant('BEDS.TOAST.BED_DELETE_FAILED')),
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
