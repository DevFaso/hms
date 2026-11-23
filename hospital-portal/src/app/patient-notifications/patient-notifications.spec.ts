import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { PatientNotificationsComponent } from './patient-notifications';
import {
  PatientNotificationDelivery,
  PatientNotificationDeliveryPage,
  PatientNotificationDeliveryService,
} from '../services/patient-notification-delivery.service';
import { ToastService } from '../core/toast.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

/**
 * Patient notification sends (V312, scorecard K3): the hospital administrator
 * sees failed patient SMS / emails with a fixed reason, never the number or the
 * text, and can put a FAILED one back in the queue, once.
 */
describe('PatientNotificationsComponent', () => {
  let fixture: ComponentFixture<PatientNotificationsComponent>;
  let component: PatientNotificationsComponent;
  let service: jasmine.SpyObj<PatientNotificationDeliveryService>;
  let toast: jasmine.SpyObj<ToastService>;
  let confirm: jasmine.SpyObj<ConfirmService>;

  function delivery(overrides: Partial<PatientNotificationDelivery>): PatientNotificationDelivery {
    return {
      id: 'd-1',
      patientId: 'p-1',
      patientName: 'Awa Traoré',
      eventType: 'LAB_RESULT_AVAILABLE',
      channel: 'SMS',
      status: 'FAILED',
      attempts: 5,
      errorCode: 'PROVIDER_UNREACHABLE',
      retryable: true,
      manualRetries: 0,
      createdAt: '2026-11-17T08:00:00',
      lastAttemptAt: '2026-11-17T09:00:00',
      ...overrides,
    };
  }

  function pageOf(content: PatientNotificationDelivery[]): PatientNotificationDeliveryPage {
    return {
      content,
      page: 0,
      size: 25,
      totalElements: content.length,
      countsByStatus: { PENDING: 0, RETRYING: 1, SENT: 12, FAILED: content.length, SKIPPED: 2 },
    };
  }

  function setup(page: PatientNotificationDeliveryPage) {
    service = jasmine.createSpyObj<PatientNotificationDeliveryService>(
      'PatientNotificationDeliveryService',
      ['search', 'retry'],
    );
    service.search.and.returnValue(of(page));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(true);

    TestBed.configureTestingModule({
      imports: [PatientNotificationsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientNotificationDeliveryService, useValue: service },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    fixture = TestBed.createComponent(PatientNotificationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('lists FAILED sends by default, with every status counted', () => {
    setup(pageOf([delivery({})]));

    expect(service.search).toHaveBeenCalledWith('FAILED', 0, 25);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="pn-row-d-1"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="pn-count-SENT"]')?.textContent).toContain('12');
    expect(host.querySelector('[data-testid="pn-count-FAILED"]')?.textContent).toContain('1');
  });

  it('shows the reason as a translated code, never a raw transport message', () => {
    setup(pageOf([delivery({})]));

    expect(component.errorKey('PROVIDER_UNREACHABLE')).toBe(
      'PATIENT_NOTIFICATIONS.ERROR.PROVIDER_UNREACHABLE',
    );
    expect(component.errorKey('SOMETHING_NEW')).toBe('PATIENT_NOTIFICATIONS.ERROR.SEND_FAILED');
    expect(component.errorKey(null)).toBeNull();
  });

  it('changing the filter reloads from the first page', () => {
    setup(pageOf([delivery({})]));

    component.setStatusFilter('ALL');

    expect(service.search).toHaveBeenCalledWith('ALL', 0, 25);
  });

  it('retries a FAILED send once and reloads, without asking when nothing went out', async () => {
    setup(pageOf([delivery({})]));
    service.retry.and.returnValue(of(delivery({ status: 'PENDING', retryable: false })));

    await component.retry(delivery({}));

    expect(confirm.ask).not.toHaveBeenCalled();
    expect(service.retry).toHaveBeenCalledOnceWith('d-1', 0);
    expect(toast.success).toHaveBeenCalled();
    expect(service.search).toHaveBeenCalledTimes(2);
  });

  it('never retries an outcome that may already have been delivered, even if a stale client says retryable', async () => {
    setup(pageOf([delivery({ errorCode: 'OUTCOME_UNKNOWN' })]));
    await component.retry(delivery({ errorCode: 'OUTCOME_UNKNOWN' }));
    await component.retry(delivery({ errorCode: 'ATTEMPTS_EXHAUSTED' }));
    expect(service.retry).not.toHaveBeenCalled();
    expect(confirm.ask).not.toHaveBeenCalled();
  });

  it('offers no retry on a send that is not FAILED', () => {
    setup(pageOf([delivery({ status: 'SENT', retryable: false, errorCode: null })]));

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="pn-retry-d-1"]'),
    ).toBeNull();
    void component.retry(delivery({ status: 'SENT', retryable: false }));
    expect(service.retry).not.toHaveBeenCalled();
  });

  it('a refused retry shows the server reason and frees the button', async () => {
    setup(pageOf([delivery({})]));
    service.retry.and.returnValue(
      throwError(() => ({ error: { message: 'Seul un envoi en échec peut être relancé.' } })),
    );

    await component.retry(delivery({}));

    expect(toast.error).toHaveBeenCalled();
    expect(component.retryingId()).toBeNull();
  });

  it('a failed load shows an error state', () => {
    setup(pageOf([]));
    service.search.and.returnValue(throwError(() => ({ status: 500 })));

    component.load();
    fixture.detectChanges();

    expect(component.error()).not.toBeNull();
  });
  // ── QA D2402: an opt-out row is dated and says what it was about ──

  it('QA D2402: an opt-out row (no attempt) shows the day it was recorded, and its appointment', () => {
    setup(
      pageOf([
        delivery({
          id: 'd-9',
          eventType: 'APPOINTMENT_CONFIRMATION',
          channel: 'EMAIL',
          status: 'SKIPPED',
          attempts: 0,
          errorCode: 'OPTED_OUT',
          retryable: false,
          lastAttemptAt: null,
          createdAt: '2026-11-19T09:15:00',
          subjectDate: '2026-11-20',
          subjectTime: '10:30:00',
        }),
      ]),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="pn-date-d-9"]')?.textContent).not.toContain('—');
    expect(host.querySelector('[data-testid="pn-date-d-9"]')?.textContent).toContain(
      'PATIENT_NOTIFICATIONS.RECORDED',
    );
    expect(component.subject(component.rows()[0])).toEqual({
      key: 'PATIENT_NOTIFICATIONS.SUBJECT_APPOINTMENT',
      params: { date: '20/11/2026', time: '10:30' },
    });
    expect(host.querySelector('[data-testid="pn-subject-d-9"]')).not.toBeNull();
  });

  it('QA D2402: an invoice row names its invoice', () => {
    setup(pageOf([delivery({ eventType: 'INVOICE', subjectReference: 'CHA-2026-000057' })]));
    expect(component.subject(component.rows()[0])).toEqual({
      key: 'PATIENT_NOTIFICATIONS.SUBJECT_INVOICE',
      params: { number: 'CHA-2026-000057' },
    });
  });

  it('QA D2402: the default filter says how many rows it leaves out, and shows them in one click', () => {
    setup(pageOf([delivery({})]));
    const host = fixture.nativeElement as HTMLElement;
    const hint = host.querySelector('[data-testid="pn-hidden-by-filter"]');

    // RETRYING 1 + SENT 12 + SKIPPED 2 are not « En échec ».
    expect(component.hiddenByFilter()).toBe(15);
    expect(hint).not.toBeNull();
    (hint?.querySelector('button') as HTMLButtonElement).click();
    expect(service.search).toHaveBeenCalledWith('ALL', 0, 25);
  });
});
