import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { StaffService, StaffResponse } from '../services/staff.service';
import {
  StaffSchedulingService,
  StaffShiftResponse,
  StaffLeaveResponse,
} from '../services/staff-scheduling.service';
import { ToastService } from '../core/toast.service';

type TabType = 'overview' | 'employment' | 'department' | 'schedule';

@Component({
  selector: 'app-staff-detail',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './staff-detail.html',
  styleUrl: './staff-detail.scss',
})
export class StaffDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly staffService = inject(StaffService);
  private readonly schedulingService = inject(StaffSchedulingService);
  private readonly toast = inject(ToastService);

  staff = signal<StaffResponse | null>(null);
  loading = signal(true);
  activeTab = signal<TabType>('overview');

  // Schedule data
  shifts = signal<StaffShiftResponse[]>([]);
  leaves = signal<StaffLeaveResponse[]>([]);
  shiftsLoading = signal(false);
  leavesLoading = signal(false);

  // Week navigation for shifts
  weekOffset = signal(0);

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.loadStaff(id);
    }
  }

  loadStaff(id: string): void {
    this.loading.set(true);
    this.staffService.getById(id).subscribe({
      next: (data) => {
        this.staff.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load staff member');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: TabType): void {
    this.activeTab.set(tab);
    if (tab === 'schedule') {
      this.loadScheduleData();
    }
  }

  // ── Schedule data ──
  private loadScheduleData(): void {
    const s = this.staff();
    if (!s) return;

    this.loadShifts();
    this.loadLeaves(s.id);
  }

  private loadShifts(): void {
    const s = this.staff();
    if (!s) return;
    this.shiftsLoading.set(true);

    const { start, end } = this.getWeekRange();
    this.schedulingService.listShifts({ staffId: s.id, startDate: start, endDate: end }).subscribe({
      next: (data) => {
        this.shifts.set(data);
        this.shiftsLoading.set(false);
      },
      error: () => {
        this.shifts.set([]);
        this.shiftsLoading.set(false);
      },
    });
  }

  private loadLeaves(staffId: string): void {
    this.leavesLoading.set(true);
    this.schedulingService.listLeaves({ staffId }).subscribe({
      next: (data) => {
        this.leaves.set(data);
        this.leavesLoading.set(false);
      },
      error: () => {
        this.leaves.set([]);
        this.leavesLoading.set(false);
      },
    });
  }

  changeWeek(delta: number): void {
    this.weekOffset.set(this.weekOffset() + delta);
    this.loadShifts();
  }

  goToCurrentWeek(): void {
    this.weekOffset.set(0);
    this.loadShifts();
  }

  getWeekRange(): { start: string; end: string } {
    const now = new Date();
    const day = now.getDay();
    const monday = new Date(now);
    monday.setDate(now.getDate() - (day === 0 ? 6 : day - 1) + this.weekOffset() * 7);
    const sunday = new Date(monday);
    sunday.setDate(monday.getDate() + 6);
    return {
      start: this.toISODate(monday),
      end: this.toISODate(sunday),
    };
  }

  getWeekDays(): { label: string; date: string; isToday: boolean }[] {
    const { start } = this.getWeekRange();
    const monday = new Date(start + 'T00:00:00');
    const today = this.toISODate(new Date());
    const dayNames = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    return dayNames.map((label, i) => {
      const d = new Date(monday);
      d.setDate(monday.getDate() + i);
      const date = this.toISODate(d);
      return { label, date, isToday: date === today };
    });
  }

  getShiftsForDay(date: string): StaffShiftResponse[] {
    return this.shifts().filter((s) => s.shiftDate === date);
  }

  getWeekLabel(): string {
    const { start, end } = this.getWeekRange();
    const s = new Date(start + 'T00:00:00');
    const e = new Date(end + 'T00:00:00');
    const opts: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric' };
    return `${s.toLocaleDateString('en-US', opts)} – ${e.toLocaleDateString('en-US', { ...opts, year: 'numeric' })}`;
  }

  // ── Helpers ──
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

  formatEmploymentType(type?: string): string {
    if (!type) return '—';
    return type
      .replaceAll('_', ' ')
      .toLowerCase()
      .replaceAll(/\b\w/g, (c) => c.toUpperCase());
  }

  formatDate(date?: string): string {
    if (!date) return '—';
    return new Date(date).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  }

  formatShortDate(date: string): string {
    return new Date(date + 'T00:00:00').toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
    });
  }

  formatTime(time?: string): string {
    if (!time) return '—';
    const [h, m] = time.split(':').map(Number);
    const ampm = h >= 12 ? 'PM' : 'AM';
    const hr = h % 12 || 12;
    return `${hr}:${m.toString().padStart(2, '0')} ${ampm}`;
  }

  formatShiftType(type: string): string {
    return type.charAt(0) + type.slice(1).toLowerCase();
  }

  formatLeaveType(type: string): string {
    return type.charAt(0) + type.slice(1).toLowerCase();
  }

  shiftTypeIcon(type: string): string {
    const map: Record<string, string> = {
      MORNING: 'wb_sunny',
      AFTERNOON: 'wb_twilight',
      EVENING: 'nights_stay',
      NIGHT: 'dark_mode',
      FLEX: 'schedule',
    };
    return map[type] ?? 'schedule';
  }

  leaveTypeIcon(type: string): string {
    const map: Record<string, string> = {
      VACATION: 'beach_access',
      SICK: 'healing',
      EMERGENCY: 'emergency',
      TRAINING: 'school',
      UNPAID: 'money_off',
      OTHER: 'more_horiz',
    };
    return map[type] ?? 'event_busy';
  }

  private toISODate(d: Date): string {
    return d.toISOString().split('T')[0];
  }
}
