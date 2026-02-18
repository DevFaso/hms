import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Injectable({ providedIn: 'root' })
export class FeatureFlagsService {
  private readonly http = inject(HttpClient);
  private readonly flags = signal<Record<string, boolean>>({});
  private loaded = false;

  load(): void {
    if (this.loaded) return;
    this.loaded = true;
    this.http.get<Record<string, boolean>>('/feature-flags').subscribe({
      next: (data) => this.flags.set(data),
      error: () => {
        // Feature flags endpoint not available, use defaults
      },
    });
  }

  isEnabled(flag: string): boolean {
    return this.flags()[flag] ?? false;
  }

  getAll(): Record<string, boolean> {
    return this.flags();
  }
}
