import { Injectable } from '@angular/core';
import { TestBed, fakeAsync, flush, flushMicrotasks, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Client, IFrame, StompConfig } from '@stomp/stompjs';

import { AuthService } from '../auth/auth.service';
import { EmergencyBroadcastService, STOMP_ACCESS_DENIED } from './emergency-broadcast.service';

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
 * stops it for that token, and the backoff is only forgotten by a connection
 * that stayed up, never by a connect whose subscription was then refused.
 */
describe('EmergencyBroadcastService — reconnects', () => {
  let service: DrivenBroadcastService;
  let http: HttpTestingController;
  let token: string;

  beforeEach(() => {
    token = 'token-1';
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['getToken', 'isExpired']);
    auth.getToken.and.callFake(() => token);
    auth.isExpired.and.returnValue(false);

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

    // Another session (a new token) may try again.
    token = 'token-2';
    service.connect();
    http.expectOne(TICKET);
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
