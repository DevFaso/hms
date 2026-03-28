import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { PatientService, PatientResponse } from '../services/patient.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PermissionService } from '../core/permission.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule } from '@ngx-translate/core';

type SortField = 'name' | 'mrn' | 'gender' | 'status' | 'createdAt';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-patient-list',
  standalone: true,
  imports: [CommonModule, RouterLink, FormsModule, TranslateModule],
  templateUrl: './patient-list.html',
  styleUrl: './patient-list.scss',
})
export class PatientListComponent implements OnInit, OnDestroy {
  private readonly patientService = inject(PatientService);
  private readonly hospitalService = inject(HospitalService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
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
  hospitalFilter = '';
  showFilters = signal(false);

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
    return [this.genderFilter, this.statusFilter, this.bloodTypeFilter, this.hospitalFilter].filter(
      Boolean,
    ).length;
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
    this.searchInput$
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => {
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
    this.loading.set(true);
    this.patientService.list().subscribe({
      next: (data) => {
        this.patients.set(data);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load patients');
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
      result = result.filter(
        (p) =>
          p.firstName.toLowerCase().includes(term) ||
          p.lastName.toLowerCase().includes(term) ||
          (p.email?.toLowerCase().includes(term) ?? false) ||
          (p.mrn?.toLowerCase().includes(term) ?? false) ||
          (p.phoneNumberPrimary?.includes(term) ?? false),
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
    this.searchTerm = '';
    this.currentPage.set(0);
    this.applyFilter();
  }

  removeFilter(key: 'gender' | 'status' | 'bloodType' | 'hospital'): void {
    if (key === 'gender') this.genderFilter = '';
    if (key === 'status') this.statusFilter = '';
    if (key === 'bloodType') this.bloodTypeFilter = '';
    if (key === 'hospital') this.hospitalFilter = '';
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

  formatGender(g: string): string {
    return g.charAt(0).toUpperCase() + g.slice(1).toLowerCase().replaceAll('_', ' ');
  }

  hospitalName(id: string): string {
    return this.hospitals().find((h) => h.id === id)?.name ?? id;
  }
}
