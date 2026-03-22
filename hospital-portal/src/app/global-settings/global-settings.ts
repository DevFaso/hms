import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  GlobalSettingService,
  GlobalSettingResponse,
  GlobalSettingRequest,
} from '../services/global-setting.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-global-settings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './global-settings.html',
  styleUrl: './global-settings.scss',
})
export class GlobalSettingsComponent implements OnInit {
  private readonly settingService = inject(GlobalSettingService);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  settings = signal<GlobalSettingResponse[]>([]);
  search = signal('');
  categoryFilter = signal('');

  showModal = signal(false);
  editing = signal<GlobalSettingResponse | null>(null);
  saving = signal(false);
  form: GlobalSettingRequest = { settingKey: '', settingValue: '' };

  showDeleteConfirm = signal(false);
  deletingSetting = signal<GlobalSettingResponse | null>(null);
  deleting = signal(false);

  categories = computed(() => {
    const cats = new Set(
      this.settings()
        .map((s) => s.category)
        .filter(Boolean),
    );
    return Array.from(cats).sort();
  });

  filteredSettings = computed(() => {
    const q = this.search().toLowerCase();
    const cat = this.categoryFilter();
    return this.settings().filter((s) => {
      const matchSearch =
        !q ||
        s.settingKey.toLowerCase().includes(q) ||
        (s.settingValue?.toLowerCase().includes(q) ?? false) ||
        (s.description?.toLowerCase().includes(q) ?? false);
      const matchCat = !cat || s.category === cat;
      return matchSearch && matchCat;
    });
  });

  ngOnInit(): void {
    this.loadSettings();
  }

  loadSettings(): void {
    this.loading.set(true);
    this.settingService.list().subscribe({
      next: (list) => {
        this.settings.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load settings');
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.form = { settingKey: '', settingValue: '', category: '', description: '' };
    this.editing.set(null);
    this.showModal.set(true);
  }

  openEdit(setting: GlobalSettingResponse): void {
    this.editing.set(setting);
    this.form = {
      settingKey: setting.settingKey,
      settingValue: setting.settingValue ?? '',
      category: setting.category ?? '',
      description: setting.description ?? '',
    };
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    this.editing.set(null);
  }

  submitSetting(): void {
    if (!this.form.settingKey.trim()) {
      this.toast.error('Setting key is required');
      return;
    }
    this.saving.set(true);
    this.settingService.upsert(this.form).subscribe({
      next: () => {
        this.toast.success(this.editing() ? 'Setting updated' : 'Setting created');
        this.closeModal();
        this.saving.set(false);
        this.loadSettings();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Operation failed');
        this.saving.set(false);
      },
    });
  }

  confirmDelete(setting: GlobalSettingResponse): void {
    this.deletingSetting.set(setting);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingSetting.set(null);
  }

  executeDelete(): void {
    const setting = this.deletingSetting();
    if (!setting) return;
    this.deleting.set(true);
    this.settingService.delete(setting.id).subscribe({
      next: () => {
        this.toast.success('Setting deleted');
        this.showDeleteConfirm.set(false);
        this.deleting.set(false);
        this.deletingSetting.set(null);
        this.loadSettings();
      },
      error: () => {
        this.toast.error('Failed to delete setting');
        this.deleting.set(false);
      },
    });
  }
}
