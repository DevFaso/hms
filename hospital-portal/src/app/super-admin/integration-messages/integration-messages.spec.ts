import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
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
});

describe('IntegrationMessagesComponent (MVP-c3)', () => {
  let service: jasmine.SpyObj<IntegrationMessagesService>;

  function setup(): IntegrationMessagesComponent {
    TestBed.configureTestingModule({
      imports: [IntegrationMessagesComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: IntegrationMessagesService, useValue: service },
      ],
    });
    const fixture = TestBed.createComponent(IntegrationMessagesComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
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
});
