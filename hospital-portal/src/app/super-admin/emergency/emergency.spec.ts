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
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { expectEmptySubmitRefusal } from '../../testing/form-refusal-behaviour';

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

  it('searches the same term again after a pick cleared the box', async () => {
    users.search.and.returnValue(
      of({ content: [user('u1', 'Awa')], totalElements: 1, totalPages: 1, size: 10, number: 0 }),
    );
    component.onMfaQueryChange('Aw');
    await settle();
    component.addMfaTarget(component.mfaResults()[0]);
    component.onMfaQueryChange('Aw');
    await settle();
    expect(users.search).toHaveBeenCalledTimes(2);
  });

  it('clears stale results the moment the query changes', async () => {
    users.search.and.returnValue(
      of({ content: [user('u1', 'Awa')], totalElements: 1, totalPages: 1, size: 10, number: 0 }),
    );
    component.onMfaQueryChange('Aw');
    await settle();
    expect(component.mfaResults().length).toBe(1);
    component.onMfaQueryChange('Bin');
    expect(component.mfaResults()).toEqual([]);
  });

  it('reports a failed search instead of "no user matches"', async () => {
    users.search.and.returnValue(throwError(() => new Error('503')));
    component.onMfaQueryChange('Aw');
    await settle();
    expect(component.mfaSearchFailed()).toBeTrue();
    expect(component.mfaResults()).toEqual([]);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.picker-error')).not.toBeNull();
  });

  it('invalidates the typed phrase when the blast radius changes', () => {
    component.mfaResetAllConfirm.set(RESET_ALL_PHRASE);
    component.onMfaScopeChange('h1');
    expect(component.mfaResetAllConfirm()).toBe('');

    component.mfaResetAllConfirm.set(RESET_ALL_PHRASE);
    component.addMfaTarget(user('u1', 'Awa'));
    expect(component.mfaResetAllConfirm()).toBe('');

    component.mfaResetAllConfirm.set(RESET_ALL_PHRASE);
    component.removeMfaTarget('u1');
    expect(component.mfaResetAllConfirm()).toBe('');
  });

  it('does not search for a single character', async () => {
    component.onMfaQueryChange('A');
    await settle();
    expect(users.search).not.toHaveBeenCalled();
  });

  it('sends the selected ids and hospital scope — never resetAll', async () => {
    controls.forceMfaReenrol.and.returnValue(of(ok(1)));
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    component.addMfaTarget(user('u1', 'Awa'));
    component.addMfaTarget(user('u2', 'Bintou'));
    component.removeMfaTarget('u2');
    component.mfaHospitalId.set('h1');
    component.mfaReason.set('phishing at site A');
    component.mfaMfa.set('123456');

    await component.forceMfaReenrol();

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

  it('surfaces the server message when the backend refuses', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    controls.forceMfaReenrol.and.returnValue(
      throwError(() => ({ error: { message: 'mfa_required: invalid or missing X-Mfa-Token' } })),
    );
    component.addMfaTarget(user('u1', 'Awa'));
    component.mfaReason.set('reason ok');
    await component.forceMfaReenrol();
    expect(component.mfaPanel().error).toBe('mfa_required: invalid or missing X-Mfa-Token');
    expect(component.mfaPanel().busy).toBeFalse();
  });
});

