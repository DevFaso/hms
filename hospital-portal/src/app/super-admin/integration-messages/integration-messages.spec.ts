import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

import { IntegrationMessagesComponent } from './integration-messages';
import { IntegrationMessagesService } from '../../services/integration-messages.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { ToastService } from '../../core/toast.service';
import {
  IntegrationMessageEvent,
  IntegrationMessagePage,
} from '../../services/integration-messages.model';

const fakeEvent = (overrides: Partial<IntegrationMessageEvent> = {}): IntegrationMessageEvent => ({
  id: overrides.id ?? 'msg-1',
  integrationId: overrides.integrationId ?? 'partner.nhis',
  organizationId: overrides.organizationId ?? null,
  direction: overrides.direction ?? 'OUTBOUND',
  messageType: overrides.messageType ?? 'CLAIM',
  correlationId: overrides.correlationId ?? 'trace-1',
  payload: overrides.payload ?? '{}',
  payloadPurgedAt: overrides.payloadPurgedAt ?? null,
  status: overrides.status ?? 'FAILED',
  errorMessage: overrides.errorMessage ?? 'partner timeout',
  attemptCount: overrides.attemptCount ?? 1,
  lastAttemptedAt: overrides.lastAttemptedAt ?? '2026-05-04T12:00:00Z',
  receivedAt: overrides.receivedAt ?? '2026-05-04T12:00:00Z',
  organizationName: overrides.organizationName ?? null,
  resolved: overrides.resolved ?? false,
  attempts: overrides.attempts ?? null,
});

const fakePage = (
  rows: IntegrationMessageEvent[] = [fakeEvent()],
  deadLetterCount = 1,
): IntegrationMessagePage => ({
  content: rows,
  pageNumber: 0,
  pageSize: 25,
  totalElements: rows.length,
  totalPages: rows.length > 0 ? 1 : 0,
  deadLetterCount,
  retentionActive: true,
  payloadRetentionDays: 180,
  payloadUnresolvedMaxDays: 365,
});

