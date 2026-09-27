import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';

import { ChatComponent } from './chat';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * #776 closed `GET /users` (the staff directory) to patients, and the
 * new-conversation picker listed it for everyone — so a patient opening the
 * picker got "Failed to load users" and could only reply to old threads.
 *
 * A patient's picker is now their care team plus the clinicians of their
 * appointments, as the native apps build it. These cases pin that a patient
 * never asks `/users`, what the list holds, and that one failing source
 * neither hides the other nor turns into "you have nobody".
 */
describe('ChatComponent new-conversation picker', () => {
  const SELF = 'user-self';
  const CARE_TEAM = '/me/patient/care-team';
  const APPOINTMENTS = '/me/patient/appointments';

  let fixture: ComponentFixture<ChatComponent>;
  let component: ChatComponent;
  let httpMock: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  let toast: jasmine.SpyObj<ToastService>;
  let roleContext: RoleContextService;

  function setUp(roles: string[], activeRole: string | null = null): void {
    auth = jasmine.createSpyObj('AuthService', ['getUserId', 'getUserProfile', 'getRoles']);
    auth.getUserId.and.returnValue(SELF);
    auth.getRoles.and.returnValue(roles);
    toast = jasmine.createSpyObj('ToastService', ['error', 'warning', 'success', 'info']);

    TestBed.configureTestingModule({
      imports: [ChatComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
      ],
    });
    roleContext = TestBed.inject(RoleContextService);
    roleContext.activeRole = activeRole;
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ChatComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    httpMock.expectOne((r) => r.url === `/chat/conversations/${SELF}`).flush([]);
  }

  afterEach(() => httpMock.verify());

  function openPicker(): void {
    component.openNewConversation();
    fixture.detectChanges();
  }

  function flushCareTeam(body: unknown): void {
    httpMock.expectOne((r) => r.url === CARE_TEAM).flush({ data: body });
  }

  function flushAppointments(rows: unknown[]): void {
    httpMock.expectOne((r) => r.url === APPOINTMENTS).flush({ data: rows });
  }

  function failRequest(url: string): void {
    httpMock
      .expectOne((r) => r.url === url)
      .flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });
  }

  function pickerText(): string {
    fixture.detectChanges();
    const panel = (fixture.nativeElement as HTMLElement).querySelector('.new-conv-panel');
    return panel?.textContent ?? '';
  }

  describe('as a patient', () => {
    beforeEach(() => setUp(['ROLE_PATIENT']));

    it('lists the care team then the appointment clinicians, deduped, without self or /users', () => {
      openPicker();
      httpMock.expectNone((r) => r.url === '/users');

      flushCareTeam({
        primaryCare: {
          id: 'pcp-1',
          doctorUserId: 'doc-a',
          doctorDisplay: 'Awa Traore',
          hospitalName: 'CHU Yalgado',
          current: true,
        },
        primaryCareHistory: [
          // the current PCP again, as the history also carries it
          { id: 'pcp-1', doctorUserId: 'doc-a', doctorDisplay: 'Awa Traore', current: true },
          { id: 'pcp-0', doctorUserId: 'doc-b', doctorDisplay: 'Boris Kabore', current: false },
          // no user id: nothing can address a message to it
          { id: 'pcp-x', doctorUserId: null, doctorDisplay: 'Ghost', current: false },
        ],
      });
      flushAppointments([
        { id: 'ap-1', staffUserId: 'doc-b', staffName: 'Boris Kabore', hospitalName: 'CMA' },
        { id: 'ap-2', staffUserId: 'doc-c', staffName: 'Chantal Ouedraogo', hospitalName: 'CMA' },
        { id: 'ap-3', staffUserId: 'doc-c', staffName: 'Chantal Ouedraogo', hospitalName: 'CMA' },
        { id: 'ap-4', staffUserId: SELF, staffName: 'Myself' },
        { id: 'ap-5', staffUserId: null, staffName: 'No user' },
        { id: 'ap-6', staffUserId: 'doc-d', staffName: '  ' },
      ]);
      httpMock.expectNone((r) => r.url === '/users');

      expect(component.availableUsers().map((u) => u.id)).toEqual(['doc-a', 'doc-b', 'doc-c']);
      expect(component.availableUsers().map((u) => u.name)).toEqual([
        'Awa Traore',
        'Boris Kabore',
        'Chantal Ouedraogo',
      ]);
      expect(component.loadingUsers()).toBeFalse();
      expect(toast.error).not.toHaveBeenCalled();
      expect(toast.warning).not.toHaveBeenCalled();
      const text = pickerText();
      expect(text).toContain('Awa Traore');
      expect(text).toContain('CHU Yalgado');
    });

    it('still shows the appointment clinicians when the care team fails', () => {
      openPicker();
      failRequest(CARE_TEAM);
      flushAppointments([{ id: 'ap-1', staffUserId: 'doc-c', staffName: 'Chantal Ouedraogo' }]);
      httpMock.expectNone((r) => r.url === '/users');

      expect(component.availableUsers().map((u) => u.id)).toEqual(['doc-c']);
      expect(toast.warning).toHaveBeenCalledOnceWith('CHAT.RECIPIENTS_PARTIAL_LOAD');
      expect(toast.error).not.toHaveBeenCalled();
      expect(component.noRecipientsYet()).toBeFalse();
    });

    it('still shows the care team when the appointments fail', () => {
      openPicker();
      flushCareTeam({
        primaryCare: { id: 'pcp-1', doctorUserId: 'doc-a', doctorDisplay: 'Awa Traore' },
        primaryCareHistory: [],
      });
      failRequest(APPOINTMENTS);

      expect(component.availableUsers().map((u) => u.id)).toEqual(['doc-a']);
      expect(toast.warning).toHaveBeenCalledOnceWith('CHAT.RECIPIENTS_PARTIAL_LOAD');
      expect(toast.error).not.toHaveBeenCalled();
    });

    it('a partial failure with nothing loaded does not claim the patient has nobody', () => {
      openPicker();
      failRequest(CARE_TEAM);
      flushAppointments([]);

      expect(component.noRecipientsYet()).toBeFalse();
      expect(pickerText()).toContain('CHAT.NO_USERS_FOUND');
      expect(pickerText()).not.toContain('CHAT.NO_MESSAGE_RECIPIENTS');
    });

    it('shows the empty state, not an error, when both sources are empty', () => {
      openPicker();
      flushCareTeam({ primaryCare: null, primaryCareHistory: [] });
      flushAppointments([]);

      expect(component.availableUsers()).toEqual([]);
      expect(component.noRecipientsYet()).toBeTrue();
      expect(toast.error).not.toHaveBeenCalled();
      expect(toast.warning).not.toHaveBeenCalled();
      const text = pickerText();
      expect(text).toContain('CHAT.NO_MESSAGE_RECIPIENTS');
      expect(text).not.toContain('CHAT.NO_USERS_FOUND');
    });

    it('reports an error, not an empty care team, when both sources fail', () => {
      openPicker();
      failRequest(CARE_TEAM);
      failRequest(APPOINTMENTS);

      expect(toast.error).toHaveBeenCalledOnceWith('CHAT.RECIPIENTS_LOAD_FAILED');
      expect(component.noRecipientsYet()).toBeFalse();
      expect(pickerText()).not.toContain('CHAT.NO_MESSAGE_RECIPIENTS');
    });

    it('searches the clinicians by name and hospital', () => {
      openPicker();
      flushCareTeam({
        primaryCare: {
          id: 'p',
          doctorUserId: 'doc-a',
          doctorDisplay: 'Awa Traore',
          hospitalName: 'CHU Yalgado',
        },
        primaryCareHistory: [],
      });
      flushAppointments([
        { id: 'ap', staffUserId: 'doc-c', staffName: 'Chantal Ouedraogo', hospitalName: 'CMA' },
      ]);

      component.userSearchTerm.set('yalgado');
      expect(component.filteredUsers().map((u) => u.id)).toEqual(['doc-a']);
      component.userSearchTerm.set('chantal');
      expect(component.filteredUsers().map((u) => u.id)).toEqual(['doc-c']);
    });

    it('opens a thread addressed to the clinician user id', () => {
      openPicker();
      flushCareTeam({ primaryCare: null, primaryCareHistory: [] });
      flushAppointments([{ id: 'ap', staffUserId: 'doc-c', staffName: 'Chantal Ouedraogo' }]);

      component.startConversationWith(component.availableUsers()[0]);
      httpMock.expectOne((r) => r.url === `/chat/history/${SELF}/doc-c`).flush([]);
      httpMock.expectOne((r) => r.url === `/chat/mark-read/doc-c/${SELF}`).flush(null);

      expect(component.activeConversation()?.conversationUserId).toBe('doc-c');
      expect(component.activeConversation()?.conversationUserName).toBe('Chantal Ouedraogo');
    });
  });

  it('uses the patient picker for a dual-role user acting as the patient', () => {
    setUp(['ROLE_NURSE', 'ROLE_PATIENT'], 'ROLE_PATIENT');
    openPicker();
    httpMock.expectNone((r) => r.url === '/users');
    flushCareTeam({ primaryCare: null, primaryCareHistory: [] });
    flushAppointments([]);
    expect(component.noRecipientsYet()).toBeTrue();
  });

  describe('as staff', () => {
    it('lists the user directory and never the patient endpoints', () => {
      setUp(['ROLE_DOCTOR']);
      openPicker();
      httpMock.expectNone((r) => r.url === CARE_TEAM || r.url === APPOINTMENTS);
      httpMock
        .expectOne((r) => r.url === '/users')
        .flush({
          content: [
            {
              id: SELF,
              username: 'me',
              email: 'me@x',
              firstName: 'Me',
              lastName: 'Self',
              roleName: 'ROLE_DOCTOR',
            },
            {
              id: 'n1',
              username: 'nurse1',
              email: 'n1@x',
              firstName: 'Nina',
              lastName: 'Sawadogo',
              roleName: 'NURSE',
              profileType: 'STAFF',
            },
            {
              id: 'a1',
              username: 'acct',
              email: 'a@x',
              firstName: 'Ali',
              lastName: 'Zongo',
              roleName: 'ROLE_ACCOUNTANT',
            },
          ],
        });

      expect(component.availableUsers().map((u) => u.id)).toEqual(['n1']);
      expect(component.availableUsers()[0].name).toBe('Nina Sawadogo');
      component.userSearchTerm.set('nurse1');
      expect(component.filteredUsers().map((u) => u.id)).toEqual(['n1']);
      expect(component.noRecipientsYet()).toBeFalse();
    });

    it('a dual-role user acting as staff keeps the user directory', () => {
      setUp(['ROLE_NURSE', 'ROLE_PATIENT'], 'ROLE_NURSE');
      openPicker();
      httpMock.expectNone((r) => r.url === CARE_TEAM || r.url === APPOINTMENTS);
      httpMock.expectOne((r) => r.url === '/users').flush({ content: [] });
      expect(component.loadingUsers()).toBeFalse();
      expect(component.noRecipientsYet()).toBeFalse();
    });

    it('still reports a directory failure as an error', () => {
      setUp(['ROLE_DOCTOR']);
      openPicker();
      httpMock
        .expectOne((r) => r.url === '/users')
        .flush({}, { status: 500, statusText: 'Server Error' });

      expect(toast.error).toHaveBeenCalledOnceWith('CHAT.USERS_LOAD_FAILED');
    });
  });
});
