import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { AnnouncementListComponent } from './announcement-list';
import { AnnouncementResponse, AnnouncementService } from '../services/announcement.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

describe('AnnouncementListComponent: who may change an announcement', () => {
  const own: AnnouncementResponse = { id: 'a-1', text: 'Own', date: '2026-11-27T10:00:00' };
  const platform: AnnouncementResponse = {
    id: 'a-2',
    text: 'Platform',
    date: '2026-11-27T09:00:00',
    platformWide: true,
  };

  let fixture: ComponentFixture<AnnouncementListComponent>;
  const superAdmin = signal(false);
  const globalView = signal(false);

  function render(roles: string[]): HTMLElement {
    TestBed.configureTestingModule({
      imports: [AnnouncementListComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: AnnouncementService,
          useValue: { list: () => of([own, platform]) },
        },
        { provide: AuthService, useValue: { getUserProfile: () => ({ roles }) } },
        { provide: RoleContextService, useValue: { isSuperAdmin: superAdmin, globalView } },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    });
    fixture = TestBed.createComponent(AnnouncementListComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function editButtons(host: HTMLElement): string[] {
    return Array.from(host.querySelectorAll('.announcement-card')).map(
      (card) =>
        `${card.querySelector('.announcement-text')?.textContent?.trim()}:${
          card.querySelectorAll('.action-link').length
        }`,
    );
  }

  beforeEach(() => {
    superAdmin.set(false);
    globalView.set(false);
  });

  it("a hospital administrator changes the hospital's own, not the platform's", () => {
    const host = render(['ROLE_HOSPITAL_ADMIN']);
    expect(editButtons(host)).toEqual(['Own:2', 'Platform:0']);
    expect(host.querySelectorAll('[data-testid="announcement-platform-wide"]').length).toBe(1);
  });

  it('a platform administrator in global view changes the platform one', () => {
    superAdmin.set(true);
    globalView.set(true);
    const host = render(['ROLE_SUPER_ADMIN']);
    expect(editButtons(host)).toEqual(['Own:2', 'Platform:2']);
  });

  it('a platform administrator working at a hospital does not', () => {
    superAdmin.set(true);
    const host = render(['ROLE_SUPER_ADMIN']);
    expect(editButtons(host)).toEqual(['Own:2', 'Platform:0']);
  });
});
