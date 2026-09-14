import {
  Component,
  inject,
  OnInit,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
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
  imports: [FormsModule, TranslateModule],
  templateUrl: './feature-flags.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './feature-flags.scss',
})
export class FeatureFlagsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

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
        this.toast.error(this.translate.instant('FLAGS.LOAD_FAILED'));
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
          this.toast.success(
            this.translate.instant(newEnabled ? 'FLAGS.FLAG_ENABLED' : 'FLAGS.FLAG_DISABLED', {
              key: flag.key,
            }),
          );
        },
        error: () =>
          this.toast.error(this.translate.instant('FLAGS.TOGGLE_FAILED', { key: flag.key })),
      });
  }

  removeOverride(flag: FeatureFlag): void {
    this.http
      .delete<Record<string, boolean>>(`/feature-flags/${encodeURIComponent(flag.key)}`)
      .subscribe({
        next: () => {
          this.loadFlags();
          this.toast.success(this.translate.instant('FLAGS.OVERRIDE_REMOVED', { key: flag.key }));
        },
        error: () =>
          this.toast.error(
            this.translate.instant('FLAGS.OVERRIDE_REMOVE_FAILED', { key: flag.key }),
          ),
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
      this.toast.error(this.translate.instant('FLAGS.KEY_REQUIRED'));
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
          this.toast.success(this.translate.instant('FLAGS.CREATED', { key }));
        },
        error: () => {
          this.saving.set(false);
          this.toast.error(this.translate.instant('FLAGS.CREATE_FAILED'));
        },
      });
  }
}
