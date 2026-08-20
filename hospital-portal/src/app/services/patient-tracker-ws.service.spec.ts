import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

import { PatientTrackerWsService } from './patient-tracker-ws.service';
import { AuthService } from '../auth/auth.service';

/**
 * Ref-counting contract of the shared tracker socket (task 24): the tracker
 * board, dashboard, and nurse station each pair one connect() with one
 * disconnect(); the underlying connection only tears down when the LAST
 * consumer releases. These tests exercise the counting via the token guard
 * (auth.getToken() -> null makes connect() a counted no-op), so no STOMP or
 * SockJS machinery is involved.
 */
describe('PatientTrackerWsService — shared-connection ref counting', () => {
  let service: PatientTrackerWsService;
  let auth: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    auth = jasmine.createSpyObj('AuthService', ['getToken', 'isExpired']);
    auth.getToken.and.returnValue(null);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    service = TestBed.inject(PatientTrackerWsService);
  });

  it('tears down only when the last consumer disconnects', () => {
    const states: boolean[] = [];
    const sub = service.getConnectionState().subscribe((s) => states.push(s));

    service.connect('h1'); // consumer A
    service.connect('h1'); // consumer B

    service.disconnect(); // A leaves — B still holds the socket
    expect(states).toEqual([]);

    service.disconnect(); // B leaves — teardown emits disconnected
    expect(states).toEqual([false]);

    sub.unsubscribe();
  });

  it('extra disconnects never underflow the count', () => {
    const states: boolean[] = [];
    const sub = service.getConnectionState().subscribe((s) => states.push(s));

    service.disconnect(); // no consumer yet — still a teardown at zero
    expect(states).toEqual([false]);

    service.connect('h1');
    service.disconnect();
    expect(states).toEqual([false, false]);

    sub.unsubscribe();
  });

  it('counts a consumer even when the token guard no-ops the attempt', () => {
    // Both consumers connect while logged out; the first release must not
    // kill a socket a later consumer could establish.
    const states: boolean[] = [];
    const sub = service.getConnectionState().subscribe((s) => states.push(s));

    service.connect('h1');
    service.connect('h1');
    service.disconnect();
    expect(states).toEqual([]); // one consumer still registered

    sub.unsubscribe();
  });
});
