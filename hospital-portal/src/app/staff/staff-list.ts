import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { StaffService, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-staff-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './staff-list.html',
  styleUrl: './staff-list.scss',
})
export class StaffListComponent implements OnInit {
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);

  staff = signal<StaffResponse[]>([]);
  filtered = signal<StaffResponse[]>([]);
  searchTerm = '';
  loading = signal(true);

  ngOnInit(): void {
    this.loadStaff();
  }

  loadStaff(): void {
    this.loading.set(true);
    this.staffService.list().subscribe({
      next: (data) => {
        this.staff.set(data);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load staff');
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.staff());
      return;
    }
    this.filtered.set(
      this.staff().filter(
        (s) =>
          s.name.toLowerCase().includes(term) ||
          s.email.toLowerCase().includes(term) ||
          (s.departmentName?.toLowerCase().includes(term) ?? false) ||
          (s.jobTitle?.toLowerCase().includes(term) ?? false) ||
          (s.roleName?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  getInitials(name: string): string {
    if (!name) return '??';
    const parts = name.trim().split(/\s+/);
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
    return name.substring(0, 2).toUpperCase();
  }

  formatJobTitle(jobTitle?: string): string {
    if (!jobTitle) return 'Staff';
    return jobTitle
      .replaceAll('_', ' ')
      .toLowerCase()
      .replaceAll(/\b\w/g, (c) => c.toUpperCase());
  }
}