describe('IntegrationMessagesComponent (MVP-c3)', () => {
  let service: jasmine.SpyObj<IntegrationMessagesService>;
  let confirm: jasmine.SpyObj<ConfirmService>;
  let toast: jasmine.SpyObj<ToastService>;

  function setupFixture() {
    TestBed.configureTestingModule({
      imports: [IntegrationMessagesComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: IntegrationMessagesService, useValue: service },
        { provide: ConfirmService, useValue: confirm },
        { provide: ToastService, useValue: toast },
      ],
    });
    const fixture = TestBed.createComponent(IntegrationMessagesComponent);
    fixture.detectChanges();
    return fixture;
  }

  function setup(): IntegrationMessagesComponent {
    return setupFixture().componentInstance;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<IntegrationMessagesService>('IntegrationMessagesService', [
      'search',
      'replay',
      'getById',
    ]);
    confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(true);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
  });

  it('loads the message page on init and exposes the dead-letter count', () => {
    service.search.and.returnValue(of(fakePage()));

    const cmp = setup();

    expect(service.search).toHaveBeenCalledTimes(1);
    expect(cmp.rows().length).toBe(1);
    expect(cmp.deadLetterCount()).toBe(1);
  });

  it('shows the error panel when the search call fails', () => {
    service.search.and.returnValue(throwError(() => new Error('boom')));

    const cmp = setup();

    expect(cmp.errored()).toBeTrue();
    expect(cmp.rows().length).toBe(0);
  });

  it('showOnlyDeadLetters() locks status=FAILED and re-runs the search', () => {
    service.search.and.returnValue(of(fakePage()));

    const cmp = setup();
    cmp.showOnlyDeadLetters();

    expect(cmp.status()).toBe('FAILED');
    expect(service.search).toHaveBeenCalledTimes(2);
    const lastArgs = service.search.calls.mostRecent().args[0];
    expect(lastArgs?.status).toBe('FAILED');
  });

  it('resetFilters() clears every filter and re-runs the search', () => {
    service.search.and.returnValue(of(fakePage()));

    const cmp = setup();
    cmp.integrationId.set('partner.nhis');
    cmp.status.set('FAILED');
    cmp.fromDate.set('2026-05-01T00:00');
    cmp.resetFilters();

    expect(cmp.integrationId()).toBe('');
    expect(cmp.status()).toBe('');
    expect(cmp.fromDate()).toBe('');
    expect(service.search).toHaveBeenCalledTimes(2);
  });

  it('replay() flips per-row busy state, then clears it and reloads on success', async () => {
    service.search.and.returnValue(of(fakePage()));
    service.replay.and.returnValue(of(fakeEvent({ status: 'REPLAYED', attemptCount: 2 })));

    const cmp = setup();
    await cmp.replay('msg-1');

    // Synchronous of() resolves immediately, so by the time we read
    // the state the row should be cleared and the search reloaded.
    expect(service.replay).toHaveBeenCalledOnceWith('msg-1');
    expect(cmp.replayBusyFor('msg-1')).toBeFalse();
    // search() called twice — once on init, once after replay.
    expect(service.search).toHaveBeenCalledTimes(2);
  });

  it('replay() surfaces an error key and keeps the row visible on failure', async () => {
    service.search.and.returnValue(of(fakePage()));
    service.replay.and.returnValue(throwError(() => new Error('partner timeout')));

    const cmp = setup();
    await cmp.replay('msg-1');

    expect(cmp.replayErrorKeyFor('msg-1')).toBe('INTEGRATION_MESSAGES.ERROR.REPLAY_FAILED');
    expect(cmp.replayBusyFor('msg-1')).toBeFalse();
    // Failed replay must NOT trigger a reload — the row should stay
    // exactly where the operator can see it.
    expect(service.search).toHaveBeenCalledTimes(1);
  });

  it('goToPage() ignores out-of-range targets', () => {
    service.search.and.returnValue(of(fakePage([fakeEvent()], 0)));

    const cmp = setup();
    cmp.goToPage(-1);
    cmp.goToPage(99);

    // Initial load only — both invalid jumps were no-ops.
    expect(service.search).toHaveBeenCalledTimes(1);
    expect(cmp.pageNumber()).toBe(0);
  });
  it('shows a purged row as "content purged" with replay disabled', () => {
    const purged = fakeEvent({
      id: 'msg-purged',
      payload: null,
      payloadPurgedAt: '2026-10-01T03:30:00',
    });
    const live = fakeEvent({ id: 'msg-live' });
    service.search.and.returnValue(of(fakePage([purged, live])));

    const fixture = setupFixture();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('[data-test-purged="msg-purged"]')).not.toBeNull();
    expect(el.querySelector('[data-test-purged="msg-live"]')).toBeNull();
    const purgedReplay = el.querySelector<HTMLButtonElement>('[data-test-replay="msg-purged"]');
    const liveReplay = el.querySelector<HTMLButtonElement>('[data-test-replay="msg-live"]');
    // QA D1124: a purged message offers no replay (the "content purged" note says why).
    expect(purgedReplay).toBeNull();
    expect(liveReplay?.disabled).toBeFalse();
    expect(el.querySelector('[data-test="retention-note"]')).not.toBeNull();
  });

  it('replay() refuses a purged row without calling the server', async () => {
    const purged = fakeEvent({
      id: 'msg-purged',
      payload: null,
      payloadPurgedAt: '2026-10-01T03:30:00',
    });
    service.search.and.returnValue(of(fakePage([purged])));

    const cmp = setup();
    await cmp.replay('msg-purged');

    expect(service.replay).not.toHaveBeenCalled();
    expect(cmp.isPurged(purged)).toBeTrue();
    expect(cmp.isPurged(fakeEvent())).toBeFalse();
  });
  it('states the windows only while retention is actually running', () => {
    service.search.and.returnValue(of(fakePage()));

    const el: HTMLElement = setupFixture().nativeElement;

    expect(el.querySelector('[data-test="retention-note"]')).not.toBeNull();
    expect(el.querySelector('[data-test="retention-off"]')).toBeNull();
  });

  it('says retention is OFF instead of quoting windows nobody enforces', () => {
    service.search.and.returnValue(of({ ...fakePage(), retentionActive: false }));

    const el: HTMLElement = setupFixture().nativeElement;

    expect(el.querySelector('[data-test="retention-off"]')).not.toBeNull();
    expect(el.querySelector('[data-test="retention-note"]')).toBeNull();
  });
  it('replay() answering 409 (content purged meanwhile) says so and reloads the list', async () => {
    service.search.and.returnValue(of(fakePage()));
    service.replay.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { message: 'Le contenu de ce message a été purgé.' },
          }),
      ),
    );

    const cmp = setup();
    await cmp.replay('msg-1');

    expect(cmp.replayErrorKeyFor('msg-1')).toBe('Le contenu de ce message a été purgé.');
    expect(toast.error).toHaveBeenCalledWith('Le contenu de ce message a été purgé.');
    expect(cmp.replayBusyFor('msg-1')).toBeFalse();
    // Reloaded so the row comes back purged and its Replay button disables.
    expect(service.search).toHaveBeenCalledTimes(2);
  });

  it('replay() answering 500 keeps the generic error and does not reload', async () => {
    service.search.and.returnValue(of(fakePage()));
    service.replay.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    const cmp = setup();
    await cmp.replay('msg-1');

    expect(cmp.replayErrorKeyFor('msg-1')).toBe('INTEGRATION_MESSAGES.ERROR.REPLAY_FAILED');
    expect(service.search).toHaveBeenCalledTimes(1);
  });

  // ── QA D4301 ───────────────────────────────────────────────────────

  it('D4301: asks before replaying, and does nothing when the answer is no', async () => {
    service.search.and.returnValue(of(fakePage()));
    confirm.ask.and.resolveTo(false);
    const cmp = setup();

    await cmp.replay('msg-1');

    expect(confirm.ask).toHaveBeenCalledTimes(1);
    expect(service.replay).not.toHaveBeenCalled();
  });

  it('D4301: says the replay was recorded', async () => {
    service.search.and.returnValue(of(fakePage()));
    service.replay.and.returnValue(of(fakeEvent({ status: 'REPLAYED', attemptCount: 2 })));
    const cmp = setup();

    await cmp.replay('msg-1');

    expect(toast.success).toHaveBeenCalledWith('INTEGRATION_MESSAGES.REPLAY_DONE');
  });

  it('D4301: a second replay of the same message while one is running sends nothing', async () => {
    service.search.and.returnValue(of(fakePage()));
    const pending = new Subject<IntegrationMessageEvent>();
    service.replay.and.returnValue(pending.asObservable());
    const cmp = setup();

    await cmp.replay('msg-1');
    expect(cmp.replayBusyFor('msg-1')).toBeTrue();
    await cmp.replay('msg-1');

    expect(service.replay).toHaveBeenCalledTimes(1);
    pending.next(fakeEvent({ status: 'REPLAYED' }));
    pending.complete();
    expect(cmp.replayBusyFor('msg-1')).toBeFalse();
  });

  it('D4301: a failure already replayed reads as handled and offers no replay', async () => {
    service.search.and.returnValue(
      of(fakePage([fakeEvent({ id: 'done', resolved: true }), fakeEvent({ id: 'open' })], 1)),
    );
    const fixture = setupFixture();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('[data-test-replay="done"]')).toBeNull();
    expect(el.querySelector('[data-test-replay="open"]')).not.toBeNull();
    const handled = el.querySelector('[data-test-row="done"] .status-badge') as HTMLElement;
    expect(handled.textContent?.trim()).toBe('INTEGRATION_MESSAGES.STATUS.FAILED_RESOLVED');

    await fixture.componentInstance.replay('done');
    expect(service.replay).not.toHaveBeenCalled();
  });

  it('D4301: the banner counts what the rows show as still waiting', () => {
    service.search.and.returnValue(
      of(fakePage([fakeEvent({ id: 'done', resolved: true }), fakeEvent({ id: 'open' })], 1)),
    );
    const fixture = setupFixture();
    const el = fixture.nativeElement as HTMLElement;

    const waiting = el.querySelectorAll('[data-test-replay]').length;
    expect(waiting).toBe(fixture.componentInstance.deadLetterCount());
  });

  // ── QA D4302 / D4303 ───────────────────────────────────────────────

  it('D4302: opening a row shows its content, correlation id, organisation and attempts', () => {
    service.search.and.returnValue(of(fakePage()));
    service.getById.and.returnValue(
      of(
        fakeEvent({
          payload: '{"stub":true}',
          correlationId: 'corr-42',
          organizationName: 'Organisation fictive',
          attempts: [
            {
              id: 'msg-1',
              status: 'FAILED',
              attemptCount: 1,
              errorMessage: 'Aucun partenaire raccordé',
              lastAttemptedAt: '2026-05-04T12:00:00Z',
              receivedAt: '2026-05-04T12:00:00Z',
            },
            {
              id: 'msg-2',
              status: 'REPLAYED',
              attemptCount: 2,
              errorMessage: null,
              lastAttemptedAt: '2026-05-04T13:00:00Z',
              receivedAt: '2026-05-04T13:00:00Z',
            },
          ],
        }),
      ),
    );
    const fixture = setupFixture();
    const el = fixture.nativeElement as HTMLElement;

    el.querySelector<HTMLButtonElement>('[data-test-detail="msg-1"]')!.click();
    fixture.detectChanges();

    expect(service.getById).toHaveBeenCalledOnceWith('msg-1');
    expect(el.querySelector('[data-test="detail-payload"]')?.textContent).toBe('{"stub":true}');
    expect(el.querySelector('[data-test="detail-correlation"]')?.textContent).toContain('corr-42');
    expect(el.querySelector('[data-test="detail-organization"]')?.textContent).toContain(
      'Organisation fictive',
    );
    expect(el.querySelectorAll('[data-test="detail-attempts"] li').length).toBe(2);

    fixture.componentInstance.closeDetail();
    fixture.detectChanges();
    expect(el.querySelector('[data-test="message-detail"]')).toBeNull();
  });

  it("D4303: direction and the product's own message types are labels, not raw codes", () => {
    service.search.and.returnValue(of(fakePage([fakeEvent({ messageType: 'PROBE' })])));
    const fixture = setupFixture();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      INTEGRATION_MESSAGES: {
        DIRECTION: { OUTBOUND: 'Sortant', INBOUND: 'Entrant' },
        TYPE: { PROBE: 'Test de connexion' },
      },
    });
    translate.use('fr');
    fixture.detectChanges();
    const row = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-test-row="msg-1"]',
    ) as HTMLElement;

    expect(row.querySelector('.direction-badge')?.textContent?.trim()).toBe('Sortant');
    expect(row.textContent).toContain('Test de connexion');
    expect(row.textContent).not.toContain('OUTBOUND');
    expect(row.textContent).not.toContain('PROBE');
    // A partner's own type (ADT^A04) has no label and is shown as sent.
    expect(fixture.componentInstance.typeLabel('ADT^A04')).toBe('ADT^A04');
  });
});
