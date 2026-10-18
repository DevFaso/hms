import { HttpContext } from '@angular/common/http';

import {
  BACKGROUND_REQUEST,
  SOCKJS_TRANSPORTS,
  SockJsConstructor,
  backgroundRequestOptions,
  openSockJs,
} from './ws-transport';

describe('ws-transport', () => {
  it('QA D67: opens SockJS without the iframe transports the server refuses', () => {
    const calls: unknown[][] = [];
    const fake = function (this: unknown, ...args: unknown[]) {
      calls.push(args);
    } as unknown as SockJsConstructor;

    openSockJs(fake, '/api/ws-chat?ticket=t');

    expect(calls.length).toBe(1);
    expect(calls[0][0]).toBe('/api/ws-chat?ticket=t');
    const transports = (calls[0][2] as { transports: string[] }).transports;
    expect(transports).toEqual([...SOCKJS_TRANSPORTS]);
    expect(transports.some((t) => t.startsWith('iframe'))).toBeFalse();
  });

  it('QA D35: background options carry the background mark', () => {
    expect(backgroundRequestOptions().context.get(BACKGROUND_REQUEST)).toBeTrue();
    expect(new HttpContext().get(BACKGROUND_REQUEST)).toBeFalse();
  });
});
