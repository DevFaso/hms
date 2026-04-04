import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MyNotificationsComponent } from './my-notifications.component';
import { PatientPortalService } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

const makeNotification = (id: string, read: boolean) => ({
  id,
  message: `Notification ${id}`,
  type: 'APPOINTMENT_REMINDER',
  read,
  recipientUsername: 'patient1',
  createdAt: '2025-01-15T10:00:00',
});

describe('MyNotificationsComponent', () => {
  let component: MyNotificationsComponent;
  let fixture: ComponentFixture<MyNotificationsComponent>;
  let portalService: jasmine.SpyObj<PatientPortalService>;
  let toastService: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    portalService = jasmine.createSpyObj('PatientPortalService', [
      'getMyNotifications',
      'getUnreadNotificationCount',
      'markNotificationRead',
      'markAllNotificationsRead',
    ]);
    toastService = jasmine.createSpyObj('ToastService', ['success', 'error']);

    portalService.getMyNotifications.and.returnValue(
      of({
        content: [makeNotification('n1', false), makeNotification('n2', true)],
        totalElements: 2,
      }),
    );
    portalService.getUnreadNotificationCount.and.returnValue(of(1));
    portalService.markNotificationRead.and.returnValue(of(void 0));
    portalService.markAllNotificationsRead.and.returnValue(of(1));

    await TestBed.configureTestingModule({
      imports: [MyNotificationsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: portalService },
        { provide: ToastService, useValue: toastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyNotificationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load notifications on init', () => {
    expect(portalService.getMyNotifications).toHaveBeenCalled();
    expect(component.notifications().length).toBe(2);
    expect(component.unreadCount()).toBe(1);
    expect(component.loading()).toBeFalse();
  });

  it('should show error toast when load fails', () => {
    portalService.getMyNotifications.and.returnValue(throwError(() => new Error('fail')));
    component.loadNotifications();
    expect(toastService.error).toHaveBeenCalled();
    expect(component.loading()).toBeFalse();
  });

  it('should switch tab and reload notifications', () => {
    portalService.getMyNotifications.calls.reset();
    component.switchTab('unread');
    expect(component.activeTab()).toBe('unread');
    expect(portalService.getMyNotifications).toHaveBeenCalledWith(false, 0, 50);
  });

  it('should mark a notification as read and decrement unread count', () => {
    const unread = makeNotification('n1', false);
    component.notifications.set([unread]);
    component.unreadCount.set(1);

    component.markRead(unread);

    expect(portalService.markNotificationRead).toHaveBeenCalledWith('n1');
    expect(component.notifications()[0].read).toBeTrue();
    expect(component.unreadCount()).toBe(0);
  });

  it('should not call markNotificationRead if already read', () => {
    const read = makeNotification('n2', true);
    component.markRead(read);
    expect(portalService.markNotificationRead).not.toHaveBeenCalled();
  });

  it('should show error toast when markRead fails', () => {
    portalService.markNotificationRead.and.returnValue(throwError(() => new Error('fail')));
    const unread = makeNotification('n1', false);
    component.markRead(unread);
    expect(toastService.error).toHaveBeenCalled();
  });

  it('should mark all as read and reset unread count', () => {
    component.notifications.set([makeNotification('n1', false), makeNotification('n2', false)]);
    component.unreadCount.set(2);

    component.markAllRead();

    expect(portalService.markAllNotificationsRead).toHaveBeenCalled();
    expect(component.notifications().every((n) => n.read)).toBeTrue();
    expect(component.unreadCount()).toBe(0);
    expect(toastService.success).toHaveBeenCalled();
    expect(component.markingAll()).toBeFalse();
  });

  it('should show error toast when markAllRead fails', () => {
    portalService.markAllNotificationsRead.and.returnValue(throwError(() => new Error('fail')));
    component.markAllRead();
    expect(toastService.error).toHaveBeenCalled();
    expect(component.markingAll()).toBeFalse();
  });
});
