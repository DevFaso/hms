import { Injectable, signal } from '@angular/core';

export interface ToastMessage {
  id: number;
  type: 'success' | 'error' | 'info' | 'warning';
  message: string;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  private counter = 0;
  readonly toasts = signal<ToastMessage[]>([]);

  push(type: ToastMessage['type'], message: string, durationMs = 5000): void {
    const id = ++this.counter;
    this.toasts.update((list) => [...list, { id, type, message }]);
    setTimeout(() => this.dismiss(id), durationMs);
  }

  dismiss(id: number): void {
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }

  success(msg: string): void {
    this.push('success', msg);
  }

  error(msg: string): void {
    this.push('error', msg);
  }

  info(msg: string): void {
    this.push('info', msg);
  }
}
