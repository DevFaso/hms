import { Injectable, signal } from '@angular/core';

import { isReadRequest } from './read-only-posts';

/** No requests, credentials, patient identifiers or payloads are retained here. */
@Injectable({ providedIn: 'root' })
export class ApiTimeoutService {
  readonly failure = signal<'read' | 'write' | null>(null);
  private epoch = 0;

  get sessionEpoch(): number {
    return this.epoch;
  }

  resetForSession(): void {
    this.epoch++;
    this.failure.set(null);
  }

  /**
   * A POST that only reads (a search whose terms travel in the body, `read-only-posts.ts`) is a
   * read: its timeout never says the action may have been saved. Only its path is used.
   */
  noteTimeout(method: string, epoch = this.sessionEpoch, url = ''): void {
    if (epoch !== this.sessionEpoch) return;
    // A later background read must not hide an uncertain write outcome.
    if (this.failure() !== 'write') {
      this.failure.set(isReadRequest(method, url) ? 'read' : 'write');
    }
  }
}
