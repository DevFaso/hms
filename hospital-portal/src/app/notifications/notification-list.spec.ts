import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { NotificationListComponent } from './notification-list';
import { NotificationService } from '../services/notification.service';
import { ToastService } from '../core/toast.service';

it('shows a notification message once when its title is missing or repeats it', () => {
  const rows = [
    { id: '1', title: 'Same message', message: 'Same message' },
    { id: '2', title: '', message: 'Message without title' },
    { id: '3', title: 'Distinct title', message: 'Distinct details' },
  ].map((row) => ({
    ...row,
    type: 'INFO',
    read: true,
    recipientUsername: 'test',
    createdAt: '2026-11-27T10:00:00Z',
  }));
  TestBed.configureTestingModule({
    imports: [NotificationListComponent, TranslateModule.forRoot()],
    providers: [
      provideRouter([]),
      {
        provide: NotificationService,
        useValue: {
          getNotifications: () =>
            of({ content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 20 }),
        },
      },
      { provide: ToastService, useValue: { error: jasmine.createSpy('error') } },
    ],
  });
  const fixture = TestBed.createComponent(NotificationListComponent);
  fixture.detectChanges();
  const cards = fixture.nativeElement.querySelectorAll('.notif-card') as NodeListOf<HTMLElement>;
  expect(cards.length).toBe(3);
  expect(cards[0].querySelectorAll('.notif-message').length).toBe(0);
  expect(cards[0].textContent?.match(/Same message/g)?.length).toBe(1);
  expect(cards[1].textContent?.match(/Message without title/g)?.length).toBe(1);
  expect(cards[2].querySelector('.notif-message')?.textContent).toContain('Distinct details');
});
