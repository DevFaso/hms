import {
  Component,
  inject,
  OnDestroy,
  OnInit,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subject, Subscription, debounceTime, takeUntil } from 'rxjs';
import { PatientService, PatientResponse } from '../services/patient.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule } from '@ngx-translate/core';

import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
type SortField = 'name' | 'mrn' | 'gender' | 'status' | 'createdAt';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-patient-list',
  standalone: true,
  imports: [CommonModule, RouterLink, FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './patient-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './patient-list.scss',
})
export class PatientListComponent implements OnInit, OnDestroy {
  private readonly patientService = inject(PatientService);
  private readonly hospitalService = inject(HospitalService);
  private readonly permissions = inject(PermissionService);
  private readonly roleContext = inject(RoleContextService);
  private readonly destroy$ = new Subject<void>();
  private readonly searchInput$ = new Subject<string>();

  patients = signal<PatientResponse[]>([]);
  filtered = signal<PatientResponse[]>([]);
  loading = signal(true);
  canCreate = this.permissions.hasPermission('Register Patients');

  /* Filters */
  searchTerm = '';
  genderFilter = '';
  statusFilter = '';
  bloodTypeFilter = '';
  private readonly selectedHospital = signal('');
  get hospitalFilter(): string {
    return this.selectedHospital();
  }
  set hospitalFilter(value: string) {
    this.selectedHospital.set(value);
  }
  showFilters = signal(true);
  contactFilter = '';
  readonly loadError = signal(false);
  private listRequest = 0;
  private listSubscription?: Subscription;

  get scopedPatients(): PatientResponse[] {
    return this.patients().filter(
      (p) => !this.hospitalFilter || p.hospitalId === this.hospitalFilter,
    );
  }

  private readonly metrics = computed(() => {
    const rows = this.scopedPatients;
    return [
      {
        id: 'all',
        label: 'PATIENTS.LOADED_RECORDS',
        icon: 'people',
        count: rows.length,
        tone: 'total',
      },
      {
        id: 'active',
        label: 'PATIENTS.ACTIVE_REGISTRATIONS',
        icon: 'how_to_reg',
        count: rows.filter((p) => p.active).length,
        tone: 'active',
      },
      {
        id: 'inactive',
        label: 'PATIENTS.INACTIVE_REGISTRATIONS',
        icon: 'person_off',
        count: rows.filter((p) => !p.active).length,
        tone: 'inactive',
      },
      {
        id: 'no-contact',
        label: 'PATIENTS.NO_CONTACT',
        icon: 'contact_phone',
        count: rows.filter((p) => !this.hasContact(p)).length,
        tone: 'contact',
      },
    ];
  });

  get directoryMetrics() {
    return this.metrics();
  }

  hasContact(p: PatientResponse): boolean {
    return !!(this.displayPhone(p) || this.displayEmail(p));
  }

  displayPhone(p: PatientResponse): string {
    return p.phoneNumberPrimary?.trim() || p.phoneNumberSecondary?.trim() || '';
  }

  displayEmail(p: PatientResponse): string {
    return p.email?.trim() || '';
  }

  metricSelected(id: string): boolean {
    if (id === 'all')
      return (
        !this.statusFilter &&
        !this.contactFilter &&
        !this.searchTerm.trim() &&
        !this.genderFilter &&
        !this.bloodTypeFilter
      );
    if (id === 'no-contact') return this.contactFilter === 'missing' && !this.statusFilter;
    return this.statusFilter === id && !this.contactFilter;
  }

  selectMetric(id: string): void {
    this.genderFilter = '';
    this.bloodTypeFilter = '';
    this.searchTerm = '';
    this.statusFilter = id === 'active' || id === 'inactive' ? id : '';
    this.contactFilter = id === 'no-contact' ? 'missing' : '';
    this.onFilterChange();
  }

  changePageSize(size: number): void {
    if (![10, 20, 50].includes(size)) return;
    this.pageSize = size;
    this.currentPage.set(0);
  }

  /* Sort */
  sortField = signal<SortField>('name');
  sortDir = signal<SortDir>('asc');

  /* Pagination */
  pageSize = 20;
  currentPage = signal(0);

  /* Hospitals for the filter dropdown */
  hospitals = signal<HospitalResponse[]>([]);

  readonly genderOptions = ['MALE', 'FEMALE', 'OTHER', 'PREFER_NOT_TO_SAY'];
  readonly bloodTypes = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'];

  get activeFilterCount(): number {
    return [
      this.genderFilter,
      this.statusFilter,
      this.bloodTypeFilter,
      this.hospitalFilter,
      this.contactFilter,
    ].filter(Boolean).length;
  }

  get pagedFiltered(): PatientResponse[] {
    const start = this.currentPage() * this.pageSize;
    return this.filtered().slice(start, start + this.pageSize);
  }

  get totalPages(): number {
    return Math.ceil(this.filtered().length / this.pageSize);
  }

