import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { HttpClient } from '@angular/common/http';
import { ToastService } from '../core/toast.service';

interface FeatureFlag {
  key: string;
  enabled: boolean;
  editing: boolean;
  description: string;
}

interface FlagOverride {
  id: string;
  flagKey: string;
  enabled: boolean;
  description: string;
  updatedBy: string;
  updatedAt: string;
}

@Component({
  selector: 'app-feature-flags',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './feature-flags.html',
  styleUrl: './feature-flags.scss',
})
export class FeatureFlagsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  flags = signal<FeatureFlag[]>([]);
  overrides = signal<FlagOverride[]>([]);
  search = signal('');
  showCreateModal = signal(false);
  newFlagKey = signal('');
  newFlagDescription = signal('');
  newFlagEnabled = signal(false);
  saving = signal(false);

  filteredFlags = computed(() => {
    const q = this.search().toLowerCase();
    return this.flags().filter(
      (f) => f.key.toLowerCase().includes(q) || f.description.toLowerCase().includes(q),
    );
  });

  enabledCount = computed(() => this.flags().filter((f) => f.enabled).length);
  disabledCount = computed(() => this.flags().filter((f) => !f.enabled).length);

  ngOnInit(): void {
    this.loadFlags();
    this.loadOverrides();
  }

  loadFlags(): void {
    this.loading.set(true);
    this.http.get<Record<string, boolean>>('/feature-flags').subscribe({
      next: (data) => {
        const list: FeatureFlag[] = Object.entries(data).map(([key, enabled]) => ({
          key,
          enabled,
          editing: false,
          description: '',
        }));
        list.sort((a, b) => a.key.localeCompare(b.key));
        this.flags.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load feature flags');
        this.loading.set(false);
      },
    });
  }

  loadOverrides(): void {
    this.http.get<FlagOverride[]>('/feature-flags/overrides').subscribe({
      next: (data) => this.overrides.set(data ?? []),
      error: () => {
        // silently ignore override loading errors
      },
    });
  }

  toggleFlag(flag: FeatureFlag): void {
    const newEnabled = !flag.enabled;
    this.http
      .put<Record<string, boolean>>(`/feature-flags/${encodeURIComponent(flag.key)}`, {
        enabled: newEnabled,
        description: flag.description || `Toggled ${flag.key}`,
      })
      .subscribe({
        next: () => {
          this.flags.update((list) =>
            list.map((f) => (f.key === flag.key ? { ...f, enabled: newEnabled } : f)),
          );
          this.toast.success(`${flag.key} ${newEnabled ? 'enabled' : 'disabled'}`);
        },
        error: () => this.toast.error(`Failed to toggle ${flag.key}`),
      });
  }

  removeOverride(flag: FeatureFlag): void {
    this.http
      .delete<Record<string, boolean>>(`/feature-flags/${encodeURIComponent(flag.key)}`)
      .subscribe({
        next: () => {
          this.loadFlags();
          this.toast.success(`Override removed for ${flag.key}`);
        },
        error: () => this.toast.error(`Failed to remove override for ${flag.key}`),
      });
  }

  openCreate(): void {
    this.newFlagKey.set('');
    this.newFlagDescription.set('');
    this.newFlagEnabled.set(false);
    this.showCreateModal.set(true);
  }

  closeCreate(): void {
    this.showCreateModal.set(false);
  }

  createFlag(): void {
    const key = this.newFlagKey().trim();
    if (!key) {
      this.toast.error('Flag key is required');
      return;
    }
    this.saving.set(true);
    this.http
      .put<Record<string, boolean>>(`/feature-flags/${encodeURIComponent(key)}`, {
        enabled: this.newFlagEnabled(),
        description: this.newFlagDescription().trim() || key,
      })
      .subscribe({
        next: () => {
          this.loadFlags();
          this.closeCreate();
          this.saving.set(false);
          this.toast.success(`Flag "${key}" created`);
        },
        error: () => {
          this.saving.set(false);
          this.toast.error('Failed to create flag');
        },
      });
  }
}
