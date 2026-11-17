import {
  Component,
  ElementRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { debounceTime, Subject, switchMap, of } from 'rxjs';
import {
  StaffSchedulingService,
  StaffShiftResponse,
  StaffShiftRequest,
  StaffLeaveResponse,
  StaffLeaveStatus,
  BulkShiftRequest,
  BulkShiftResult,
  BulkShiftSkip,
  DayOfWeek,
} from '../services/staff-scheduling.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { RoleLabelPipe } from '../shared/pipes/role-label.pipe';
import { currentLocale, formatClockTime } from '../shared/i18n/app-locale';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { refuseFields } from '../shared/forms/invalid-fields';
import { missingRequiredMessage } from '../shared/required-fields';
import { refusalReason, refusedFieldReasons } from '../shared/refusal-reason';
import { ListEscapeDirective } from '../shared/a11y/list-escape.directive';
@Component({
  selector: 'app-scheduling',
  standalone: true,
  imports: [
    ListEscapeDirective,
    SubmitGuardDirective,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    RoleLabelPipe,
    ModalDialogDirective,
  ],
  templateUrl: './scheduling.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './scheduling.scss',
})
export class SchedulingComponent implements OnInit {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly shiftFields: Record<string, string> = {
    staffId: 'bulk-staffSearch',
    hospitalId: 'bulk-hospitalId',
    shiftDate: 'bulk-startDate',
    startTime: 'bulk-startTime',
    endTime: 'bulk-endTime',
    shiftType: 'bulk-shiftType',
    notes: 'bulk-notes',
  };
  private readonly bulkFields: Record<string, string> = {
    ...this.shiftFields,
    startDate: 'bulk-startDate',
    endDate: 'bulk-endDate',
    daysOfWeek: 'bulk-daysOfWeek',
  };
  private readonly schedulingService = inject(StaffSchedulingService);
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);
  private readonly permissions = inject(PermissionService);
  private readonly translate = inject(TranslateService);

  /** True for admin/clinician roles that can manage shifts, leaves & bulk scheduling. */
  readonly isSchedulingAdmin = this.permissions.hasAnyPermission(
    'Manage Staff',
    'Manage Staff Schedules',
  );

  /**
   * QA D878: bulk planning is offered only when the server says this caller's
   * writes succeed (an administrator of the acting hospital, or a department
   * head). False until it answers, and on any failure: never a button whose
   * POST is refused.
   */
  readonly canManageSchedules = signal(false);

  activeView = signal<'shifts' | 'leaves'>('shifts');

  // Shifts
  shifts = signal<StaffShiftResponse[]>([]);
  shiftsLoading = signal(false);
  weekOffset = signal(0);

  // Leaves
  leaves = signal<StaffLeaveResponse[]>([]);
  leavesLoading = signal(false);
  leaveFilter = signal<'ALL' | StaffLeaveStatus>('ALL');

  // ── Bulk Schedule Modal ──────────────────────────────────────────────────
  singleShiftMode = signal(false);
  bulkModalOpen = signal(false);
  bulkSubmitting = signal(false);
  bulkResult = signal<BulkShiftResult | null>(null);

  // ── Staff search / autocomplete ──────────────────────────────────────────
  /** All loaded staff (fetched once when modal opens) */
  private allStaff: StaffResponse[] = [];
  /** The currently selected staff member */
  selectedStaff = signal<StaffResponse | null>(null);
  /** Text the user is typing in the staff search box */
  staffQuery = signal('');
  /** Debounce: staff suggestions shown under the input */
  staffSuggestions = signal<StaffResponse[]>([]);
  staffSuggestionsOpen = signal(false);
  staffLoading = signal(false);

  private readonly staffSearch$ = new Subject<string>();

  /** Initialise the debounced search pipeline once */
  private initStaffSearch(): void {
    this.staffSearch$
      .pipe(
        debounceTime(220),
        switchMap((q) => {
          const query = q.trim().toLowerCase();
          if (!query) return of([]);
          return of(
            this.allStaff.filter(
              (s) =>
                s.name.toLowerCase().includes(query) ||
                (s.jobTitle ?? '').toLowerCase().includes(query) ||
                (s.hospitalName ?? '').toLowerCase().includes(query) ||
                (s.departmentName ?? '').toLowerCase().includes(query),
            ),
          );
        }),
      )
      .subscribe((results) => {
        this.staffSuggestions.set(results.slice(0, 8));
        this.staffSuggestionsOpen.set(results.length > 0);
        this.staffLoading.set(false);
      });
  }

  onStaffQueryChange(value: string): void {
    this.staffQuery.set(value);
    if (!value.trim()) {
      this.staffSuggestions.set([]);
      this.staffSuggestionsOpen.set(false);
      return;
    }
    this.staffLoading.set(true);
    this.staffSearch$.next(value);
  }

  selectStaff(staff: StaffResponse): void {
    this.selectedStaff.set(staff);
    this.bulk.staffId = staff.id;
    this.bulk.hospitalId = staff.hospitalId;
    this.staffQuery.set('');
    this.staffSuggestions.set([]);
    this.staffSuggestionsOpen.set(false);
  }

  clearStaff(): void {
    this.selectedStaff.set(null);
    this.bulk.staffId = '';
    this.bulk.hospitalId = '';
    this.staffQuery.set('');
  }

  closeSuggestions(): void {
    // Small delay so a click on a suggestion registers first
    setTimeout(() => this.staffSuggestionsOpen.set(false), 150);
  }

  staffInitials(staff: StaffResponse): string {
    return this.getInitials(staff.name);
  }

  /** Backing model for the bulk scheduling form */
  bulk: BulkShiftRequest = {
    staffId: '',
    hospitalId: '',
    departmentId: undefined,
    startDate: '',
    endDate: '',
    daysOfWeek: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'],
    startTime: '18:00',
    endTime: '01:00',
    shiftType: 'NIGHT',
    notes: '',
    skipConflicts: true,
  };

  readonly allDays: DayOfWeek[] = [
    'MONDAY',
    'TUESDAY',
    'WEDNESDAY',
    'THURSDAY',
    'FRIDAY',
    'SATURDAY',
    'SUNDAY',
  ];

  openShiftModal(
    date = this.weekOffset() === 0 ? this.toISODate(new Date()) : this.getWeekRange().start,
  ): void {
    if (!this.canManageSchedules() || this.bulkSubmitting()) return;
    this.openBulkModal();
    this.singleShiftMode.set(true);
    this.bulk.startDate = date;
    this.bulk.endDate = date;
    this.bulk.startTime = '08:00';
    this.bulk.endTime = '16:00';
    this.bulk.shiftType = 'MORNING';
    this.bulk.notes = '';
  }

  openBulkModal(): void {
    if (!this.canManageSchedules()) return;
    this.singleShiftMode.set(false);
    this.bulkResult.set(null);
    this.clearStaff();
    this.bulkModalOpen.set(true);
    // Load staff list once (only if not already loaded)
    if (this.allStaff.length === 0) {
      this.staffLoading.set(true);
      this.staffService.list().subscribe({
        next: (staff) => {
          this.allStaff = staff;
          this.staffLoading.set(false);
        },
        error: () => {
          this.staffLoading.set(false);
          this.toast.error(this.translate.instant('SCHEDULING.TOAST.STAFF_LOAD_FAILED'));
        },
      });
    }
  }

  closeBulkModal(): void {
    if (this.bulkSubmitting()) return;
    this.bulkModalOpen.set(false);
  }

  /**
   * QA D2124: the weekday chips read « lun. mar. … » in French, not the enum's
   * « MON TUE ». The locale names the day, as the week header does (QA D348).
   */
  dayChipLabel(day: DayOfWeek, width: 'short' | 'long' = 'short'): string {
    // 1 January 2024 was a Monday: allDays' order from there.
    const date = new Date(2024, 0, 1 + this.allDays.indexOf(day));
    return date.toLocaleDateString(currentLocale(), { weekday: width });
  }

  isDaySelected(day: DayOfWeek): boolean {
    return this.bulk.daysOfWeek.includes(day);
  }

  toggleDay(day: DayOfWeek): void {
    if (this.isDaySelected(day)) {
      this.bulk.daysOfWeek = this.bulk.daysOfWeek.filter((d) => d !== day);
    } else {
      this.bulk.daysOfWeek = [...this.bulk.daysOfWeek, day];
    }
  }

  selectWeekdays(): void {
    this.bulk.daysOfWeek = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'];
  }

  selectWeekend(): void {
    this.bulk.daysOfWeek = ['SATURDAY', 'SUNDAY'];
  }

  selectAllDays(): void {
    this.bulk.daysOfWeek = [...this.allDays];
  }

  submitShift(): void {
    if (!this.canManageSchedules() || this.bulkSubmitting()) return;
    if (!this.bulk.staffId || !this.bulk.hospitalId) {
      refuseFields(
        this.host.nativeElement,
        // QA D2128: the hospital follows the staff pick, so the staff field is the one to fix.
        ['bulk-staffSearch'],
        Object.values(this.shiftFields),
        this.translate.instant('SCHEDULING.TOAST.IDS_REQUIRED'),
      );
      this.toast.error(this.translate.instant('SCHEDULING.TOAST.IDS_REQUIRED'));
      return;
    }
    if (
      !this.bulk.startDate ||
      !this.bulk.startTime ||
      !this.bulk.endTime ||
      this.bulk.startTime === this.bulk.endTime
    ) {
      const invalid = [
        !this.bulk.startDate ? 'bulk-startDate' : '',
        !this.bulk.startTime ? 'bulk-startTime' : '',
        !this.bulk.endTime || this.bulk.startTime === this.bulk.endTime ? 'bulk-endTime' : '',
      ].filter(Boolean);
      const message =
        missingRequiredMessage(this.translate, [
          {
            labelKey: this.singleShiftMode() ? 'SCHEDULING.DAY' : 'SCHEDULING.START_DATE',
            value: this.bulk.startDate,
          },
          { labelKey: 'SCHEDULING.START_TIME', value: this.bulk.startTime },
          { labelKey: 'SCHEDULING.END_TIME', value: this.bulk.endTime },
        ]) ?? this.translate.instant('SCHEDULING.TOAST.SHIFT_TIME_RANGE');
      refuseFields(this.host.nativeElement, invalid, Object.values(this.shiftFields), message);
      this.toast.error(message);
      return;
    }
    const request: StaffShiftRequest = {
      staffId: this.bulk.staffId,
      hospitalId: this.bulk.hospitalId,
      shiftDate: this.bulk.startDate,
      startTime: this.bulk.startTime,
      endTime: this.bulk.endTime,
      shiftType: this.bulk.shiftType,
      notes: this.bulk.notes,
    };
    this.bulkSubmitting.set(true);
    this.schedulingService.createShift(request).subscribe({
      next: () => {
        this.bulkSubmitting.set(false);
        this.closeBulkModal();
        this.toast.success(this.translate.instant('SCHEDULING.TOAST.SHIFT_CREATED'));
        this.loadShifts();
      },
      error: (err) => {
        this.bulkSubmitting.set(false);
        const reasons = refusedFieldReasons(err, this.shiftFields);
        refuseFields(
          this.host.nativeElement,
          reasons.map(([id, message]) => ({ id, message })),
          Object.values(this.shiftFields),
        );
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('SCHEDULING.TOAST.SHIFT_FAILED'),
        );
      },
    });
  }

  submitBulk(): void {
    if (!this.canManageSchedules() || this.bulkSubmitting()) return;
    if (!this.bulk.staffId || !this.bulk.hospitalId) {
      refuseFields(
        this.host.nativeElement,
        // QA D2128: the hospital follows the staff pick, so the staff field is the one to fix.
        ['bulk-staffSearch'],
        Object.values(this.bulkFields),
        this.translate.instant('SCHEDULING.TOAST.IDS_REQUIRED'),
      );
      this.toast.error(this.translate.instant('SCHEDULING.TOAST.IDS_REQUIRED'));
      return;
    }
    if (!this.bulk.startDate || !this.bulk.endDate) {
      refuseFields(
        this.host.nativeElement,
        [
          !this.bulk.startDate ? 'bulk-startDate' : '',
          !this.bulk.endDate ? 'bulk-endDate' : '',
        ].filter(Boolean),
        Object.values(this.bulkFields),
        this.translate.instant('SCHEDULING.TOAST.DATE_RANGE_REQUIRED'),
      );
      this.toast.error(this.translate.instant('SCHEDULING.TOAST.DATE_RANGE_REQUIRED'));
      return;
    }
    if (this.bulk.daysOfWeek.length === 0) {
      refuseFields(
        this.host.nativeElement,
        ['bulk-daysOfWeek'],
        Object.values(this.bulkFields),
        this.translate.instant('SCHEDULING.TOAST.DOW_REQUIRED'),
      );
      this.toast.error(this.translate.instant('SCHEDULING.TOAST.DOW_REQUIRED'));
      return;
    }
    this.bulkSubmitting.set(true);
    this.bulkResult.set(null);
    this.schedulingService.bulkScheduleShifts(this.bulk).subscribe({
      next: (result) => {
        this.bulkResult.set(result);
        this.bulkSubmitting.set(false);
        if (result.totalScheduled > 0) {
          const baseMsg = this.translate.instant('SCHEDULING.TOAST.BULK_SUCCESS', {
            count: result.totalScheduled,
          });
          const skippedSuffix =
            result.totalSkipped > 0
              ? this.translate.instant('SCHEDULING.TOAST.BULK_SKIPPED_SUFFIX', {
                  count: result.totalSkipped,
                })
              : '.';
          this.toast.success(baseMsg + skippedSuffix);
          this.loadShifts();
        } else {
          this.toast.error(this.translate.instant('SCHEDULING.TOAST.BULK_ALL_SKIPPED'));
        }
      },
      error: (err) => {
        this.bulkSubmitting.set(false);
        const reasons = refusedFieldReasons(err, this.bulkFields);
        refuseFields(
          this.host.nativeElement,
          reasons.map(([id, message]) => ({ id, message })),
          Object.values(this.bulkFields),
        );
        const msg = refusalReason(err) ?? this.translate.instant('SCHEDULING.TOAST.BULK_FAILED');
        this.toast.error(msg);
      },
    });
  }

  formatBulkSkipDate(skip: BulkShiftSkip): string {
    return this.formatDate(skip.date) + ' — ' + skip.reason;
  }
  // ── End Bulk Modal ────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.initStaffSearch();
    this.loadShifts();
    if (this.isSchedulingAdmin) {
      this.loadLeaves();
      this.schedulingService.capabilities().subscribe({
        next: (caps) => this.canManageSchedules.set(caps?.canManageSchedules === true),
        error: () => this.canManageSchedules.set(false),
      });
    }
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
        this.toast.error(this.translate.instant('SCHEDULING.TOAST.SHIFTS_LOAD_FAILED'));
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
    // QA D348: the locale knows its weekday abbreviations (staff-detail already did this).
    const locale = currentLocale();
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(monday);
      d.setDate(monday.getDate() + i);
      const date = this.toISODate(d);
      return {
        label: d.toLocaleDateString(locale, { weekday: 'short' }),
        date,
        isToday: date === today,
      };
    });
  }

  getShiftsForDay(date: string): StaffShiftResponse[] {
    return this.shifts().filter(
      (s) =>
        s.shiftDate === date ||
        // Cross-midnight: also show on the end-date column so the viewer can
        // see that the shift is still running at the start of that day.
        (s.crossMidnight && s.shiftEndDate === date),
    );
  }

  getWeekLabel(): string {
    const { start, end } = this.getWeekRange();
    const s = new Date(start + 'T00:00:00');
    const e = new Date(end + 'T00:00:00');
    const opts: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric' };
    return `${s.toLocaleDateString(currentLocale(), opts)} – ${e.toLocaleDateString(currentLocale(), { ...opts, year: 'numeric' })}`;
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
        this.toast.error(this.translate.instant('SCHEDULING.TOAST.LEAVE_LOAD_FAILED'));
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
    return formatClockTime(time);
  }

  /**
   * Returns a formatted time-range string for a shift.
   * Cross-midnight end times get a "+1" suffix so the viewer knows the shift
   * ends on the following calendar day.
   * e.g.  "5:00 PM – 1:30 AM (+1)"
   */
  formatShiftRange(shift: StaffShiftResponse): string {
    const start = this.formatTime(shift.startTime);
    const end = this.formatTime(shift.endTime);
    return shift.crossMidnight ? `${start} – ${end} (+1)` : `${start} – ${end}`;
  }

  formatDate(date?: string): string {
    if (!date) return '—';
    return new Date(date + 'T00:00:00').toLocaleDateString(currentLocale(), {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  }

  formatShortDate(date: string): string {
    return new Date(date + 'T00:00:00').toLocaleDateString(currentLocale(), {
      month: 'short',
      day: 'numeric',
    });
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
    // Local-date formatting: toISOString() converts to UTC, which shifts the
    // calendar day for evening users in negative-UTC-offset timezones (week
    // rendered Tue–Mon, wrong "today" column, wrong shift query range).
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
