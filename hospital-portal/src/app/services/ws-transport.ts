import { HttpContext, HttpContextToken } from '@angular/common/http';

/**
 * Marks a request no page is waiting on: a background connection such as the
 * WebSocket ticket exchange. A 403 on it is the caller's to handle (the
 * socket retries with backoff); the error interceptor never sends the whole
 * page to /error/403 for it (QA D35: users landed on the forbidden page right
 * after login because the notification socket's ticket call was refused).
 */
export const BACKGROUND_REQUEST = new HttpContextToken<boolean>(() => false);

/** The options of every background request. */
export function backgroundRequestOptions(): { context: HttpContext } {
  return { context: new HttpContext().set(BACKGROUND_REQUEST, true) };
}

/** The URL of the ticket exchange every socket uses. */
export const WS_TICKET_URL = '/auth/ws-ticket';

/**
 * The SockJS transports the portal uses (QA D67). The server checks the
 * Origin of every SockJS request, and Spring then refuses the iframe page
 * (`/ws-chat/iframe.html` answers 404 by design), so the iframe-based
 * fallbacks can never work: offering them only adds a failing request.
 */
export const SOCKJS_TRANSPORTS: readonly string[] = ['websocket', 'xhr-streaming', 'xhr-polling'];

/** The sockjs-client constructor, as the dynamic import hands it over. */
export type SockJsConstructor = new (
  url: string,
  reserved?: unknown,
  options?: { transports: string[] },
) => WebSocket;

/** A SockJS socket on {@link SOCKJS_TRANSPORTS} only. */
export function openSockJs(ctor: SockJsConstructor, url: string): WebSocket {
  return new ctor(url, undefined, { transports: [...SOCKJS_TRANSPORTS] });
}
