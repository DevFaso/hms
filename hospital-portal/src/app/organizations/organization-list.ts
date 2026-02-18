import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  OrganizationService,
  OrganizationResponse,
  OrganizationCreateRequest,
} from '../services/organization.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-organization-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './organization-list.html',
  styleUrl: './organization-list.scss',
})
export class OrganizationListComponent implements OnInit {
  private readonly orgService = inject(OrganizationService);
  private readonly toast = inject(ToastService);

  organizations = signal<OrganizationResponse[]>([]);
  filtered = signal<OrganizationResponse[]>([]);
  loading = signal(true);
  searchTerm = '';

  showCreate = signal(false);
  saving = signal(false);
  createForm: OrganizationCreateRequest = { name: '', code: '' };

  currentPage = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);

  ngOnInit(): void {
    this.loadOrganizations();
  }

  loadOrganizations(page = 0): void {
    this.loading.set(true);
    this.orgService.list(page, 20).subscribe({
      next: (res) => {
        this.organizations.set(res.content);
        this.currentPage.set(res.number);
        this.totalPages.set(res.totalPages);
        this.totalElements.set(res.totalElements);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load organizations');
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.organizations());
      return;
    }
    this.filtered.set(
      this.organizations().filter(
        (o) =>
          o.name.toLowerCase().includes(term) ||
          o.code.toLowerCase().includes(term) ||
          (o.type?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  openCreate(): void {
    this.createForm = { name: '', code: '' };
    this.showCreate.set(true);
  }

  closeCreate(): void {
    this.showCreate.set(false);
  }

  submitCreate(): void {
    if (!this.createForm.name || !this.createForm.code) {
      this.toast.error('Name and code are required');
      return;
    }
    this.saving.set(true);
    this.orgService.create(this.createForm).subscribe({
      next: () => {
        this.toast.success('Organization created');
        this.showCreate.set(false);
        this.saving.set(false);
        this.loadOrganizations();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to create organization');
        this.saving.set(false);
      },
    });
  }

  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages()) {
      this.loadOrganizations(page);
    }
  }
}
