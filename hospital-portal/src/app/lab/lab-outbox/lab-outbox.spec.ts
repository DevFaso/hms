import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { LabOutboxComponent } from './lab-outbox';
import {
  InstrumentOutboxService,
  OutboxMessage,
  OutboxPage,
  OutboxTransport,
} from '../../services/instrument-outbox.service';
import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../core/toast.service';

/**
 * The instrument outbox monitor (P2 #17).
 *
 * The V119 delivery-tracking columns (attempts / last_error / last_attempt_at)
 * were write-only end to end, and ERROR was an absorbing state nothing could
 * exit. These specs pin the reader surface and the requeue affordance.
 */
describe('LabOutboxComponent', () => {
  let fixture: ComponentFixture<LabOutboxComponent>;
  let component: LabOutboxComponent;
  let outboxService: jasmine.SpyObj<InstrumentOutboxService>;
  let auth: jasmine.SpyObj<AuthService>;
  let toast: jasmine.SpyObj<ToastService>;

  function message(overrides: Partial<OutboxMessage>): OutboxMessage {
    return {
      id: 'm-1',
      labOrderId: 'order-1',
      messageType: 'ORU^R01',
      status: 'PENDING',
      createdAt: '2026-08-22T08:00:00',
      attempts: 0,
      ...overrides,
    };
  }

  function pageOf(content: OutboxMessage[], errorCount = 0): OutboxPage {
    return {
      content,
      page: 0,
      size: 25,
      totalElements: content.length,
      pendingCount: content.filter((m) => m.status === 'PENDING').length,
      errorCount,
      ackCount: 0,
    };
  }

  const transportOff: OutboxTransport = {
    enabled: false,
    host: 'localhost',
    port: 2576,
    maxAttempts: 5,
    retryAfterSeconds: 60,
    batchSize: 50,
  };

  function setup(roles: string[], page: OutboxPage, transport: OutboxTransport = transportOff) {
    outboxService = jasmine.createSpyObj<InstrumentOutboxService>('InstrumentOutboxService', [
      'search',
      'getMessage',
      'getMessagesForOrder',
      'retry',
      'getTransport',
    ]);
    outboxService.search.and.returnValue(of(page));
    outboxService.getTransport.and.returnValue(of(transport));

    auth = jasmine.createSpyObj<AuthService>('AuthService', ['getRoles', 'getHospitalId']);
    auth.getRoles.and.returnValue(roles);

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    TestBed.configureTestingModule({
      imports: [LabOutboxComponent, TranslateModule.forRoot()],
      providers: [
        { provide: InstrumentOutboxService, useValue: outboxService },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
      ],
    });

    fixture = TestBed.createComponent(LabOutboxComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('loads the queue and renders a row per message', () => {
    setup(
      ['ROLE_LAB_TECHNICIAN'],
      pageOf([message({ id: 'm-1' }), message({ id: 'm-2', status: 'ACK' })]),
    );

    expect(outboxService.search).toHaveBeenCalledWith('', 0, 25);
    expect(fixture.nativeElement.querySelector('[data-testid="outbox-row-m-1"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="outbox-row-m-2"]')).not.toBeNull();
  });

  it('shows the transport-off banner so an empty wire is diagnosable', () => {
    setup(['ROLE_LAB_TECHNICIAN'], pageOf([]));

    const banner = fixture.nativeElement.querySelector(
      '[data-testid="outbox-transport-banner"]',
    ) as HTMLElement;
    expect(banner).not.toBeNull();
    expect(banner.classList).toContain('transport-off');
  });

  it('offers retry only on ERROR rows, and only to the supervisory roles', () => {
    setup(
      ['ROLE_LAB_MANAGER'],
      pageOf(
        [message({ id: 'm-err', status: 'ERROR' }), message({ id: 'm-ok', status: 'ACK' })],
        1,
      ),
    );

    expect(
      fixture.nativeElement.querySelector('[data-testid="outbox-retry-m-err"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="outbox-retry-m-ok"]')).toBeNull();
  });

  it('hides retry from roles the backend would refuse', () => {
    // Mirrors RETRY_ROLES on POST /{id}/retry — a technician seeing a button
    // that 403s is the read-back-button defect all over again.
    setup(['ROLE_LAB_TECHNICIAN'], pageOf([message({ id: 'm-err', status: 'ERROR' })], 1));

    expect(fixture.nativeElement.querySelector('[data-testid="outbox-retry-m-err"]')).toBeNull();
  });

  it('requeues an ERROR row and reloads', () => {
    const row = message({ id: 'm-err', status: 'ERROR', attempts: 5 });
    setup(['ROLE_LAB_MANAGER'], pageOf([row], 1));
    outboxService.retry.and.returnValue(of({ ...row, status: 'PENDING', attempts: 0 }));

    component.retry(row);

    expect(outboxService.retry).toHaveBeenCalledWith('m-err');
    expect(toast.success).toHaveBeenCalled();
    expect(component.retryingId()).toBeNull();
    expect(outboxService.search).toHaveBeenCalledTimes(2);
  });

  it('surfaces the backend refusal verbatim when a retry is rejected', () => {
    const row = message({ id: 'm-ok', status: 'ERROR' });
    setup(['ROLE_LAB_MANAGER'], pageOf([row], 1));
    const backendMessage = 'Only a message in ERROR can be retried; this one is ACK.';
    outboxService.retry.and.returnValue(
      throwError(() => ({ error: { message: backendMessage } }) as unknown),
    );

    component.retry(row);

    expect(toast.error).toHaveBeenCalledWith(backendMessage);
    expect(component.retryingId()).toBeNull();
  });

  it('reloads from the first page when the status filter changes', () => {
    setup(['ROLE_LAB_TECHNICIAN'], pageOf([message({ id: 'm-1' })]));
    component.pageIndex.set(3);

    component.setStatusFilter('ERROR');

    expect(component.pageIndex()).toBe(0);
    expect(outboxService.search).toHaveBeenCalledWith('ERROR', 0, 25);
  });

  it('opens the detail modal with the payload and the order’s sibling messages', () => {
    const row = message({ id: 'm-1' });
    setup(['ROLE_LAB_TECHNICIAN'], pageOf([row]));
    outboxService.getMessage.and.returnValue(of({ ...row, payload: 'MSH|^~\\&|HMS|...' }));
    outboxService.getMessagesForOrder.and.returnValue(
      of([row, message({ id: 'm-2', messageType: 'OML^O21', status: 'ACK' })]),
    );

    component.openDetail(row);
    fixture.detectChanges();

    const payload = fixture.nativeElement.querySelector(
      '[data-testid="outbox-detail-payload"]',
    ) as HTMLElement;
    expect(payload.textContent).toContain('MSH|^~\\&|HMS|');
    // The clicked message itself is filtered out of the siblings list.
    expect(component.detail()?.siblings.map((s) => s.id)).toEqual(['m-2']);
  });
});
