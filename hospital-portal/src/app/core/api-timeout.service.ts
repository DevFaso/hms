import { Injectable, signal } from '@angular/core';

/** No requests, credentials, patient identifiers or payloads are retained here. */
@Injectable({ providedIn: 'root' })
export class ApiTimeoutService {
  readonly failure = signal<'read' | 'write' | null>(null);

  noteTimeout(method: string): void {
    // A later background read must not hide an uncertain write outcome.
    if (this.failure() !== 'write') {
      this.failure.set(['GET', 'HEAD', 'OPTIONS'].includes(method) ? 'read' : 'write');
    }
  }
}