  ngOnInit(): void {
    // Debounce the search input
    this.searchInput$.pipe(debounceTime(300), takeUntil(this.destroy$)).subscribe(() => {
      this.currentPage.set(0);
      this.applyFilter();
    });

    this.loadPatients();
    this.loadHospitals();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onSearchChange(value: string): void {
    this.searchTerm = value;
    this.searchInput$.next(value);
  }

  loadPatients(): void {
    const request = ++this.listRequest;
    this.listSubscription?.unsubscribe();
    this.loading.set(true);
    this.loadError.set(false);
    this.listSubscription = this.patientService
      .list()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          if (request !== this.listRequest) return;
          this.patients.set(data ?? []);
          this.applyFilter();
          this.loading.set(false);
        },
        error: () => {
          if (request !== this.listRequest) return;
          this.patients.set([]);
          this.filtered.set([]);
          this.currentPage.set(0);
          this.loadError.set(true);
          this.loading.set(false);
        },
      });
  }

  loadHospitals(): void {
    // ── TENANT ISOLATION: only SUPER_ADMIN loads full hospital list for filter ──
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe({
        next: (h) => this.hospitals.set(h),
        error: () => {
          /* silent */
        },
      });
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => this.hospitals.set([h]),
      });
    }
  }

  applyFilter(): void {
    let result = this.patients();

    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      const tokens = term.split(/\s+/);
      result = result.filter(
        (p) =>
          tokens.every((token) =>
            [p.firstName, p.middleName, p.lastName]
              .filter(Boolean)
              .join(' ')
              .toLowerCase()
              .includes(token),
          ) ||
          (p.email?.toLowerCase().includes(term) ?? false) ||
          (p.mrn?.toLowerCase().includes(term) ?? false) ||
          (p.phoneNumberPrimary?.includes(term) ?? false) ||
          (p.phoneNumberSecondary?.includes(term) ?? false),
      );
    }
    if (this.genderFilter) {
      result = result.filter((p) => (p.gender ?? '').toUpperCase() === this.genderFilter);
    }
    if (this.statusFilter === 'active') result = result.filter((p) => p.active);
    if (this.statusFilter === 'inactive') result = result.filter((p) => !p.active);
    if (this.bloodTypeFilter) {
      result = result.filter((p) => p.bloodType === this.bloodTypeFilter);
    }
    if (this.hospitalFilter) {
      result = result.filter((p) => p.hospitalId === this.hospitalFilter);
    }

    if (this.contactFilter)
      result = result.filter((p) => this.hasContact(p) === (this.contactFilter === 'recorded'));

    // Sort
    const field = this.sortField();
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    result = [...result].sort((a, b) => {
      switch (field) {
        case 'name':
          return dir * `${a.firstName} ${a.lastName}`.localeCompare(`${b.firstName} ${b.lastName}`);
        case 'mrn':
          return dir * (a.mrn ?? '').localeCompare(b.mrn ?? '');
        case 'gender':
          return dir * (a.gender ?? '').localeCompare(b.gender ?? '');
        case 'status':
          return dir * ((a.active ? 1 : 0) - (b.active ? 1 : 0));
        case 'createdAt':
          return dir * (a.createdAt ?? '').localeCompare(b.createdAt ?? '');
        default:
          return 0;
      }
    });

    this.filtered.set(result);
    this.currentPage.set(Math.max(0, Math.min(this.currentPage(), this.totalPages - 1)));
  }

  sort(field: SortField): void {
    if (this.sortField() === field) {
      this.sortDir.set(this.sortDir() === 'asc' ? 'desc' : 'asc');
    } else {
      this.sortField.set(field);
      this.sortDir.set('asc');
    }
    this.currentPage.set(0);
    this.applyFilter();
  }

  sortIcon(field: SortField): string {
    if (this.sortField() !== field) return 'unfold_more';
    return this.sortDir() === 'asc' ? 'arrow_upward' : 'arrow_downward';
  }

  clearFilters(): void {
    this.genderFilter = '';
    this.statusFilter = '';
    this.bloodTypeFilter = '';
    this.hospitalFilter = '';
    this.contactFilter = '';
    this.searchTerm = '';
    this.currentPage.set(0);
    this.applyFilter();
  }

  removeFilter(key: 'gender' | 'status' | 'bloodType' | 'hospital' | 'contact'): void {
    if (key === 'gender') this.genderFilter = '';
    if (key === 'status') this.statusFilter = '';
    if (key === 'bloodType') this.bloodTypeFilter = '';
    if (key === 'hospital') this.hospitalFilter = '';
    if (key === 'contact') this.contactFilter = '';
    this.currentPage.set(0);
    this.applyFilter();
  }

  onFilterChange(): void {
    this.currentPage.set(0);
    this.applyFilter();
  }

  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages) {
      this.currentPage.set(page);
    }
  }

  /**
   * Locale-aware gender label. Same three-tier lookup as the `enumLabel` pipe:
   * the raw enum under `PORTAL.ENUM.GENDER.*` first, then a prettified fallback
   * so an unmapped value never renders as a raw translation key.
   */
  hospitalName(id: string): string {
    return this.hospitals().find((h) => h.id === id)?.name ?? id;
  }
}
