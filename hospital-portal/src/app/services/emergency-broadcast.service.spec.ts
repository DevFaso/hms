import { Injectable, signal } from '@angular/core';
import { TestBed, fakeAsync, flush, flushMicrotasks, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Client, IFrame, StompConfig } from '@stomp/stompjs';

import { AuthService } from '../auth/auth.service';
import {
  EmergencyBroadcastService,
  STOMP_ACCESS_DENIED,
  STOMP_UNAVAILABLE,
} from './emergency-broadcast.service';

/** The service with its STOMP client and SockJS load replaced: the specs drive the callbacks. */
@Injectable()
class DrivenBroadcastService extends EmergencyBroadcastService {
  readonly configs: StompConfig[] = [];

  protected override createClient(config: StompConfig): Client {
    this.configs.push(config);
    return {
      active: false,
      activate: () => undefined,
      deactivate: () => Promise.resolve(),
      subscribe: () => ({ id: 'sub-1', unsubscribe: () => undefined }),
    } as unknown as Client;
  }

  protected override loadSockJs(): Promise<{ default?: unknown }> {
    return Promise.resolve({ default: class {} });
  }

  get last(): StompConfig {
    return this.configs[this.configs.length - 1];
  }
}

const TICKET = '/auth/ws-ticket';

function errorFrame(message: string): IFrame {
  return { command: 'ERROR', headers: { message }, body: '' } as unknown as IFrame;
}

/**
 * Reconnect behaviour of the emergency-broadcast socket: an authorization
 * refusal (the server's "access-denied" ERROR, what a provider user gets)
 * stops it for that token, and the backoff is forgotten once a subscription
 * has stood a short grace without a refusal (a flaky link keeps reconnecting),
 * never by a connect whose subscription was then refused.
 */
describe('EmergencyBroadcastService — reconnects', () => {
  let service: DrivenBroadcastService;
  let http: HttpTestingController;
  let token: string;
  const tokenVersion = signal(0);

  /** A new access token, as AuthService.setToken announces it. */
  function newToken(value: string): void {
    token = value;
    tokenVersion.update((v) => v + 1);
    TestBed.tick();
  }

  beforeEach(() => {
    token = 'token-1';
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['getToken', 'isExpired']);
    auth.getToken.and.callFake(() => token);
    auth.isExpired.and.returnValue(false);
    Object.defineProperty(auth, 'tokenVersion', { value: tokenVersion });

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        DrivenBroadcastService,
      ],
    });
    service = TestBed.inject(DrivenBroadcastService);
    http = TestBed.inject(HttpTestingController);
  });

  /** One connect: the ticket request answered and the client built. */
  function open(): StompConfig {
    http.expectOne(TICKET).flush({ ticket: 'ticket' });
    flushMicrotasks();
    return service.last;
  }

  it('an access-denied refusal stops reconnecting, for that token', fakeAsync(() => {
    service.connect();
    const config = open();
    config.onConnect!(errorFrame(''));

    config.onStompError!(errorFrame(STOMP_ACCESS_DENIED));
    tick(5 * 60_000);
    http.expectNone(TICKET);

    service.connect();
    http.expectNone(TICKET);

    // A new token (a refresh, another sign-in) re-arms it, with no call from the shell.
    newToken('token-2');
    http.expectOne(TICKET);
    flush();
  }));

  it('unavailable (the server could not resolve the caller just now) is retried with backoff', fakeAsync(() => {
    service.connect();
    open().onStompError!(errorFrame(STOMP_UNAVAILABLE));

    tick(5_000);
    open();
    service.disconnect();
    flush();
  }));

  it('a backoff that gave up re-arms on a new token; a released socket does not', fakeAsync(() => {
    service.connect();
    let config = open();
    for (const wait of [5_000, 10_000, 20_000, 40_000, 60_000]) {
      config.onStompError!(errorFrame(STOMP_UNAVAILABLE));
      tick(wait);
      config = open();
    }
    config.onStompError!(errorFrame(STOMP_UNAVAILABLE)); // the sixth: it gives up
    tick(5 * 60_000);
    http.expectNone(TICKET);

    newToken('token-2');
    open();

    service.disconnect(); // the shell released it: a new token changes nothing
    newToken('token-3');
    http.expectNone(TICKET);
    flush();
  }));

  it('any other ERROR still reconnects, with backoff', fakeAsync(() => {
    service.connect();
    open().onStompError!(errorFrame('broker unavailable'));

    tick(4_999);
    http.expectNone(TICKET);
    tick(1);
    open();
    service.disconnect();
    flush();
  }));

  it('a connect whose subscription is then refused does not reset the backoff', fakeAsync(() => {
    service.connect();
    open().onStompError!(errorFrame('broker unavailable'));
    tick(5_000);

    // Connected, then refused at once: the second wait doubles (10 s), it
    // does not start over at 5 s.
    const second = open();
    second.onConnect!(errorFrame(''));
    second.onStompError!(errorFrame('broker unavailable'));
    tick(5_000);
    http.expectNone(TICKET);
    tick(5_000);
    open();
    service.disconnect();
    flush();
  }));

  it('a flaky link (each subscription accepted, each drop under 30 s) keeps reconnecting', fakeAsync(() => {
    service.connect();
    let config = open();
    for (let drop = 0; drop < 8; drop++) {
      config.onConnect!(errorFrame(''));
      tick(3_000); // accepted: no refusal within the grace
      config.onWebSocketError!(new Event('error'));
      tick(5_000); // the backoff starts over at 5 s every time
      config = open();
    }
    service.disconnect();
    flush();
  }));

  it('a connection that stayed up forgets the earlier failures', fakeAsync(() => {
    service.connect();
    open().onStompError!(errorFrame('broker unavailable'));
    tick(5_000);

    const stable = open();
    stable.onConnect!(errorFrame(''));
    tick(30_000);
    stable.onStompError!(errorFrame('broker unavailable'));
    tick(5_000);
    open();
    service.disconnect();
    flush();
  }));
});
