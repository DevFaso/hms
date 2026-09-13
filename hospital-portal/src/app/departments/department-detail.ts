import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

interface DepartmentDetail {
  id: string;
  name: string;
  code: string;
  description?: string;
  headOfDepartment?: string;
  headOfDepartmentName?: string;
  active: boolean;
  hospitalId?: string;
  hospitalName?: string;
  createdAt?: string;
  updatedAt?: string;
}

interface DepartmentStaff {
  id: string;
  name: string;
  jobTitle?: string;
  email?: string;
  active: boolean;
}

/** E9 #63 — the department's default sensitive category (the backend's SensitivityTagResponseDTO). */
interface DepartmentSensitivity {
  id: string;
  departmentDefault: SensitivityCategory | null;
}

export type SensitivityCategory =
  'SUBSTANCE_USE' | 'BEHAVIOURAL_HEALTH' | 'HIV' | 'REPRODUCTIVE_HEALTH';
export const SENSITIVITY_CATEGORIES: SensitivityCategory[] = [
  'SUBSTANCE_USE',
  'BEHAVIOURAL_HEALTH',
  'HIV',
  'REPRODUCTIVE_HEALTH',
];

interface DepartmentStats {
  totalStaff: number;
  activeStaff: number;
  totalPatients: number;
  totalAppointments: number;
}

@Component({
  selector: 'app-department-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslateModule],
  templateUrl: './department-detail.html',
  styleUrl: './department-detail.scss',
})
export class DepartmentDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);

  /**
   * E9 #63 — a hospital admin classifies the department once; every untagged
   * row recorded in it inherits the category and stays behind
   * break-the-glass when another hospital reads the chart (decision D3).
   */
  readonly canClassify = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly sensitivityCategories = SENSITIVITY_CATEGORIES;
  defaultSensitivity = signal<SensitivityCategory | null>(null);
  sensitivitySaving = signal(false);

  department = signal<DepartmentDetail | null>(null);
  staff = signal<DepartmentStaff[]>([]);
  stats = signal<DepartmentStats | null>(null);
  loading = signal(true);
  activeTab = signal<'overview' | 'staff' | 'stats'>('overview');

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.router.navigate(['/departments']);
      return;
    }
    this.loadDepartment(id);
  }

  private loadDepartment(id: string): void {
    this.loading.set(true);

    this.http.get<DepartmentDetail>(`/departments/${id}`).subscribe({
      next: (dept) => {
        this.department.set(dept);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('DEPARTMENTS.TOAST.NOT_FOUND'));
        this.loading.set(false);
        this.router.navigate(['/departments']);
      },
    });

    this.http.get<{ staff: DepartmentStaff[] }>(`/departments/${id}/with-staff`).subscribe({
      next: (res) => this.staff.set(res?.staff ?? []),
    });

    this.http.get<DepartmentStats>(`/departments/${id}/stats`).subscribe({
      next: (s) => this.stats.set(s),
      error: () => {
        /* stats optional — dept may not have computed stats yet */
      },
    });

    if (this.canClassify) {
      this.http.get<DepartmentSensitivity>(`/departments/${id}/default-sensitivity`).subscribe({
        next: (s) => this.defaultSensitivity.set(s?.departmentDefault ?? null),
        error: () =>
          this.toast.error(this.translate.instant('DEPARTMENTS.TOAST.SENSITIVITY_LOAD_FAILED')),
      });
    }
  }

  /** An empty selection clears the default: the department's rows become untagged again. */
  setDefaultSensitivity(value: string): void {
    const dept = this.department();
    if (!dept || !this.canClassify) return;
    const category = (value || null) as SensitivityCategory | null;
    const previous = this.defaultSensitivity();
    this.sensitivitySaving.set(true);
    this.defaultSensitivity.set(category);
    this.http
      .put<DepartmentSensitivity>(`/departments/${dept.id}/default-sensitivity`, { category })
      .subscribe({
        next: (s) => {
          this.defaultSensitivity.set(s?.departmentDefault ?? null);
          this.sensitivitySaving.set(false);
          this.toast.success(this.translate.instant('DEPARTMENTS.TOAST.SENSITIVITY_SAVED'));
        },
        error: () => {
          this.defaultSensitivity.set(previous);
          this.sensitivitySaving.set(false);
          this.toast.error(this.translate.instant('DEPARTMENTS.TOAST.SENSITIVITY_FAILED'));
        },
      });
  }

  setTab(tab: 'overview' | 'staff' | 'stats'): void {
    this.activeTab.set(tab);
  }
}
