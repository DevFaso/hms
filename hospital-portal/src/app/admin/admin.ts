import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../auth/auth.service';

interface SystemStat {
  label: string;
  value: string | number;
  icon: string;
  color: string;
}

interface AdminSection {
  title: string;
  description: string;
  icon: string;
  route: string;
  color: string;
}

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './admin.html',
  styleUrl: './admin.scss',
})
export class AdminComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  loading = signal(true);

  stats = signal<SystemStat[]>([
    { label: 'Total Users', value: '—', icon: 'group', color: '#3b82f6' },
    { label: 'Active Staff', value: '—', icon: 'badge', color: '#10b981' },
    { label: 'Departments', value: '—', icon: 'domain', color: '#8b5cf6' },
    { label: 'System Health', value: 'Online', icon: 'monitor_heart', color: '#059669' },
  ]);

  sections: AdminSection[] = [
    {
      title: 'User Management',
      description: 'Manage user accounts, roles, and permissions',
      icon: 'manage_accounts',
      route: '/staff',
      color: '#3b82f6',
    },
    {
      title: 'Department Management',
      description: 'Create and configure hospital departments',
      icon: 'domain',
      route: '/departments',
      color: '#8b5cf6',
    },
    {
      title: 'Billing Configuration',
      description: 'Configure billing rates, insurance, and payment methods',
      icon: 'receipt_long',
      route: '/billing',
      color: '#10b981',
    },
    {
      title: 'Laboratory Settings',
      description: 'Manage lab test catalogs and processing workflows',
      icon: 'science',
      route: '/lab',
      color: '#f59e0b',
    },
    {
      title: 'System Notifications',
      description: 'Configure notification templates and delivery channels',
      icon: 'notifications_active',
      route: '/notifications',
      color: '#ef4444',
    },
    {
      title: 'Audit Logs',
      description: 'View system audit trail and access logs',
      icon: 'shield',
      route: '/admin',
      color: '#64748b',
    },
  ];

  ngOnInit(): void {
    this.loadStats();
  }

  loadStats(): void {
    this.loading.set(true);
    this.http.get<Record<string, number>>('/super-admin/summary').subscribe({
      next: (data) => {
        this.stats.set([
          {
            label: 'Total Users',
            value: data['totalUsers'] ?? '—',
            icon: 'group',
            color: '#3b82f6',
          },
          {
            label: 'Active Staff',
            value: data['activeUsers'] ?? '—',
            icon: 'badge',
            color: '#10b981',
          },
          {
            label: 'Departments',
            value: data['totalHospitals'] ?? '—',
            icon: 'domain',
            color: '#8b5cf6',
          },
          { label: 'System Health', value: 'Online', icon: 'monitor_heart', color: '#059669' },
        ]);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  get userName(): string {
    const profile = this.auth.getUserProfile();
    return profile ? `${profile.firstName} ${profile.lastName}` : 'Admin';
  }
}
