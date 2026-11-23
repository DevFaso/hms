import { Injectable, signal } from '@angular/core';

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

  noteTimeout(method: string, epoch = this.sessionEpoch): void {
    if (epoch !== this.sessionEpoch) return;
    // A later background read must not hide an uncertain write outcome.
    if (this.failure() !== 'write') {
      this.failure.set(['GET', 'HEAD', 'OPTIONS'].includes(method) ? 'read' : 'write');
    }
  }
}
