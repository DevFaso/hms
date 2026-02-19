import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  StaffSchedulingService,
  StaffShiftRequest,
  StaffShiftResponse,
  StaffShiftType,
  StaffLeaveRequest,
  StaffLeaveResponse,
  StaffLeaveStatus,
  StaffLeaveType,
} from '../services/staff-scheduling.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService } from '../services/staff.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-scheduling',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './scheduling.html',
  styleUrl: './scheduling.scss',
})
export class SchedulingComponent implements OnInit {
  private readonly schedulingService = inject(StaffSchedulingService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);

  activeView = signal<'shifts' | 'leaves'>('shifts');

  // Shifts
  shifts = signal<StaffShiftResponse[]>([]);
  shiftsLoading = signal(false);
  weekOffset = signal(0);

  // Leaves
  leaves = signal<StaffLeaveResponse[]>([]);
  leavesLoading = signal(false);
  leaveFilter = signal<'ALL' | StaffLeaveStatus>('ALL');

  // Dropdowns
  hospitals = signal<HospitalResponse[]>([]);
  staffMembers = signal<{ id: string; name: string }[]>([]);

  // ── Shift Modal ──
  showShiftModal = signal(false);
  editingShift = signal(false);
  savingShift = signal(false);
  editingShiftId = '';
  shiftForm: StaffShiftRequest = this.emptyShiftForm();

  shiftTypes: StaffShiftType[] = ['MORNING', 'AFTERNOON', 'EVENING', 'NIGHT', 'FLEX'];

  emptyShiftForm(): StaffShiftRequest {
    return { staffId: '', hospitalId: '', shiftDate: '', startTime: '', endTime: '', shiftType: 'MORNING' };
  }

  // ── Leave Modal ──
  showLeaveModal = signal(false);
  savingLeave = signal(false);
  leaveForm: StaffLeaveRequest = this.emptyLeaveForm();

  leaveTypes: StaffLeaveType[] = ['VACATION', 'SICK', 'EMERGENCY', 'TRAINING', 'UNPAID', 'OTHER'];

  emptyLeaveForm(): StaffLeaveRequest {
    return { staffId: '', hospitalId: '', startDate: '', endDate: '', leaveType: 'VACATION', requiresCoverage: false };
  }

  ngOnInit(): void {
    this.loadShifts();
    this.loadLeaves();
    this.hospitalService.list().subscribe({ next: (h) => this.hospitals.set(h) });
    this.staffService.list().subscribe({
      next: (list) => this.staffMembers.set(list.map((s) => ({ id: s.id, name: s.name || s.email }))),
    });
  }

  // ── Shift CRUD ──
  openCreateShift(): void {
    this.shiftForm = this.emptyShiftForm();
    this.editingShift.set(false);
    this.editingShiftId = '';
    this.showShiftModal.set(true);
  }

  openEditShift(s: StaffShiftResponse): void {
    this.editingShift.set(true);
    this.editingShiftId = s.id;
    this.shiftForm = { staffId: s.staffId, hospitalId: s.hospitalId, departmentId: s.departmentId, shiftDate: s.shiftDate, startTime: s.startTime, endTime: s.endTime, shiftType: s.shiftType, notes: s.notes };
    this.showShiftModal.set(true);
  }

  closeShiftModal(): void { this.showShiftModal.set(false); }

  submitShift(): void {
    this.savingShift.set(true);
    const op = this.editingShift()
      ? this.schedulingService.updateShift(this.editingShiftId, this.shiftForm)
      : this.schedulingService.createShift(this.shiftForm);
    op.subscribe({
      next: () => { this.toast.success(this.editingShift() ? 'Shift updated' : 'Shift created'); this.closeShiftModal(); this.loadShifts(); this.savingShift.set(false); },
      error: () => { this.toast.error('Failed to save shift'); this.savingShift.set(false); },
    });
  }

  // ── Leave CRUD ──
  openCreateLeave(): void {
    this.leaveForm = this.emptyLeaveForm();
    this.showLeaveModal.set(true);
  }

  closeLeaveModal(): void { this.showLeaveModal.set(false); }

  submitLeave(): void {
    this.savingLeave.set(true);
    this.schedulingService.requestLeave(this.leaveForm).subscribe({
      next: () => { this.toast.success('Leave request submitted'); this.closeLeaveModal(); this.loadLeaves(); this.savingLeave.set(false); },
      error: () => { this.toast.error('Failed to submit leave request'); this.savingLeave.set(false); },
    });
  }

  setView(view: 'shifts' | 'leaves'): void {
    this.activeView.set(view);
  }

  // ── Shifts ──
  loadShifts(): void {
    this.shiftsLoading.set(true);
    const { start, end } = this.getWeekRange();
    this.schedulingService.listShifts({ startDate: start, endDate: end }).subscribe({
      next: (data) => {
        this.shifts.set(data);
        this.shiftsLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load shifts');
        this.shifts.set([]);
        this.shiftsLoading.set(false);
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
    return { start: this.toISODate(monday), end: this.toISODate(sunday) };
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

  // ── Leaves ──
  loadLeaves(): void {
    this.leavesLoading.set(true);
    this.schedulingService.listLeaves().subscribe({
      next: (data) => {
        this.leaves.set(data);
        this.leavesLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load leave requests');
        this.leaves.set([]);
        this.leavesLoading.set(false);
      },
    });
  }

  setLeaveFilter(filter: 'ALL' | StaffLeaveStatus): void {
    this.leaveFilter.set(filter);
  }

  filteredLeaves(): StaffLeaveResponse[] {
    const f = this.leaveFilter();
    if (f === 'ALL') return this.leaves();
    return this.leaves().filter((l) => l.status === f);
  }

  get pendingCount(): number {
    return this.leaves().filter((l) => l.status === 'PENDING').length;
  }

  get approvedCount(): number {
    return this.leaves().filter((l) => l.status === 'APPROVED').length;
  }

  // ── Formatting ──
  formatTime(time?: string): string {
    if (!time) return '—';
    const [h, m] = time.split(':').map(Number);
    const ampm = h >= 12 ? 'PM' : 'AM';
    const hr = h % 12 || 12;
    return `${hr}:${m.toString().padStart(2, '0')} ${ampm}`;
  }

  formatDate(date?: string): string {
    if (!date) return '—';
    return new Date(date + 'T00:00:00').toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  }

  formatShortDate(date: string): string {
    return new Date(date + 'T00:00:00').toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
    });
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

  getInitials(name: string): string {
    if (!name) return '??';
    const parts = name.trim().split(/\s+/);
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
    return name.substring(0, 2).toUpperCase();
  }

  private toISODate(d: Date): string {
    return d.toISOString().split('T')[0];
  }
}
