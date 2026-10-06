import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { IntegrationMessagesComponent } from './integration-messages';
import { IntegrationMessagesService } from '../../services/integration-messages.service';
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

  function setupFixture() {
    TestBed.configureTestingModule({
      imports: [IntegrationMessagesComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: IntegrationMessagesService, useValue: service },
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
    ]);
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

  it('replay() flips per-row busy state, then clears it and reloads on success', () => {
    service.search.and.returnValue(of(fakePage()));
    service.replay.and.returnValue(of(fakeEvent({ status: 'REPLAYED', attemptCount: 2 })));

    const cmp = setup();
    cmp.replay('msg-1');

    // Synchronous of() resolves immediately, so by the time we read
    // the state the row should be cleared and the search reloaded.
    expect(service.replay).toHaveBeenCalledOnceWith('msg-1');
    expect(cmp.replayBusyFor('msg-1')).toBeFalse();
    // search() called twice — once on init, once after replay.
    expect(service.search).toHaveBeenCalledTimes(2);
  });

  it('replay() surfaces an error key and keeps the row visible on failure', () => {
    service.search.and.returnValue(of(fakePage()));
    service.replay.and.returnValue(throwError(() => new Error('partner timeout')));

    const cmp = setup();
    cmp.replay('msg-1');

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
    expect(purgedReplay?.disabled).toBeTrue();
    expect(liveReplay?.disabled).toBeFalse();
    expect(el.querySelector('[data-test="retention-note"]')).not.toBeNull();
  });

  it('replay() refuses a purged row without calling the server', () => {
    const purged = fakeEvent({
      id: 'msg-purged',
      payload: null,
      payloadPurgedAt: '2026-10-01T03:30:00',
    });
    service.search.and.returnValue(of(fakePage([purged])));

    const cmp = setup();
    cmp.replay('msg-purged');

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
  it('replay() answering 409 (content purged meanwhile) says so and reloads the list', () => {
    service.search.and.returnValue(of(fakePage()));
    service.replay.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409 })));

    const cmp = setup();
    cmp.replay('msg-1');

    expect(cmp.replayErrorKeyFor('msg-1')).toBe('INTEGRATION_MESSAGES.CONTENT_PURGED');
    expect(cmp.replayBusyFor('msg-1')).toBeFalse();
    // Reloaded so the row comes back purged and its Replay button disables.
    expect(service.search).toHaveBeenCalledTimes(2);
  });

  it('replay() answering 500 keeps the generic error and does not reload', () => {
    service.search.and.returnValue(of(fakePage()));
    service.replay.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    const cmp = setup();
    cmp.replay('msg-1');

    expect(cmp.replayErrorKeyFor('msg-1')).toBe('INTEGRATION_MESSAGES.ERROR.REPLAY_FAILED');
    expect(service.search).toHaveBeenCalledTimes(1);
  });
});
