import {
  Component,
  ElementRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { AnnouncementService, AnnouncementResponse } from '../services/announcement.service';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { refusalReason } from '../shared/refusal-reason';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
import { refuseFields } from '../shared/forms/invalid-fields';

const TEXT_FIELD_ID = 'announcementText';

@Component({
  selector: 'app-announcement-list',
  standalone: true,
  imports: [
    DateTimePipe,
    SubmitGuardDirective,
    ModalDialogDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
  ],
  templateUrl: './announcement-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './announcement-list.scss',
})
export class AnnouncementListComponent implements OnInit {
  private readonly announcementService = inject(AnnouncementService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** True when the logged-in user has an admin-level role */
  get canManage(): boolean {
    const roles = this.auth.getUserProfile()?.roles ?? [];
    return roles.some((r) => ['ROLE_SUPER_ADMIN', 'ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN'].includes(r));
  }

  /**
   * Whether this reader may change this announcement. A platform one is listed at
   * every hospital but changed only by a platform administrator in global view
   * (the server answers anybody else as if it did not exist).
   */
  canEdit(announcement: AnnouncementResponse): boolean {
    if (!this.canManage) return false;
    if (!announcement.platformWide) return true;
    return this.roleContext.isSuperAdmin() && this.roleContext.globalView();
  }

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
        this.toast.error(this.translate.instant('ANNOUNCEMENTS.TOAST.LOAD_FAILED'));
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
    this.filtered.set(this.announcements().filter((a) => a.text.toLowerCase().includes(term)));
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

  /** The length of the announcement text the server accepts (AnnouncementServiceImpl). */
  readonly maxTextLength = 255;

  submitForm(): void {
    if (!this.formText.trim()) {
      const message = this.translate.instant('ANNOUNCEMENTS.TOAST.TEXT_REQUIRED');
      refuseFields(this.host.nativeElement, [TEXT_FIELD_ID], [TEXT_FIELD_ID], message);
      this.toast.error(message);
      return;
    }
    // QA D4321: a pasted text can exceed what the field lets one type.
    if (this.formText.trim().length > this.maxTextLength) {
      const message = this.translate.instant('ANNOUNCEMENTS.TOAST.TEXT_TOO_LONG', {
        max: this.maxTextLength,
      });
      refuseFields(this.host.nativeElement, [TEXT_FIELD_ID], [TEXT_FIELD_ID], message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], [TEXT_FIELD_ID]);
    this.saving.set(true);
    const existing = this.editing();
    const op = existing
      ? this.announcementService.update(existing.id, this.formText.trim())
      : this.announcementService.create(this.formText.trim());

    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            existing ? 'ANNOUNCEMENTS.TOAST.UPDATED' : 'ANNOUNCEMENTS.TOAST.CREATED',
          ),
        );
        this.showModal.set(false);
        this.saving.set(false);
        this.editing.set(null);
        this.loadAnnouncements();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ANNOUNCEMENTS.TOAST.SAVE_FAILED'),
        );
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
        this.toast.success(this.translate.instant('ANNOUNCEMENTS.TOAST.DELETED'));
        this.showDeleteConfirm.set(false);
        this.deleting.set(false);
        this.deletingAnnouncement.set(null);
        this.loadAnnouncements();
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('ANNOUNCEMENTS.TOAST.DELETE_FAILED'),
        );
        this.deleting.set(false);
      },
    });
  }
}
