import { textMatches } from '../shared/person-search';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import {
  Component,
  inject,
  OnDestroy,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import {
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  of,
  switchMap,
  takeUntil,
  tap,
} from 'rxjs';
import { AppointmentService, AppointmentResponse } from '../services/appointment.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PermissionService } from '../core/permission.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { localDateString } from '../shared/date-utils';
import { ClockTimePipe } from '../shared/pipes/clock-time.pipe';
type SortField = 'patient' | 'doctor' | 'date' | 'status';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-appointment-list',
  standalone: true,
  imports: [
    ClockTimePipe,
    ServerTimePipe,
    CommonModule,
    RouterLink,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
  ],
  templateUrl: './appointment-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './appointment-list.scss',
})
export class AppointmentListComponent implements OnInit, OnDestroy {
  private readonly appointmentService = inject(AppointmentService);
  private readonly hospitalService = inject(HospitalService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);
  private readonly destroy$ = new Subject<void>();
  private readonly searchInput$ = new Subject<string>();

  appointments = signal<AppointmentResponse[]>([]);
  /**
   * QA D1322 (round 29; the D1310 / D1274 class): what the SERVER finds for
   * the typed term — its `?search=` goes through the patient-search router
   * (MRN, phone, e-mail, birth date as well as names). The page filtered
   * only the four names it holds, so "CHW1112", "70291801" or "14/08/1979"
   * found nothing. `null`: no term, or the answer is not back yet.
   */
  private readonly serverMatches = signal<AppointmentResponse[] | null>(null);
  filtered = signal<AppointmentResponse[]>([]);
  loading = signal(true);
  canCreate = this.permissions.hasPermission('Create Appointments');

  /* Filters */
  searchTerm = '';
  statusFilter = '';
  dateFrom = '';
  dateTo = '';
  hospitalFilter = '';
  showFilters = signal(false);

  /* Sort */
  sortField = signal<SortField>('date');
  sortDir = signal<SortDir>('desc');

  /* Pagination */
  pageSize = 20;
  currentPage = signal(0);

  hospitals = signal<HospitalResponse[]>([]);

  get activeFilterCount(): number {
    return [this.statusFilter, this.dateFrom, this.dateTo, this.hospitalFilter].filter(Boolean)
      .length;
  }

  get pagedFiltered(): AppointmentResponse[] {
    const start = this.currentPage() * this.pageSize;
    return this.filtered().slice(start, start + this.pageSize);
  }

  get totalPages(): number {
    return Math.ceil(this.filtered().length / this.pageSize);
  }

  ngOnInit(): void {
    this.searchInput$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap(() => {
          this.currentPage.set(0);
          this.serverMatches.set(null);
          this.applyFilter();
        }),
        switchMap((value) => {
          const term = value.trim();
          if (!term) return of(null);
          // A failed search keeps the names the page holds; it never empties the list.
          return this.appointmentService.list({ search: term }).pipe(catchError(() => of(null)));
        }),
        takeUntil(this.destroy$),
      )
      .subscribe((rows) => {
        this.serverMatches.set(rows);
        this.applyFilter();
      });

    this.loadAppointments();
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

  loadAppointments(): void {
    this.loading.set(true);
    this.appointmentService.list().subscribe({
      next: (data) => {
        // Sort newest-first by default
        this.appointments.set(
          data.sort((a, b) => b.appointmentDate.localeCompare(a.appointmentDate)),
        );
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('APPOINTMENTS.LOAD_FAILED'));
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
    const term = this.searchTerm.toLowerCase().trim();
    const server = term ? this.serverMatches() : null;
    const serverIds = new Set((server ?? []).map((a) => a.id));
    // The server may know a match the page has not loaded: both are read.
    let result = server
      ? [...this.appointments(), ...server.filter((a) => !this.appointments().some((b) => b.id === a.id))]
      : this.appointments();

    if (this.statusFilter) {
      result = result.filter((a) => a.status === this.statusFilter);
    }
    if (this.dateFrom) {
      result = result.filter((a) => a.appointmentDate >= this.dateFrom);
    }
    if (this.dateTo) {
      result = result.filter((a) => a.appointmentDate <= this.dateTo);
    }
    if (this.hospitalFilter) {
      result = result.filter((a) => a.hospitalId === this.hospitalFilter);
    }
    if (term) {
      result = result.filter(
        (a) =>
          serverIds.has(a.id) ||
          textMatches(a.patientName, term) ||
          textMatches(a.staffName, term) ||
          textMatches(a.reason, term) ||
          textMatches(a.hospitalName, term),
      );
    }

    // Sort
    const field = this.sortField();
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    result = [...result].sort((a, b) => {
      switch (field) {
        case 'patient':
          return dir * a.patientName.localeCompare(b.patientName);
        case 'doctor':
          return dir * a.staffName.localeCompare(b.staffName);
        case 'date':
          return (
            dir *
            `${a.appointmentDate}T${a.startTime}`.localeCompare(
              `${b.appointmentDate}T${b.startTime}`,
            )
          );
        case 'status':
          return dir * a.status.localeCompare(b.status);
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
      this.sortDir.set(field === 'date' ? 'desc' : 'asc');
    }
    this.currentPage.set(0);
    this.applyFilter();
  }

  sortIcon(field: SortField): string {
    if (this.sortField() !== field) return 'unfold_more';
    return this.sortDir() === 'asc' ? 'arrow_upward' : 'arrow_downward';
  }

  onFilterChange(): void {
    this.currentPage.set(0);
    this.applyFilter();
  }

  clearFilters(): void {
    this.statusFilter = '';
    this.dateFrom = '';
    this.dateTo = '';
    this.hospitalFilter = '';
    this.searchTerm = '';
    this.searchInput$.next('');
    this.currentPage.set(0);
    this.applyFilter();
  }

  removeFilter(key: 'status' | 'dateFrom' | 'dateTo' | 'hospital'): void {
    if (key === 'status') this.statusFilter = '';
    if (key === 'dateFrom') this.dateFrom = '';
    if (key === 'dateTo') this.dateTo = '';
    if (key === 'hospital') this.hospitalFilter = '';
    this.currentPage.set(0);
    this.applyFilter();
  }

  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages) this.currentPage.set(page);
  }

  getStatusClass(status: string): string {
    const base = 'status-badge ';
    switch (status) {
      case 'SCHEDULED':
      case 'CONFIRMED':
        return base + 'scheduled';
      case 'COMPLETED':
        return base + 'completed';
      case 'CANCELLED':
      case 'NO_SHOW':
        return base + 'cancelled';
      case 'IN_PROGRESS':
        return base + 'in-progress';
      default:
        return base;
    }
  }

  hospitalName(id: string): string {
    return this.hospitals().find((h) => h.id === id)?.name ?? id;
  }

  todayIso(): string {
    return localDateString();
  }
}
