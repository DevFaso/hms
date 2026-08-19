import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { SchedulingComponent } from './scheduling';
import {
  StaffSchedulingService,
  StaffShiftResponse,
  StaffLeaveResponse,
  BulkShiftResult,
} from '../services/staff-scheduling.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';

function mockShift(overrides: Partial<StaffShiftResponse> = {}): StaffShiftResponse {
  return {
    id: 's1',
    staffId: 'st1',
    staffName: 'Ama Owusu',
    hospitalId: 'h1',
    shiftDate: '2026-08-17',
    startTime: '08:00',
    endTime: '16:00',
    shiftType: 'MORNING',
    status: 'SCHEDULED',
    crossMidnight: false,
    ...overrides,
  } as StaffShiftResponse;
}

function mockLeave(overrides: Partial<StaffLeaveResponse> = {}): StaffLeaveResponse {
  return {
    id: 'l1',
    staffId: 'st1',
    staffName: 'Ama Owusu',
    leaveType: 'VACATION',
    status: 'PENDING',
    startDate: '2026-09-01',
    endDate: '2026-09-05',
    ...overrides,
  } as StaffLeaveResponse;
}

describe('SchedulingComponent', () => {
  let component: SchedulingComponent;
  let fixture: ComponentFixture<SchedulingComponent>;
  let schedulingSpy: jasmine.SpyObj<StaffSchedulingService>;
  let staffSpy: jasmine.SpyObj<StaffService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    schedulingSpy = jasmine.createSpyObj('StaffSchedulingService', [
      'listShifts',
      'listLeaves',
      'bulkScheduleShifts',
    ]);
    schedulingSpy.listShifts.and.returnValue(of([mockShift()]));
    schedulingSpy.listLeaves.and.returnValue(
      of([mockLeave(), mockLeave({ id: 'l2', status: 'APPROVED' })]),
    );
    staffSpy = jasmine.createSpyObj('StaffService', ['list']);
    staffSpy.list.and.returnValue(
      of([
        {
          id: 'st1',
          name: 'Ama Owusu',
          jobTitle: 'Nurse',
          hospitalId: 'h1',
          hospitalName: 'City Hospital',
        } as StaffResponse,
      ]),
    );
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const permissionSpy = jasmine.createSpyObj('PermissionService', ['hasAnyPermission']);
    permissionSpy.hasAnyPermission.and.returnValue(true);

    await TestBed.configureTestingModule({
      imports: [SchedulingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: StaffSchedulingService, useValue: schedulingSpy },
        { provide: StaffService, useValue: staffSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: PermissionService, useValue: permissionSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SchedulingComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('loads shifts and leaves on init for scheduling admins', () => {
    fixture.detectChanges();
    expect(schedulingSpy.listShifts).toHaveBeenCalled();
    expect(schedulingSpy.listLeaves).toHaveBeenCalled();
    expect(component.shifts().length).toBe(1);
    expect(component.leaves().length).toBe(2);
  });

  it('requests a Monday-to-Sunday week range', () => {
    fixture.detectChanges();
    const { start, end } = component.getWeekRange();
    expect(new Date(start + 'T00:00:00').getDay()).toBe(1); // Monday
    expect(new Date(end + 'T00:00:00').getDay()).toBe(0); // Sunday
  });

  it('changeWeek shifts the range and reloads', () => {
    fixture.detectChanges();
    const before = component.getWeekRange().start;
    schedulingSpy.listShifts.calls.reset();
    component.changeWeek(1);
    expect(component.weekOffset()).toBe(1);
    expect(schedulingSpy.listShifts).toHaveBeenCalled();
    const after = component.getWeekRange().start;
    expect(after > before).toBeTrue();
    component.goToCurrentWeek();
    expect(component.weekOffset()).toBe(0);
  });

  it('getShiftsForDay includes cross-midnight shifts on their end date', () => {
    component.shifts.set([
      mockShift({ shiftDate: '2026-08-17' }),
      mockShift({
        id: 's2',
        shiftDate: '2026-08-17',
        shiftEndDate: '2026-08-18',
        crossMidnight: true,
      }),
    ]);
    expect(component.getShiftsForDay('2026-08-17').length).toBe(2);
    expect(component.getShiftsForDay('2026-08-18').length).toBe(1);
    expect(component.getShiftsForDay('2026-08-19').length).toBe(0);
  });

  it('filters leaves by status and exposes pending/approved counts', () => {
    fixture.detectChanges();
    expect(component.filteredLeaves().length).toBe(2);
    component.setLeaveFilter('PENDING');
    expect(component.filteredLeaves().length).toBe(1);
    expect(component.pendingCount).toBe(1);
    expect(component.approvedCount).toBe(1);
  });

  it('day-of-week toggles and presets update the bulk form', () => {
    component.toggleDay('MONDAY');
    expect(component.isDaySelected('MONDAY')).toBeFalse();
    component.toggleDay('MONDAY');
    expect(component.isDaySelected('MONDAY')).toBeTrue();
    component.selectWeekend();
    expect(component.bulk.daysOfWeek).toEqual(['SATURDAY', 'SUNDAY']);
    component.selectAllDays();
    expect(component.bulk.daysOfWeek.length).toBe(7);
    component.selectWeekdays();
    expect(component.bulk.daysOfWeek.length).toBe(5);
  });

  it('submitBulk validates staff, date range, and days of week', () => {
    component.bulk.staffId = '';
    component.submitBulk();
    expect(toastSpy.error).toHaveBeenCalledTimes(1);

    component.bulk.staffId = 'st1';
    component.bulk.hospitalId = 'h1';
    component.bulk.startDate = '';
    component.submitBulk();
    expect(toastSpy.error).toHaveBeenCalledTimes(2);

    component.bulk.startDate = '2026-08-17';
    component.bulk.endDate = '2026-08-21';
    component.bulk.daysOfWeek = [];
    component.submitBulk();
    expect(toastSpy.error).toHaveBeenCalledTimes(3);
    expect(schedulingSpy.bulkScheduleShifts).not.toHaveBeenCalled();
  });

  it('submitBulk reports success and reloads shifts', () => {
    fixture.detectChanges();
    component.bulk.staffId = 'st1';
    component.bulk.hospitalId = 'h1';
    component.bulk.startDate = '2026-08-17';
    component.bulk.endDate = '2026-08-21';
    schedulingSpy.bulkScheduleShifts.and.returnValue(
      of({ totalScheduled: 5, totalSkipped: 1, scheduled: [], skipped: [] } as BulkShiftResult),
    );
    schedulingSpy.listShifts.calls.reset();
    component.submitBulk();
    expect(toastSpy.success).toHaveBeenCalled();
    expect(schedulingSpy.listShifts).toHaveBeenCalled();
    expect(component.bulkSubmitting()).toBeFalse();
  });

  it('submitBulk flags an all-skipped result as an error', () => {
    component.bulk.staffId = 'st1';
    component.bulk.hospitalId = 'h1';
    component.bulk.startDate = '2026-08-17';
    component.bulk.endDate = '2026-08-21';
    schedulingSpy.bulkScheduleShifts.and.returnValue(
      of({ totalScheduled: 0, totalSkipped: 3, scheduled: [], skipped: [] } as BulkShiftResult),
    );
    component.submitBulk();
    expect(toastSpy.error).toHaveBeenCalled();
  });

  it('openBulkModal loads the staff list once', () => {
    component.openBulkModal();
    expect(staffSpy.list).toHaveBeenCalledTimes(1);
    component.closeBulkModal();
    component.openBulkModal();
    expect(staffSpy.list).toHaveBeenCalledTimes(1); // cached
  });

  it('staff search debounces and filters the loaded staff', (done) => {
    fixture.detectChanges();
    component.openBulkModal();
    component.onStaffQueryChange('ama');
    setTimeout(() => {
      expect(component.staffSuggestions().length).toBe(1);
      expect(component.staffSuggestionsOpen()).toBeTrue();

      component.onStaffQueryChange('zzz');
      setTimeout(() => {
        expect(component.staffSuggestions().length).toBe(0);
        done();
      }, 300);
    }, 300);
  });

  it('selectStaff fills the bulk form; clearStaff resets it', () => {
    const staff = {
      id: 'st1',
      name: 'Ama Owusu',
      hospitalId: 'h1',
    } as StaffResponse;
    component.selectStaff(staff);
    expect(component.bulk.staffId).toBe('st1');
    expect(component.bulk.hospitalId).toBe('h1');
    component.clearStaff();
    expect(component.bulk.staffId).toBe('');
    expect(component.selectedStaff()).toBeNull();
  });

  it('formats times, ranges, and enum labels', () => {
    expect(component.formatTime('08:30')).toBe('8:30 AM');
    expect(component.formatTime('16:05')).toBe('4:05 PM');
    expect(component.formatTime('00:00')).toBe('12:00 AM');
    expect(component.formatTime(undefined)).toBe('—');
    expect(
      component.formatShiftRange(
        mockShift({ startTime: '18:00', endTime: '01:00', crossMidnight: true }),
      ),
    ).toContain('(+1)');
    expect(component.formatShiftType('NIGHT')).toBe('Night');
    expect(component.getInitials('Ama Owusu')).toBe('AO');
    expect(component.getInitials('Cher')).toBe('CH');
    expect(component.getInitials('')).toBe('??');
  });

  it('shows an error toast when shift loading fails', () => {
    schedulingSpy.listShifts.and.returnValue(throwError(() => new Error('boom')));
    fixture.detectChanges();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.shifts().length).toBe(0);
    expect(component.shiftsLoading()).toBeFalse();
  });
});
