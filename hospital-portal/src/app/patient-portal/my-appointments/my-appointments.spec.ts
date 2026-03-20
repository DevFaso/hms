import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyAppointmentsComponent } from './my-appointments';

describe('MyAppointmentsComponent', () => {
  let component: MyAppointmentsComponent;
  let fixture: ComponentFixture<MyAppointmentsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyAppointmentsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyAppointmentsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show loading initially', () => {
    expect(component.loading()).toBeTrue();
  });

  it('cancelTarget is null initially', () => {
    expect(component.cancelTarget()).toBeNull();
  });

  it('rescheduleTarget is null initially', () => {
    expect(component.rescheduleTarget()).toBeNull();
  });

  it('openCancelModal sets cancelTarget', () => {
    const appt = { id: '1', status: 'SCHEDULED' } as never;
    component.openCancelModal(appt);
    expect(component.cancelTarget()?.id).toBe('1');
  });

  it('closeCancelModal clears cancelTarget', () => {
    const appt = { id: '1', status: 'SCHEDULED' } as never;
    component.openCancelModal(appt);
    component.closeCancelModal();
    expect(component.cancelTarget()).toBeNull();
  });

  it('openRescheduleModal sets rescheduleTarget', () => {
    const appt = {
      id: '2',
      status: 'CONFIRMED',
      date: '2026-05-01',
      startTime: '09:00',
      endTime: '09:30',
    } as never;
    component.openRescheduleModal(appt);
    expect(component.rescheduleTarget()?.id).toBe('2');
  });

  it('closeRescheduleModal clears rescheduleTarget', () => {
    const appt = {
      id: '2',
      status: 'CONFIRMED',
      date: '2026-05-01',
      startTime: '09:00',
      endTime: '09:30',
    } as never;
    component.openRescheduleModal(appt);
    component.closeRescheduleModal();
    expect(component.rescheduleTarget()).toBeNull();
  });

  it('canCancel returns true for SCHEDULED', () => {
    const appt = { id: '1', status: 'SCHEDULED' } as never;
    expect(component.canCancel(appt)).toBeTrue();
  });

  it('canCancel returns false for COMPLETED', () => {
    const appt = { id: '1', status: 'COMPLETED' } as never;
    expect(component.canCancel(appt)).toBeFalse();
  });

  it('canReschedule returns true for CONFIRMED', () => {
    const appt = { id: '1', status: 'CONFIRMED' } as never;
    expect(component.canReschedule(appt)).toBeTrue();
  });

  // ── Booking modal tests ─────────────────────────────────────────────
  it('should not show booking form initially', () => {
    expect(component.showBookingForm()).toBe(false);
  });

  it('should show booking form when openBookingForm is called', () => {
    component.openBookingForm();
    expect(component.showBookingForm()).toBe(true);
  });

  it('should hide booking form when closeBookingForm is called', () => {
    component.openBookingForm();
    component.closeBookingForm();
    expect(component.showBookingForm()).toBe(false);
  });

  it('should reset booking form when opening', () => {
    component.updateBookingField('reason', 'Test');
    component.openBookingForm();
    expect(component.bookingForm().reason).toBe('');
  });

  it('should update booking form fields', () => {
    component.updateBookingField('reason', 'Annual checkup');
    expect(component.bookingForm().reason).toBe('Annual checkup');
  });

  it('isBookingValid returns false when form is empty', () => {
    expect(component.isBookingValid()).toBe(false);
  });

  it('isBookingValid returns true when required fields are filled', () => {
    component.updateBookingField('departmentId', 'dept-1');
    component.updateBookingField('appointmentDate', '2026-06-01');
    component.updateBookingField('startTime', '09:00');
    component.updateBookingField('endTime', '09:30');
    component.updateBookingField('reason', 'Checkup');
    expect(component.isBookingValid()).toBe(true);
  });

  it('should not book when form is invalid', () => {
    component.confirmBooking();
    expect(component.booking()).toBe(false);
  });

  it('should start booking false', () => {
    expect(component.booking()).toBe(false);
  });

  it('should start with empty departments', () => {
    expect(component.departments().length).toBe(0);
  });

  it('should start with empty providers', () => {
    expect(component.providers().length).toBe(0);
  });
});
