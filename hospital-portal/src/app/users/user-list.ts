import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { UserService, UserSummary, AdminRegisterRequest } from '../services/user.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-user-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './user-list.html',
  styleUrl: './user-list.scss',
})
export class UserListComponent implements OnInit {
  private readonly userService = inject(UserService);
  private readonly toast = inject(ToastService);

  users = signal<UserSummary[]>([]);
  filtered = signal<UserSummary[]>([]);
  loading = signal(true);
  searchTerm = '';

  currentPage = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);

  showCreate = signal(false);
  saving = signal(false);
  createForm: AdminRegisterRequest = {
    username: '',
    email: '',
    password: '',
    firstName: '',
    lastName: '',
    roleNames: [],
  };
  roleInput = '';

  ngOnInit(): void {
    this.loadUsers();
  }

  loadUsers(page = 0): void {
    this.loading.set(true);
    this.userService.list(page, 20).subscribe({
      next: (res) => {
        this.users.set(res.content);
        this.currentPage.set(res.number);
        this.totalPages.set(res.totalPages);
        this.totalElements.set(res.totalElements);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load users');
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.users());
      return;
    }
    this.filtered.set(
      this.users().filter(
        (u) =>
          u.username.toLowerCase().includes(term) ||
          u.email.toLowerCase().includes(term) ||
          u.firstName.toLowerCase().includes(term) ||
          u.lastName.toLowerCase().includes(term) ||
          (u.roleName?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  getInitials(u: UserSummary): string {
    return `${u.firstName?.charAt(0) ?? ''}${u.lastName?.charAt(0) ?? ''}`.toUpperCase() || '?';
  }

  openCreate(): void {
    this.createForm = {
      username: '',
      email: '',
      password: '',
      firstName: '',
      lastName: '',
      roleNames: [],
    };
    this.roleInput = '';
    this.showCreate.set(true);
  }

  closeCreate(): void {
    this.showCreate.set(false);
  }

  submitCreate(): void {
    if (
      !this.createForm.username ||
      !this.createForm.email ||
      !this.createForm.password ||
      !this.createForm.firstName ||
      !this.createForm.lastName
    ) {
      this.toast.error('All required fields must be filled');
      return;
    }
    this.createForm.roleNames = this.roleInput
      .split(',')
      .map((r) => r.trim())
      .filter((r) => r.length > 0);

    this.saving.set(true);
    this.userService.adminRegister(this.createForm).subscribe({
      next: () => {
        this.toast.success('User created successfully');
        this.showCreate.set(false);
        this.saving.set(false);
        this.loadUsers();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to create user');
        this.saving.set(false);
      },
    });
  }

  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages()) {
      this.loadUsers(page);
    }
  }
}
