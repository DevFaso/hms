import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AnnouncementService, AnnouncementResponse } from '../services/announcement.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-announcement-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './announcement-list.html',
  styleUrl: './announcement-list.scss',
})
export class AnnouncementListComponent implements OnInit {
  private readonly announcementService = inject(AnnouncementService);
  private readonly toast = inject(ToastService);

  announcements = signal<AnnouncementResponse[]>([]);
  filtered = signal<AnnouncementResponse[]>([]);
  loading = signal(true);
  searchTerm = '';

  // Create / Edit
  showModal = signal(false);
  editing = signal<AnnouncementResponse | null>(null);
  saving = signal(false);
  formText = '';

  // Delete
  showDeleteConfirm = signal(false);
  deletingAnnouncement = signal<AnnouncementResponse | null>(null);
  deleting = signal(false);

  ngOnInit(): void {
    this.loadAnnouncements();
  }

  loadAnnouncements(): void {
    this.loading.set(true);
    this.announcementService.list().subscribe({
      next: (data) => {
        this.announcements.set(data);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load announcements');
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.announcements());
      return;
    }
    this.filtered.set(
      this.announcements().filter((a) => a.text.toLowerCase().includes(term)),
    );
  }

  // ---------- Create ----------
  openCreate(): void {
    this.formText = '';
    this.editing.set(null);
    this.showModal.set(true);
  }

  // ---------- Edit ----------
  openEdit(announcement: AnnouncementResponse): void {
    this.editing.set(announcement);
    this.formText = announcement.text;
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
    this.editing.set(null);
  }

  submitForm(): void {
    if (!this.formText.trim()) {
      this.toast.error('Announcement text is required');
      return;
    }
    this.saving.set(true);
    const existing = this.editing();
    const op = existing
      ? this.announcementService.update(existing.id, this.formText.trim())
      : this.announcementService.create(this.formText.trim());

    op.subscribe({
      next: () => {
        this.toast.success(existing ? 'Announcement updated' : 'Announcement created');
        this.showModal.set(false);
        this.saving.set(false);
        this.editing.set(null);
        this.loadAnnouncements();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Operation failed');
        this.saving.set(false);
      },
    });
  }

  // ---------- Delete ----------
  confirmDelete(announcement: AnnouncementResponse): void {
    this.deletingAnnouncement.set(announcement);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingAnnouncement.set(null);
  }

  executeDelete(): void {
    const a = this.deletingAnnouncement();
    if (!a) return;
    this.deleting.set(true);
    this.announcementService.delete(a.id).subscribe({
      next: () => {
        this.toast.success('Announcement deleted');
        this.showDeleteConfirm.set(false);
        this.deleting.set(false);
        this.deletingAnnouncement.set(null);
        this.loadAnnouncements();
      },
      error: () => {
        this.toast.error('Failed to delete announcement');
        this.deleting.set(false);
      },
    });
  }
}
