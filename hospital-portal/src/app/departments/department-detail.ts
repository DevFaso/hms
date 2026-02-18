import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ToastService } from '../core/toast.service';

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

interface DepartmentStats {
  totalStaff: number;
  activeStaff: number;
  totalPatients: number;
  totalAppointments: number;
}

@Component({
  selector: 'app-department-detail',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './department-detail.html',
  styleUrl: './department-detail.scss',
})
export class DepartmentDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);

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
        this.toast.error('Department not found');
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
  }

  setTab(tab: 'overview' | 'staff' | 'stats'): void {
    this.activeTab.set(tab);
  }
}
