import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { MyRemindersComponent } from './my-reminders';
import { PatientPortalService, PortalHealthReminder } from '../../services/patient-portal.service';

const mockReminder = (overrides: Partial<PortalHealthReminder> = {}): PortalHealthReminder => ({
  id: 'r1',
  type: 'ANNUAL_PHYSICAL',
  typeLabel: 'ANNUAL PHYSICAL',
  dueDate: '2024-12-01',
  status: 'PENDING',
  overdue: false,
  notes: '',
  completedDate: '',
  completedBy: '',
  createdAt: '2024-01-01T00:00:00',
  ...overrides,
});

describe('MyRemindersComponent', () => {
  let component: MyRemindersComponent;
  let fixture: ComponentFixture<MyRemindersComponent>;
  let portalSpy: jasmine.SpyObj<PatientPortalService>;

  beforeEach(async () => {
    portalSpy = jasmine.createSpyObj('PatientPortalService', [
      'getMyHealthReminders',
      'completeHealthReminder',
    ]);
    portalSpy.getMyHealthReminders.and.returnValue(of([]));
    portalSpy.completeHealthReminder.and.returnValue(of(mockReminder({ status: 'COMPLETED' })));

    await TestBed.configureTestingModule({
      imports: [MyRemindersComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PatientPortalService, useValue: portalSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyRemindersComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no reminders', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.portal-empty')).toBeTruthy();
  });

  it('should load reminders on init', () => {
    expect(portalSpy.getMyHealthReminders).toHaveBeenCalled();
  });

  it('should display reminders when returned', () => {
    portalSpy.getMyHealthReminders.and.returnValue(of([mockReminder()]));
    component.ngOnInit();
    fixture.detectChanges();
    expect(component.reminders().length).toBe(1);
  });

  it('isDone should return true for COMPLETED status', () => {
    expect(component.isDone(mockReminder({ status: 'COMPLETED' }))).toBeTrue();
  });

  it('isDone should return true for DISMISSED status', () => {
    expect(component.isDone(mockReminder({ status: 'DISMISSED' }))).toBeTrue();
  });

  it('isDone should return false for PENDING status', () => {
    expect(component.isDone(mockReminder({ status: 'PENDING' }))).toBeFalse();
  });

  it('isDone should return false for OVERDUE status', () => {
    expect(component.isDone(mockReminder({ status: 'OVERDUE', overdue: true }))).toBeFalse();
  });

  it('should call completeHealthReminder on complete()', () => {
    const r = mockReminder();
    component.complete(r);
    expect(portalSpy.completeHealthReminder).toHaveBeenCalledWith(r.id);
  });

  it('should not call complete again if already completing', () => {
    component['completing'].set('r1');
    component.complete(mockReminder());
    expect(portalSpy.completeHealthReminder).not.toHaveBeenCalled();
  });

  it('should update reminder to COMPLETED after complete() succeeds', () => {
    const r = mockReminder();
    portalSpy.getMyHealthReminders.and.returnValue(of([r]));
    component.ngOnInit();
    fixture.detectChanges();

    component.complete(r);
    fixture.detectChanges();

    const updated = component.reminders().find((x) => x.id === r.id);
    expect(updated?.status).toBe('COMPLETED');
  });

  it('should set loading false after data loads', () => {
    expect(component.loading()).toBeFalse();
  });

  it('should set loading false on error', () => {
    portalSpy.getMyHealthReminders.and.returnValue(throwError(() => new Error('err')));
    component.ngOnInit();
    fixture.detectChanges();
    expect(component.loading()).toBeFalse();
  });

  it('should clear completing after completion', () => {
    component.complete(mockReminder());
    expect(component['completing']()).toBeNull();
  });
});
