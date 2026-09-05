import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { EmergencyComponent, RESET_ALL_PHRASE } from './emergency';
import { EmergencyControlService } from '../../services/emergency-control.service';
import { DowntimeService } from '../../services/downtime.service';
import { UserService, UserSummary } from '../../services/user.service';
import { HospitalService } from '../../services/hospital.service';
import { signal } from '@angular/core';
import { EmergencyActionResponse } from '../../services/emergency-control.model';

const ok = (affectedRows: number): EmergencyActionResponse => ({
  action: 'FORCE_MFA_REENROL',
  takenAt: '2026-09-05T16:00:00Z',
  actorUsername: 'super.alice',
  affectedRows,
  message: 'ok',
});

const user = (id: string, name: string): UserSummary => ({
  id,
  username: name.toLowerCase(),
  email: `${name.toLowerCase()}@example.test`,
  firstName: name,
  lastName: 'Test',
  active: true,
  deleted: false,
  roleName: 'ROLE_NURSE',
  profileType: 'STAFF',
  roleCount: 1,
});

describe('EmergencyComponent — MFA-reset picker', () => {
  let fixture: ComponentFixture<EmergencyComponent>;
  let component: EmergencyComponent;
  let controls: jasmine.SpyObj<EmergencyControlService>;
  let users: jasmine.SpyObj<UserService>;

  beforeEach(async () => {
    controls = jasmine.createSpyObj<EmergencyControlService>('EmergencyControlService', [
      'forceLogoutAll',
      'killFeature',
      'forceMfaReenrol',
      'broadcast',
    ]);
    users = jasmine.createSpyObj<UserService>('UserService', ['search']);
    const hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', ['list']);
    hospitals.list.and.returnValue(
      of([
        {
          id: 'h1',
          name: 'Hospital A',
          code: 'HAX',
        } as never,
      ]),
    );
    const downtime = {
      load: jasmine.createSpy('load'),
      status: signal(null),
      toggle: jasmine.createSpy('toggle').and.returnValue(of(null)),
    };

    await TestBed.configureTestingModule({
      imports: [EmergencyComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: EmergencyControlService, useValue: controls },
        { provide: UserService, useValue: users },
        { provide: HospitalService, useValue: hospitals },
        { provide: DowntimeService, useValue: downtime },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(EmergencyComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // The picker debounces on a real timer (no fakeAsync here: the suite runs
  // without a ProxyZone), so the two search cases wait the debounce out.
  const settle = () => new Promise<void>((resolve) => setTimeout(resolve, 350));

  it('searches users by name (debounced) and turns a pick into a chip', async () => {
    users.search.and.returnValue(
      of({ content: [user('u1', 'Awa')], totalElements: 1, totalPages: 1, size: 10, number: 0 }),
    );

    component.onMfaQueryChange('Aw');
    await settle();

    expect(users.search).toHaveBeenCalledWith(0, 10, { name: 'Aw' });
    expect(component.mfaResults().length).toBe(1);

    component.addMfaTarget(component.mfaResults()[0]);
    expect(component.mfaSelected().map((u) => u.id)).toEqual(['u1']);
    expect(component.mfaResults()).toEqual([]);
    expect(component.mfaQuery()).toBe('');

    component.addMfaTarget(user('u1', 'Awa'));
    expect(component.mfaSelected().length).toBe(1);
  });

  it('does not search for a single character', async () => {
    component.onMfaQueryChange('A');
    await settle();
    expect(users.search).not.toHaveBeenCalled();
  });

  it('sends the selected ids and hospital scope — never resetAll', () => {
    controls.forceMfaReenrol.and.returnValue(of(ok(1)));
    component.addMfaTarget(user('u1', 'Awa'));
    component.addMfaTarget(user('u2', 'Bintou'));
    component.removeMfaTarget('u2');
    component.mfaHospitalId.set('h1');
    component.mfaReason.set('phishing at site A');
    component.mfaMfa.set('123456');

    component.forceMfaReenrol();

    expect(controls.forceMfaReenrol).toHaveBeenCalledWith(
      { userIds: ['u1'], hospitalId: 'h1', resetAll: undefined, reason: 'phishing at site A' },
      '123456',
    );
    expect(component.mfaSelected()).toEqual([]);
  });

  it('refuses a reset of everyone until the phrase is typed, then sends resetAll=true', () => {
    controls.forceMfaReenrol.and.returnValue(of(ok(9)));
    component.mfaReason.set('global rotate');
    component.mfaMfa.set('123456');

    component.forceMfaReenrol();
    expect(controls.forceMfaReenrol).not.toHaveBeenCalled();
    expect(component.mfaPanel().error).toBeTruthy();

    component.mfaResetAllConfirm.set(RESET_ALL_PHRASE);
    component.forceMfaReenrol();
    expect(controls.forceMfaReenrol).toHaveBeenCalledWith(
      { userIds: undefined, hospitalId: undefined, resetAll: true, reason: 'global rotate' },
      '123456',
    );
    expect(component.mfaResetAllConfirm()).toBe('');
  });

  it('surfaces the server message when the backend refuses', () => {
    controls.forceMfaReenrol.and.returnValue(
      throwError(() => ({ error: { message: 'mfa_required: invalid or missing X-Mfa-Token' } })),
    );
    component.addMfaTarget(user('u1', 'Awa'));
    component.mfaReason.set('reason ok');
    component.forceMfaReenrol();
    expect(component.mfaPanel().error).toBe('mfa_required: invalid or missing X-Mfa-Token');
    expect(component.mfaPanel().busy).toBeFalse();
  });
});
