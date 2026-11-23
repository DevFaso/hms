import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';

import {
  ChannelChoice,
  PatientChannelPreferencesComponent,
} from './patient-channel-preferences.component';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { NOTICE_TYPES } from '../../shared/notification-channel-choices';

/** QA D2203: the desk sees and records a patient's SMS / e-mail choices. */
describe('PatientChannelPreferencesComponent', () => {
  let fixture: ComponentFixture<PatientChannelPreferencesComponent>;
  let http: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;

  const matrix = (): ChannelChoice[] =>
    NOTICE_TYPES.flatMap((type) =>
      (['SMS', 'EMAIL'] as const).map((channel) => ({
        notificationType: type,
        channel,
        enabled: !(type === 'BILLING' && channel === 'EMAIL'),
      })),
    );

  function setUp(desk: boolean): void {
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    TestBed.configureTestingModule({
      imports: [PatientChannelPreferencesComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: RoleContextService, useValue: { hasAnyActiveRole: () => desk } },
        { provide: ToastService, useValue: toast },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PatientChannelPreferencesComponent);
    fixture.componentRef.setInput('patientId', 'p-1');
    fixture.detectChanges();
  }

  const box = (testid: string): HTMLInputElement =>
    (fixture.nativeElement as HTMLElement).querySelector(
      `[data-testid="${testid}"]`,
    ) as HTMLInputElement;

  it('shows the server-recorded actor and time without treating a legacy row as newly recorded', () => {
    setUp(true);
    const choices = matrix();
    choices[0] = {
      ...choices[0],
      recordedByUserId: 'recorder-1',
      recordedByName: 'cdx Desk Recorder',
      recordedAt: '2026-11-22T08:00:00',
    };
    http.expectOne('/patients/p-1/channel-preferences').flush(choices);
    fixture.detectChanges();
    const provenance = fixture.nativeElement.querySelector(
      `[data-testid="choice-recorder-${choices[0].notificationType}-${choices[0].channel}"]`,
    ) as HTMLElement;
    expect(provenance.textContent).toContain('cdx Desk Recorder');
    expect(provenance.querySelector('time')?.dateTime).toBe('2026-11-22T08:00:00Z');
    expect(provenance.querySelector('time')?.textContent?.trim()).toBeTruthy();
    expect(fixture.nativeElement.querySelectorAll('.choice-recorder').length).toBe(1);
  });

  it('shows the stored choices: the patient turned billing e-mails off', async () => {
    setUp(true);
    http.expectOne('/patients/p-1/channel-preferences').flush(matrix());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(box('channel-BILLING-EMAIL').checked).toBeFalse();
    expect(box('channel-BILLING-SMS').checked).toBeTrue();
    expect(box('channel-APPOINTMENT_REMINDER-SMS')).not.toBeNull();
    // Nothing a sender ignores: no PUSH, no bell, no « Système ».
    expect(box('channel-SYSTEM-SMS')).toBeNull();
    expect(box('channel-BILLING-PUSH')).toBeNull();
    expect(box('channel-BILLING-IN_APP')).toBeNull();
  });

  it('records a change and says so', () => {
    setUp(true);
    http.expectOne('/patients/p-1/channel-preferences').flush(matrix());
    fixture.detectChanges();

    fixture.componentInstance.toggle('APPOINTMENT_REMINDER', 'SMS', false);
    fixture.detectChanges();
    expect(fixture.componentInstance.dirty()).toBeTrue();
    fixture.componentInstance.save();

    const put = http.expectOne('/patients/p-1/channel-preferences');
    expect(put.request.method).toBe('PUT');
    const sent = put.request.body as ChannelChoice[];
    expect(sent.length).toBe(1);
    expect(
      sent.find((c) => c.notificationType === 'APPOINTMENT_REMINDER' && c.channel === 'SMS')
        ?.enabled,
    ).toBeFalse();
    put.flush(
      matrix().map((row) =>
        row.notificationType === 'APPOINTMENT_REMINDER' && row.channel === 'SMS'
          ? { ...row, enabled: false }
          : row,
      ),
    );

    expect(toast.success).toHaveBeenCalledWith('PATIENT_CHANNELS.SAVED');
    expect(fixture.componentInstance.dirty()).toBeFalse();
    expect(fixture.componentInstance.isOn('BILLING', 'EMAIL')).toBeFalse();
  });

  it('is not shown, and reads nothing, for a role the endpoint refuses', () => {
    setUp(false);
    http.expectNone('/patients/p-1/channel-preferences');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="channel-prefs"]'),
    ).toBeNull();
  });

  afterEach(() => http.verify());
});
