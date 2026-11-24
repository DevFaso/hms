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
    if (desk) flushVerification('p-1');
  }

  function flushVerification(patientId: string): void {
    const request = http.expectOne(`/patients/${patientId}/recipient-verification`);
    expect(request.request.method).toBe('GET');
    request.flush([
      { channel: 'SMS', verified: true },
      { channel: 'EMAIL', verified: false },
    ]);
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
      recordedByKind: 'STAFF',
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

  it('QA r73 D3700: a choice recorded at another facility shows « Autre établissement », not a name', () => {
    setUp(true);
    const choices = matrix();
    choices[0] = {
      ...choices[0],
      enabled: false,
      recordedByKind: 'OTHER_FACILITY',
      recordedByName: null,
      recordedAt: '2026-11-24T01:29:00',
    };
    choices[1] = {
      ...choices[1],
      enabled: false,
      recordedByKind: 'PATIENT',
      recordedByName: null,
      recordedAt: '2026-11-24T01:30:00',
    };
    http.expectOne('/patients/p-1/channel-preferences').flush(choices);
    fixture.detectChanges();
    const recorder = (i: number) =>
      (
        fixture.nativeElement.querySelector(
          `[data-testid="choice-recorder-${choices[i].notificationType}-${choices[i].channel}"]`,
        ) as HTMLElement
      ).textContent ?? '';
    expect(recorder(0)).toContain('PATIENT_CHANNELS.RECORDER_OTHER_FACILITY');
    expect(recorder(0)).not.toContain('COMMON.UNKNOWN');
    expect(recorder(1)).toContain('PATIENT_CHANNELS.RECORDER_PATIENT');
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

  it('explains an unchanged matrix without marking an optional consent choice invalid or sending it', () => {
    setUp(true);
    http.expectOne('/patients/p-1/channel-preferences').flush(matrix());
    fixture.detectChanges();
    fixture.nativeElement.querySelector('[data-testid="channel-prefs-save"]').click();
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[data-testid="channel-prefs-unchanged"]').textContent,
    ).toContain('SUBMIT_GUARD.NO_CHANGES');
    http.expectNone('/patients/p-1/channel-preferences');
    expect(fixture.nativeElement.querySelector('[aria-invalid="true"]')).toBeNull();
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

  it('locks every desk switch during the pending write, then permits another edit', async () => {
    setUp(true);
    http.expectOne('/patients/p-1/channel-preferences').flush(matrix());
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.toggle('APPOINTMENT_REMINDER', 'SMS', false);
    component.save();
    const put = http.expectOne('/patients/p-1/channel-preferences');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const boxes = [
      ...fixture.nativeElement.querySelectorAll('input[type="checkbox"]'),
    ] as HTMLInputElement[];
    expect(boxes.length).toBeGreaterThan(0);
    expect(boxes.every((box) => box.disabled)).toBeTrue();
    component.toggle('BILLING', 'SMS', false);
    expect(component.isOn('BILLING', 'SMS')).toBeTrue();
    put.flush(
      matrix().map((row) =>
        row.notificationType === 'APPOINTMENT_REMINDER' && row.channel === 'SMS'
          ? { ...row, enabled: false }
          : row,
      ),
    );
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(boxes.every((box) => !box.disabled)).toBeTrue();
    component.toggle('BILLING', 'SMS', false);
    expect(component.dirty()).toBeTrue();
    expect(component.isOn('BILLING', 'SMS')).toBeFalse();
  });

  it('ignores an old patient save while another patient has an unsaved or in-flight refusal', () => {
    setUp(true);
    http.expectOne('/patients/p-1/channel-preferences').flush(matrix());
    const component = fixture.componentInstance;
    component.toggle('APPOINTMENT_REMINDER', 'SMS', false);
    component.save();
    const oldSave = http.expectOne('/patients/p-1/channel-preferences');
    fixture.componentRef.setInput('patientId', 'p-2');
    fixture.detectChanges();
    flushVerification('p-2');
    expect(component.choices()).toEqual([]);
    http.expectOne('/patients/p-2/channel-preferences').flush(matrix());
    component.toggle('LAB_RESULT', 'SMS', false);
    component.save();
    const currentSave = http.expectOne('/patients/p-2/channel-preferences');
    oldSave.flush(matrix());
    expect(component.isOn('LAB_RESULT', 'SMS')).toBeFalse();
    expect(component.saving()).toBeTrue();
    expect(toast.success).not.toHaveBeenCalled();
    currentSave.flush(
      matrix().map((row) =>
        row.notificationType === 'LAB_RESULT' && row.channel === 'SMS'
          ? { ...row, enabled: false }
          : row,
      ),
    );
    expect(component.saving()).toBeFalse();
    expect(component.isOn('LAB_RESULT', 'SMS')).toBeFalse();
    expect(toast.success).toHaveBeenCalledTimes(1);
  });

  it('ignores earlier loads even when navigation returns to the original patient', () => {
    setUp(true);
    const oldA = http.expectOne('/patients/p-1/channel-preferences');
    fixture.componentRef.setInput('patientId', 'p-2');
    fixture.detectChanges();
    flushVerification('p-2');
    const oldB = http.expectOne('/patients/p-2/channel-preferences');
    fixture.componentRef.setInput('patientId', 'p-1');
    fixture.detectChanges();
    flushVerification('p-1');
    const currentA = http.expectOne('/patients/p-1/channel-preferences');
    currentA.flush(matrix());
    const stale = matrix().map((row) => ({ ...row, enabled: true }));
    oldA.flush(stale);
    oldB.flush(stale);
    expect(fixture.componentInstance.isOn('BILLING', 'EMAIL')).toBeFalse();
  });

  it('keeps the draft refusal and reenables the desk after a current save fails', async () => {
    setUp(true);
    http.expectOne('/patients/p-1/channel-preferences').flush(matrix());
    const component = fixture.componentInstance;
    component.toggle('APPOINTMENT_REMINDER', 'SMS', false);
    component.save();
    http
      .expectOne('/patients/p-1/channel-preferences')
      .flush({}, { status: 503, statusText: 'offline' });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(component.saving()).toBeFalse();
    expect(component.dirty()).toBeTrue();
    expect(component.isOn('APPOINTMENT_REMINDER', 'SMS')).toBeFalse();
    expect(box('channel-BILLING-SMS').disabled).toBeFalse();
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('an old patient read error cannot hide the current patient matrix', () => {
    setUp(true);
    const oldRead = http.expectOne('/patients/p-1/channel-preferences');
    fixture.componentRef.setInput('patientId', 'p-2');
    fixture.detectChanges();
    flushVerification('p-2');
    http.expectOne('/patients/p-2/channel-preferences').flush(matrix());
    oldRead.flush({}, { status: 503, statusText: 'offline' });
    expect(fixture.componentInstance.loadFailed()).toBeFalse();
    expect(fixture.componentInstance.choices().length).toBe(matrix().length);
    expect(fixture.componentInstance.isOn('BILLING', 'EMAIL')).toBeFalse();
  });

  it('an old patient save error cannot unlock or alter the current patient pending save', () => {
    setUp(true);
    http.expectOne('/patients/p-1/channel-preferences').flush(matrix());
    const component = fixture.componentInstance;
    component.toggle('APPOINTMENT_REMINDER', 'SMS', false);
    component.save();
    const oldSave = http.expectOne('/patients/p-1/channel-preferences');
    fixture.componentRef.setInput('patientId', 'p-2');
    fixture.detectChanges();
    flushVerification('p-2');
    component.toggle('LAB_RESULT', 'SMS', false);
    expect(component.choices()).toEqual([]);
    http.expectOne('/patients/p-2/channel-preferences').flush(matrix());
    component.toggle('LAB_RESULT', 'SMS', false);
    component.save();
    const currentSave = http.expectOne('/patients/p-2/channel-preferences');
    oldSave.flush({}, { status: 503, statusText: 'offline' });
    expect(component.saving()).toBeTrue();
    expect(component.isOn('LAB_RESULT', 'SMS')).toBeFalse();
    expect(toast.error).not.toHaveBeenCalled();
    currentSave.flush(
      matrix().map((row) =>
        row.notificationType === 'LAB_RESULT' && row.channel === 'SMS'
          ? { ...row, enabled: false }
          : row,
      ),
    );
    expect(component.saving()).toBeFalse();
    expect(component.dirty()).toBeFalse();
  });

  afterEach(() => http.verify());
});
