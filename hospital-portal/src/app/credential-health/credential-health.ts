import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CredentialHealthService,
  UserCredentialHealthDTO,
} from '../services/credential-health.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-credential-health',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './credential-health.html',
  styleUrl: './credential-health.scss',
})
export class CredentialHealthComponent implements OnInit {
  private readonly credService = inject(CredentialHealthService);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  users = signal<UserCredentialHealthDTO[]>([]);
  search = signal('');
  filterType = signal<'all' | 'no-mfa' | 'force-change' | 'no-recovery'>('all');

  selectedUser = signal<UserCredentialHealthDTO | null>(null);
  showDetail = signal(false);

  stats = computed(() => {
    const list = this.users();
    return {
      total: list.length,
      withMfa: list.filter((u) => u.mfaEnrolledCount > 0).length,
      forceChange: list.filter((u) => u.forcePasswordChange || u.forceUsernameChange).length,
      noRecovery: list.filter((u) => u.recoveryContactCount === 0).length,
    };
  });

  filteredUsers = computed(() => {
    const q = this.search().toLowerCase();
    const filter = this.filterType();
    return this.users().filter((u) => {
      const matchSearch =
        !q || u.username.toLowerCase().includes(q) || (u.email?.toLowerCase().includes(q) ?? false);
      let matchFilter = true;
      if (filter === 'no-mfa') matchFilter = u.mfaEnrolledCount === 0;
      if (filter === 'force-change') matchFilter = u.forcePasswordChange || u.forceUsernameChange;
      if (filter === 'no-recovery') matchFilter = u.recoveryContactCount === 0;
      return matchSearch && matchFilter;
    });
  });

  ngOnInit(): void {
    this.loadHealth();
  }

  loadHealth(): void {
    this.loading.set(true);
    this.credService.listCredentialHealth().subscribe({
      next: (list) => {
        this.users.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load credential health data');
        this.loading.set(false);
      },
    });
  }

  openDetail(user: UserCredentialHealthDTO): void {
    this.selectedUser.set(user);
    this.showDetail.set(true);
  }

  closeDetail(): void {
    this.showDetail.set(false);
    this.selectedUser.set(null);
  }

  getHealthScore(user: UserCredentialHealthDTO): 'good' | 'warning' | 'critical' {
    if (user.forcePasswordChange || user.forceUsernameChange) return 'critical';
    if (user.mfaEnrolledCount === 0 || user.recoveryContactCount === 0) return 'warning';
    return 'good';
  }
}
