import { ComponentFixture, TestBed } from '@angular/core/testing';
import { expectKeyboardModal } from '../../testing/modal-dialog-keyboard';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

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
      throwError(() => ({ status: 400, error: { message: backendMessage } }) as unknown),
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

  describe('a readable message (owner: segments ran together, no summary, P/F looked like duplicates)', () => {
    // As the builder stores it: CR terminators, escaped caret in the unit.
    const ORU =
      'MSH|^~\\&|HMS|HOSPITAL|LAB_ANALYZER|LAB|20261119153000+0000||ORU^R01^ORU_R01|HMS-ABC|P|2.5.1\r' +
      'PID|1||p-1||Ouedraogo^Awa^Marie\r' +
      'OBR|1|o-1||GLU^Glucose|||20261119152000+0000\r' +
      'OBX|1|NM|GLU^Glycémie||7.8|mmol/L||H|||P|||20261119152000+0000\r';

    function openWith(
      payload: string,
      overrides: Partial<OutboxMessage> = {},
      siblings: OutboxMessage[] = [],
    ) {
      const row = message({ id: 'm-1', ...overrides });
      setup(['ROLE_LAB_TECHNICIAN'], pageOf([row]));
      outboxService.getMessage.and.returnValue(of({ ...row, payload }));
      outboxService.getMessagesForOrder.and.returnValue(of([row, ...siblings]));
      component.openDetail(row);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    const text = (root: HTMLElement, id: string) =>
      root.querySelector<HTMLElement>(`[data-testid="${id}"]`)?.textContent?.trim() ?? null;

    it('puts every segment on its own line whatever the terminator, and never rewrites the payload', () => {
      const payload =
        'MSH|^~\\&|HMS|H|A|L|20261119153000+0000||ORU^R01|X|P|2.5.1\rPID|1\nOBR|1\r\nOBX|1\r';
      const root = openWith(payload);

      const lines = Array.from(
        root.querySelectorAll('[data-testid="outbox-detail-payload"] .segment'),
      ).map((el) => el.textContent);
      expect(lines).toEqual([
        'MSH|^~\\&|HMS|H|A|L|20261119153000+0000||ORU^R01|X|P|2.5.1',
        'PID|1',
        'OBR|1',
        'OBX|1',
      ]);
      expect(component.detail()?.message.payload).toBe(payload);
    });

    it('scrolls a long segment sideways instead of breaking it mid-word', () => {
      const root = openWith(ORU);
      const box = root.querySelector<HTMLElement>('[data-testid="outbox-detail-payload"]')!;
      const segment = box.querySelector<HTMLElement>('.segment')!;
      expect(getComputedStyle(box).overflowX).toBe('auto');
      expect(getComputedStyle(segment).whiteSpace).toBe('pre');
    });

    it('summarises type, patient, test, value + unit + flag, result status, queue time and why it waits', () => {
      const root = openWith(ORU);

      expect(text(root, 'outbox-summary-type')).toContain('LAB_OUTBOX.TYPE_ORU');
      expect(text(root, 'outbox-summary-patient')).toBe('Awa Marie OUEDRAOGO');
      expect(text(root, 'outbox-summary-test')).toBe('Glycémie');
      expect(text(root, 'outbox-summary-value')).toBe('7.8 mmol/L');
      expect(text(root, 'outbox-summary-flag')).toContain('LAB_OUTBOX.FLAG_H');
      expect(text(root, 'outbox-summary-result-status')).toBe('LAB_OUTBOX.RESULT_STATUS_P');
      expect(text(root, 'outbox-summary-queued')).toBeTruthy();
      // The suite's default transport is OFF: that is why it waits.
      expect(text(root, 'outbox-summary-waiting')).toBe('LAB_OUTBOX.WAITING_TRANSPORT_OFF');
    });

    it('names the last error when the transport is on', () => {
      const row = message({ id: 'm-1', status: 'ERROR', lastError: 'Connection refused' });
      setup(['ROLE_LAB_TECHNICIAN'], pageOf([row]), { ...transportOff, enabled: true });
      outboxService.getMessage.and.returnValue(of({ ...row, payload: ORU }));
      outboxService.getMessagesForOrder.and.returnValue(of([]));
      component.openDetail(row);
      fixture.detectChanges();

      expect(component.waitingReason()).toEqual({
        key: 'LAB_OUTBOX.WAITING_LAST_ERROR',
        params: { error: 'Connection refused' },
      });
    });

    it('names an order message in words', () => {
      const root = openWith(
        'MSH|^~\\&|HMS|H|A|L|20261119153000+0000||OML^O21^OML_O21|X|P|2.5.1\rPID|1||p||Sawadogo^Ali\rOBR|1|ACC||NFS^Numération|||20261119150000+0000\r',
        { messageType: 'OML^O21' },
      );
      expect(text(root, 'outbox-summary-type')).toContain('LAB_OUTBOX.TYPE_OML');
      expect(text(root, 'outbox-summary-test')).toBe('Numération');
      expect(root.querySelector('[data-testid="outbox-summary-result-status"]')).toBeNull();
    });

    it('shows only the raw text for a malformed payload', () => {
      const root = openWith('not an HL7 message\rsecond line');
      expect(root.querySelector('[data-testid="outbox-detail-summary"]')).toBeNull();
      expect(root.querySelector('[data-testid="outbox-detail-unparseable"]')).not.toBeNull();
      expect(text(root, 'outbox-detail-payload')).toContain('not an HL7 message');
    });

    it('« Copier le message » copies one segment per line', async () => {
      const root = openWith(ORU);
      const write = spyOn(navigator.clipboard, 'writeText').and.returnValue(Promise.resolve());
      root.querySelector<HTMLButtonElement>('[data-testid="outbox-detail-copy"]')!.click();
      await Promise.resolve();
      await Promise.resolve();

      expect(write).toHaveBeenCalledWith(ORU.split('\r').filter(Boolean).join('\n'));
      expect(toast.success).toHaveBeenCalledWith('LAB_OUTBOX.COPIED');
    });

    it('badges a preliminary and a final message apart, on the row and among the siblings', () => {
      const pre = message({ id: 'm-p', resultStatus: 'P' });
      const fin = message({ id: 'm-f', resultStatus: 'F', status: 'ACK' });
      setup(['ROLE_LAB_TECHNICIAN'], pageOf([pre, fin]));
      const root = fixture.nativeElement as HTMLElement;
      expect(text(root, 'outbox-result-status-m-p')).toBe('LAB_OUTBOX.RESULT_STATUS_P');
      expect(text(root, 'outbox-result-status-m-f')).toBe('LAB_OUTBOX.RESULT_STATUS_F');

      outboxService.getMessage.and.returnValue(of({ ...pre, payload: ORU }));
      outboxService.getMessagesForOrder.and.returnValue(of([pre, fin]));
      component.openDetail(pre);
      fixture.detectChanges();
      expect(text(root, 'outbox-sibling-result-status-m-f')).toBe('LAB_OUTBOX.RESULT_STATUS_F');
    });
  });

  describe('the message dialog keeps the keyboard contract (QA D1154 / D1004 / D1125)', () => {
    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    it('"Voir le message" by keyboard: focus moves in while it loads, Escape closes, focus returns', () => {
      const row = message({ id: 'm-1' });
      setup(['ROLE_LAB_DIRECTOR'], pageOf([row]));
      // The message is still loading when the dialog opens (the round-21 shape).
      outboxService.getMessage.and.returnValue(new Subject<never>());
      outboxService.getMessagesForOrder.and.returnValue(of([]));
      document.body.appendChild(fixture.nativeElement);
      const opener = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
        '[data-testid="outbox-view-m-1"]',
      )!;
      expectKeyboardModal(fixture, opener);
    });

    it('once loaded, Escape still closes and gives focus back', () => {
      const row = message({ id: 'm-1' });
      setup(['ROLE_LAB_DIRECTOR'], pageOf([row]));
      outboxService.getMessage.and.returnValue(of({ ...row, payload: 'MSH|^~&|HMS|' }));
      outboxService.getMessagesForOrder.and.returnValue(of([]));
      document.body.appendChild(fixture.nativeElement);
      const opener = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
        '[data-testid="outbox-view-m-1"]',
      )!;
      expectKeyboardModal(fixture, opener);
    });
  });
});
