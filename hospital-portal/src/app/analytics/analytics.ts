import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import {
  DashboardService,
  PlatformAnalytics,
  TrendPoint,
  DepartmentUtilization,
  HospitalMetric,
} from '../services/dashboard.service';

interface StatCard {
  label: string;
  value: number;
  icon: string;
  color: string;
  bg: string;
}

@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './analytics.html',
  styleUrl: './analytics.scss',
})
export class AnalyticsComponent implements OnInit {
  private readonly dashboardService = inject(DashboardService);

  loading = signal(true);
  analytics = signal<PlatformAnalytics | null>(null);

  statCards = computed<StatCard[]>(() => {
    const a = this.analytics();
    if (!a) return [];
    return [
      {
        label: 'Total Patients',
        value: a.totalPatients,
        icon: 'people',
        color: '#3b82f6',
        bg: '#eff6ff',
      },
      {
        label: 'Appointments',
        value: a.totalAppointments,
        icon: 'calendar_month',
        color: '#8b5cf6',
        bg: '#f5f3ff',
      },
      {
        label: 'Encounters',
        value: a.totalEncounters,
        icon: 'medical_services',
        color: '#10b981',
        bg: '#ecfdf5',
      },
      {
        label: 'Invoices',
        value: a.totalInvoices,
        icon: 'receipt_long',
        color: '#f59e0b',
        bg: '#fffbeb',
      },
      {
        label: 'Lab Orders',
        value: a.totalLabOrders,
        icon: 'science',
        color: '#ef4444',
        bg: '#fef2f2',
      },
      {
        label: 'Prescriptions',
        value: a.totalPrescriptions,
        icon: 'medication',
        color: '#06b6d4',
        bg: '#ecfeff',
      },
      {
        label: 'Users',
        value: a.totalUsers,
        icon: 'group',
        color: '#6366f1',
        bg: '#eef2ff',
      },
      {
        label: 'Active Hospitals',
        value: a.activeHospitals,
        icon: 'local_hospital',
        color: '#ec4899',
        bg: '#fdf2f8',
      },
    ];
  });

  appointmentTrend = computed<TrendPoint[]>(() => this.analytics()?.appointmentTrend ?? []);

  appointmentsByStatus = computed<{ label: string; value: number; pct: number }[]>(() => {
    const map = this.analytics()?.appointmentsByStatus ?? {};
    const total = Object.values(map).reduce((s, v) => s + v, 0) || 1;
    return Object.entries(map).map(([label, value]) => ({
      label,
      value,
      pct: Math.round((value / total) * 100),
    }));
  });

  encountersByStatus = computed<{ label: string; value: number; pct: number }[]>(() => {
    const map = this.analytics()?.encountersByStatus ?? {};
    const total = Object.values(map).reduce((s, v) => s + v, 0) || 1;
    return Object.entries(map).map(([label, value]) => ({
      label,
      value,
      pct: Math.round((value / total) * 100),
    }));
  });

  invoicesByStatus = computed<{ label: string; value: number; pct: number }[]>(() => {
    const map = this.analytics()?.invoicesByStatus ?? {};
    const total = Object.values(map).reduce((s, v) => s + v, 0) || 1;
    return Object.entries(map).map(([label, value]) => ({
      label,
      value,
      pct: Math.round((value / total) * 100),
    }));
  });

  departmentUtil = computed<DepartmentUtilization[]>(
    () => this.analytics()?.departmentUtilization ?? [],
  );

  hospitalMetrics = computed<HospitalMetric[]>(() => this.analytics()?.hospitalMetrics ?? []);

  maxTrend = computed(() => {
    const pts = this.appointmentTrend();
    return Math.max(1, ...pts.map((p) => p.count));
  });

  ngOnInit(): void {
    this.dashboardService.getAnalytics(14).subscribe({
      next: (data) => {
        this.analytics.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusColor(status: string): string {
    const colors: Record<string, string> = {
      SCHEDULED: '#3b82f6',
      CONFIRMED: '#6366f1',
      COMPLETED: '#10b981',
      CANCELLED: '#ef4444',
      NO_SHOW: '#f59e0b',
      IN_PROGRESS: '#06b6d4',
      ARRIVED: '#8b5cf6',
      PENDING: '#94a3b8',
      DRAFT: '#94a3b8',
      SENT: '#3b82f6',
      PAID: '#10b981',
      PARTIALLY_PAID: '#f59e0b',
      OVERDUE: '#ef4444',
      ACTIVE: '#10b981',
      DISCHARGED: '#64748b',
    };
    return colors[status] || '#94a3b8';
  }

  barHeight(count: number): string {
    return `${Math.max(4, (count / this.maxTrend()) * 100)}%`;
  }

  shortDate(dateStr: string): string {
    const d = new Date(dateStr);
    return `${d.getMonth() + 1}/${d.getDate()}`;
  }
}