describe('EmergencyComponent — every action asks, and a blank form marks its fields (QA CX-001)', () => {
  let fixture: ComponentFixture<EmergencyComponent>;
  let component: EmergencyComponent;
  let controls: jasmine.SpyObj<EmergencyControlService>;
  let ask: jasmine.Spy;
  let toggle: jasmine.Spy;

  beforeEach(async () => {
    controls = jasmine.createSpyObj<EmergencyControlService>('EmergencyControlService', [
      'forceLogoutAll',
      'killFeature',
      'forceMfaReenrol',
      'broadcast',
    ]);
    controls.killFeature.and.returnValue(of(ok(1)));
    controls.broadcast.and.returnValue(of(ok(1)));
    controls.forceMfaReenrol.and.returnValue(of(ok(1)));
    const hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', ['list']);
    hospitals.list.and.returnValue(of([]));
    toggle = jasmine.createSpy('toggle').and.returnValue(of({ readOnly: true }));
    await TestBed.configureTestingModule({
      imports: [EmergencyComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: EmergencyControlService, useValue: controls },
        { provide: UserService, useValue: jasmine.createSpyObj('UserService', ['search']) },
        { provide: HospitalService, useValue: hospitals },
        {
          provide: DowntimeService,
          useValue: { load: jasmine.createSpy('load'), status: signal(null), toggle },
        },
      ],
    }).compileComponents();
    ask = spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    fixture = TestBed.createComponent(EmergencyComponent);
    component = fixture.componentInstance;
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => (fixture.nativeElement as HTMLElement).remove());

  it('activating read-only with no message marks it, asks nothing, sends nothing', () => {
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="downtime-activate"]',
      sent: toggle,
    });
    expect(marked).toEqual(['emergency-downtime-message']);
    expect(ask).not.toHaveBeenCalled();
  });

  it('a direct call without a message is refused on the field too', async () => {
    await component.setDowntime(true);
    expect(toggle).not.toHaveBeenCalled();
    expect(ask).not.toHaveBeenCalled();
    const field = (fixture.nativeElement as HTMLElement).querySelector(
      '#emergency-downtime-message',
    );
    expect(field?.getAttribute('aria-invalid')).toBe('true');
  });

  it('activating read-only asks first and sends nothing until the answer; a no sends nothing', async () => {
    component.downtimeMessage.set('  Maintenance until 14:00  ');
    let answer!: (value: boolean) => void;
    ask.and.returnValue(new Promise<boolean>((resolve) => (answer = resolve)));

    const done = component.setDowntime(true);
    expect(ask).toHaveBeenCalledTimes(1);
    expect(toggle).not.toHaveBeenCalled();
    answer(false);
    await done;
    expect(toggle).not.toHaveBeenCalled();

    ask.and.resolveTo(true);
    await component.setDowntime(true);
    expect(toggle).toHaveBeenCalledOnceWith(true, 'Maintenance until 14:00');
  });

  it('lifting read-only asks too, and needs no reason', async () => {
    await component.setDowntime(false);
    expect(ask).toHaveBeenCalledTimes(1);
    expect(toggle).toHaveBeenCalledOnceWith(false, undefined);
  });

  it('the panel of an active mode offers no message to fill', () => {
    component.downtimeStatus.set({ readOnly: true } as never);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('#emergency-downtime-message')).toBeNull();
    expect(host.querySelector('[data-test="downtime-deactivate"]')).not.toBeNull();
  });

  it('a blank kill form marks the key and the reason and sends nothing', () => {
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="emergency-kill-submit"]',
      sent: controls.killFeature,
    });
    expect(marked).toEqual(['emergency-kill-key', 'emergency-kill-reason']);
  });

  it('a filled kill form asks, then sends; a no sends nothing', async () => {
    component.killFlagKey.set(' feature.x ');
    component.killReason.set('broken since noon');
    ask.and.resolveTo(false);
    await component.killFeature();
    expect(controls.killFeature).not.toHaveBeenCalled();

    ask.and.resolveTo(true);
    await component.killFeature();
    expect(controls.killFeature).toHaveBeenCalledOnceWith(
      { flagKey: 'feature.x', reason: 'broken since noon' },
      '',
    );
  });

  it('a blank broadcast marks the message and sends nothing', () => {
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="emergency-broadcast-submit"]',
      sent: controls.broadcast,
    });
    expect(marked).toEqual(['emergency-broadcast-message']);
  });

  it('a filled broadcast asks, then sends; a no sends nothing', async () => {
    component.broadcastMessage.set('Restart at noon');
    ask.and.resolveTo(false);
    await component.broadcast();
    expect(controls.broadcast).not.toHaveBeenCalled();

    ask.and.resolveTo(true);
    await component.broadcast();
    expect(controls.broadcast).toHaveBeenCalledTimes(1);
  });

  it('a blank force-logout marks the reason and the phrase and sends nothing', () => {
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="emergency-logout-submit"]',
      sent: controls.forceLogoutAll,
    });
    expect(marked).toEqual(['emergency-logout-reason', 'emergency-logout-confirm']);
  });

  it('force-logout with the wrong phrase is refused: the typed phrase is its confirmation', () => {
    component.logoutReason.set('credential leak');
    component.logoutConfirm.set('force logout');
    component.forceLogoutAll();
    expect(controls.forceLogoutAll).not.toHaveBeenCalled();
    expect(component.logoutPanel().error).toBeTruthy();
  });

  it('a blank reset of everyone marks the phrase and the reason and sends nothing', () => {
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="emergency-mfa-submit"]',
      sent: controls.forceMfaReenrol,
    });
    expect(marked).toEqual(['emergency-mfa-confirm', 'emergency-mfa-reason']);
  });

  it('a reset of named users asks first: no typed phrase protects it', async () => {
    component.addMfaTarget(user('u1', 'Awa'));
    component.mfaReason.set('phishing at site A');
    ask.and.resolveTo(false);
    await component.forceMfaReenrol();
    expect(controls.forceMfaReenrol).not.toHaveBeenCalled();
    expect(component.mfaSelected().length).toBe(1);

    ask.and.resolveTo(true);
    await component.forceMfaReenrol();
    expect(controls.forceMfaReenrol).toHaveBeenCalledTimes(1);
  });
});

describe('EmergencyComponent — hospital scope directory', () => {
  it('shows a failure with retry instead of an empty "whole platform" list', async () => {
    const controls = jasmine.createSpyObj<EmergencyControlService>('EmergencyControlService', [
      'forceLogoutAll',
      'killFeature',
      'forceMfaReenrol',
      'broadcast',
    ]);
    const users = jasmine.createSpyObj<UserService>('UserService', ['search']);
    const hospitals = jasmine.createSpyObj<HospitalService>('HospitalService', ['list']);
    hospitals.list.and.returnValues(
      throwError(() => new Error('503')),
      of([]),
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
    const fixture = TestBed.createComponent(EmergencyComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();
    // NgModel applies [disabled] asynchronously (setDisabledState after a
    // resolved promise), so settle before reading the DOM.
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.hospitalsLoading()).toBeFalse();
    expect(component.hospitalsFailed()).toBeTrue();
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      'select[aria-busy]',
    ) as HTMLSelectElement;
    expect(select.disabled).toBeTrue();

    component.loadHospitals();
    expect(hospitals.list).toHaveBeenCalledTimes(2);
    expect(component.hospitalsFailed()).toBeFalse();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(select.disabled).toBeFalse();
  });
});
