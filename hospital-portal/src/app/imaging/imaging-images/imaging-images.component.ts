import {
  ChangeDetectionStrategy,
  Component,
  Input,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  computed,
  inject,
  signal,
} from '@angular/core';
import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeResourceUrl, SafeUrl } from '@angular/platform-browser';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

import { ToastService } from '../../core/toast.service';
import {
  IMAGING_IMAGE_EXTENSIONS,
  IMAGING_IMAGE_MAX_BYTES,
  ImagingImagesService,
  ImagingStudyImage,
} from '../../services/imaging-images.service';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { blobRefusalReason } from '../../shared/blob-refusal';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { MeasurePipe } from '../../shared/pipes/measure.pipe';

/** One file on its way up. */
interface PendingUpload {
  key: number;
  name: string;
  /** 0–100. */
  progress: number;
  error: string | null;
}

const MIN_ZOOM = 1;
const MAX_ZOOM = 6;
const ZOOM_STEP = 0.5;
const PAN_STEP = 40;

/**
 * Key images of an imaging study (owner 2026-11-19, V316): the thumbnail
 * strip, the upload control (drag-drop or file picker, with progress and the
 * refusal in the reader's language) and a viewer (zoom, pan, previous/next;
 * a PDF opens inline).
 *
 * The bytes are fetched as authenticated blobs: an `<img src>` URL carries no
 * bearer token, and the server streams them only to a reader of the report.
 * Every object URL made here is revoked when the strip is destroyed or reloaded.
 */
@Component({
  selector: 'app-imaging-images',
  standalone: true,
  imports: [
    FormsModule,
    TranslateModule,
    ModalDialogDirective,
    DateTimePipe,
    ServerTimePipe,
    MeasurePipe,
  ],
  templateUrl: './imaging-images.component.html',
  styleUrl: './imaging-images.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImagingImagesComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) orderId!: string;
  /** Uploads go on this report when set (an unsigned report or addendum), else on the study. */
  @Input() reportId: string | null = null;
  @Input() canUpload = false;
  /** A heading level fitting where the strip sits. */
  @Input() compact = false;

  private readonly imagesService = inject(ImagingImagesService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly translate = inject(TranslateService);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  readonly images = signal<ImagingStudyImage[]>([]);
  readonly loading = signal(false);
  readonly loadFailed = signal(false);
  readonly thumbs = signal<Record<string, SafeUrl>>({});
  readonly uploads = signal<PendingUpload[]>([]);
  readonly dragOver = signal(false);
  caption = '';

  readonly viewerIndex = signal<number | null>(null);
  readonly zoom = signal(1);
  readonly panX = signal(0);
  readonly panY = signal(0);
  readonly viewerPdf = signal<SafeResourceUrl | null>(null);
  readonly viewerImage = signal<SafeUrl | null>(null);
  readonly viewerLoading = signal(false);

  readonly current = computed(() => {
    const index = this.viewerIndex();
    return index === null ? null : (this.images()[index] ?? null);
  });
  readonly transform = computed(
    () => `translate(${this.panX()}px, ${this.panY()}px) scale(${this.zoom()})`,
  );
  readonly accept =
    IMAGING_IMAGE_EXTENSIONS.join(',') + ',image/jpeg,image/png,image/webp,application/pdf';
  readonly inputId = `imaging-images-input-${Math.random().toString(36).slice(2, 9)}`;
  readonly captionId = `${this.inputId}-caption`;
  readonly viewerTitleId = `${this.inputId}-viewer-title`;

  private objectUrls = new Map<string, string>();
  private viewerUrl: string | null = null;
  private listSub: Subscription | null = null;
  private readonly subs = new Subscription();
  private uploadKey = 0;
  private dragStart: { x: number; y: number; panX: number; panY: number } | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['orderId'] && this.orderId) {
      this.load();
    }
  }

  ngOnDestroy(): void {
    this.listSub?.unsubscribe();
    this.subs.unsubscribe();
    this.revokeAll();
    this.clearViewerUrl();
  }

  load(): void {
    this.listSub?.unsubscribe();
    this.loading.set(true);
    this.loadFailed.set(false);
    this.listSub = this.imagesService.list(this.orderId).subscribe({
      next: (images) => {
        this.loading.set(false);
        this.revokeAll();
        this.thumbs.set({});
        this.images.set(images);
        for (const image of images) {
          if (this.isImage(image)) this.fetchThumb(image);
        }
      },
      error: () => {
        this.loading.set(false);
        this.loadFailed.set(true);
      },
    });
  }

  isImage(image: ImagingStudyImage): boolean {
    return image.contentType.startsWith('image/');
  }

  /** The alt text: the caption, else "Image N of the study". */
  altOf(image: ImagingStudyImage, index: number): string {
    return (
      image.caption?.trim() ||
      this.translate.instant('IMAGING_IMAGES.IMAGE_N', {
        n: index + 1,
        total: this.images().length,
      })
    );
  }

  // ── Upload ──────────────────────────────────────────────────────────

  onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.uploadFiles(Array.from(input.files ?? []));
    input.value = '';
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(true);
  }

  onDragLeave(): void {
    this.dragOver.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    this.uploadFiles(Array.from(event.dataTransfer?.files ?? []));
  }

  /** The refusal key of a file the server would refuse, or null. */
  static refusalOf(file: File): string | null {
    const dot = file.name.lastIndexOf('.');
    const extension = dot < 0 ? '' : file.name.slice(dot).toLowerCase();
    if (!IMAGING_IMAGE_EXTENSIONS.includes(extension)) return 'IMAGING_IMAGES.ERROR_TYPE';
    if (file.size === 0) return 'IMAGING_IMAGES.ERROR_EMPTY';
    if (file.size > IMAGING_IMAGE_MAX_BYTES) return 'IMAGING_IMAGES.ERROR_SIZE';
    return null;
  }

  private uploadFiles(files: File[]): void {
    if (!this.canUpload) return;
    const caption = this.caption;
    for (const file of files) {
      const key = ++this.uploadKey;
      const refusal = ImagingImagesComponent.refusalOf(file);
      this.uploads.update((list) => [
        ...list,
        {
          key,
          name: file.name,
          progress: 0,
          error: refusal ? this.translate.instant(refusal, { max: '10 MB' }) : null,
        },
      ]);
      if (refusal) continue;
      this.subs.add(
        this.imagesService.upload(this.orderId, file, this.reportId, caption).subscribe({
          next: (event) => {
            if (event.type === HttpEventType.UploadProgress && event.total) {
              this.setUpload(key, { progress: Math.round((100 * event.loaded) / event.total) });
            } else if (event.type === HttpEventType.Response) {
              this.uploads.update((list) => list.filter((u) => u.key !== key));
              this.load();
            }
          },
          error: (err: unknown) => {
            this.setUpload(key, {
              error:
                this.serverMessage(err) ?? this.translate.instant('IMAGING_IMAGES.ERROR_UPLOAD'),
            });
          },
        }),
      );
    }
    this.caption = '';
  }

  dismissUpload(key: number): void {
    this.uploads.update((list) => list.filter((u) => u.key !== key));
  }

  private setUpload(key: number, patch: Partial<PendingUpload>): void {
    this.uploads.update((list) => list.map((u) => (u.key === key ? { ...u, ...patch } : u)));
  }

  // ── Removal ─────────────────────────────────────────────────────────

  async remove(image: ImagingStudyImage): Promise<void> {
    if (
      !(await this.confirm.ask({
        titleKey: 'IMAGING_IMAGES.DELETE_CONFIRM',
        confirmKey: 'COMMON.DELETE',
      }))
    ) {
      return;
    }
    this.subs.add(
      this.imagesService.delete(image.id).subscribe({
        next: () => {
          this.toast.success(this.translate.instant('IMAGING_IMAGES.DELETED'));
          if (this.viewerIndex() !== null) this.closeViewer();
          this.load();
        },
        error: (err: unknown) =>
          this.toast.error(
            this.serverMessage(err) ?? this.translate.instant('IMAGING_IMAGES.ERROR_DELETE'),
          ),
      }),
    );
  }

  // ── Viewer ──────────────────────────────────────────────────────────

  openViewer(index: number): void {
    this.viewerIndex.set(index);
    this.showCurrent();
  }

  closeViewer(): void {
    this.viewerIndex.set(null);
    this.clearViewerUrl();
  }

  next(): void {
    this.step(1);
  }

  previous(): void {
    this.step(-1);
  }

  private step(delta: number): void {
    const index = this.viewerIndex();
    const count = this.images().length;
    if (index === null || count < 2) return;
    this.viewerIndex.set((index + delta + count) % count);
    this.showCurrent();
  }

  zoomIn(): void {
    this.zoom.update((z) => Math.min(MAX_ZOOM, z + ZOOM_STEP));
  }

  zoomOut(): void {
    this.zoom.update((z) => Math.max(MIN_ZOOM, z - ZOOM_STEP));
    if (this.zoom() === MIN_ZOOM) this.resetPan();
  }

  resetZoom(): void {
    this.zoom.set(MIN_ZOOM);
    this.resetPan();
  }

  /** Keyboard contract: ←/→ previous/next (pan when zoomed), ↑/↓ pan, +/− zoom, 0 resets. */
  onViewerKey(event: KeyboardEvent): void {
    const zoomed = this.zoom() > MIN_ZOOM;
    switch (event.key) {
      case 'ArrowLeft':
        if (zoomed) this.panX.update((x) => x + PAN_STEP);
        else this.previous();
        break;
      case 'ArrowRight':
        if (zoomed) this.panX.update((x) => x - PAN_STEP);
        else this.next();
        break;
      case 'ArrowUp':
        if (!zoomed) return;
        this.panY.update((y) => y + PAN_STEP);
        break;
      case 'ArrowDown':
        if (!zoomed) return;
        this.panY.update((y) => y - PAN_STEP);
        break;
      case '+':
      case '=':
        this.zoomIn();
        break;
      case '-':
        this.zoomOut();
        break;
      case '0':
        this.resetZoom();
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  onWheel(event: WheelEvent): void {
    event.preventDefault();
    if (event.deltaY < 0) this.zoomIn();
    else this.zoomOut();
  }

  onPointerDown(event: PointerEvent): void {
    if (this.zoom() <= MIN_ZOOM) return;
    this.dragStart = { x: event.clientX, y: event.clientY, panX: this.panX(), panY: this.panY() };
    (event.target as Element).setPointerCapture?.(event.pointerId);
  }

  onPointerMove(event: PointerEvent): void {
    if (!this.dragStart) return;
    this.panX.set(this.dragStart.panX + event.clientX - this.dragStart.x);
    this.panY.set(this.dragStart.panY + event.clientY - this.dragStart.y);
  }

  onPointerUp(): void {
    this.dragStart = null;
  }

  private resetPan(): void {
    this.panX.set(0);
    this.panY.set(0);
  }

  private showCurrent(): void {
    this.resetZoom();
    this.clearViewerUrl();
    const image = this.current();
    if (!image) return;
    const known = this.objectUrls.get(image.id);
    if (known) {
      this.viewerImage.set(this.sanitizer.bypassSecurityTrustUrl(known));
      return;
    }
    this.viewerLoading.set(true);
    this.subs.add(
      this.imagesService.content(image.id).subscribe({
        next: (blob) => {
          this.viewerLoading.set(false);
          if (this.current()?.id !== image.id) return;
          const url = URL.createObjectURL(blob);
          if (this.isImage(image)) {
            this.objectUrls.set(image.id, url);
            this.viewerImage.set(this.sanitizer.bypassSecurityTrustUrl(url));
          } else {
            this.viewerUrl = url;
            // A blob URL this component made from the server's own bytes, served as a PDF.
            this.viewerPdf.set(this.sanitizer.bypassSecurityTrustResourceUrl(url));
          }
        },
        error: (err: unknown) => {
          this.viewerLoading.set(false);
          void blobRefusalReason(err).then((reason) =>
            this.toast.error(reason ?? this.translate.instant('IMAGING_IMAGES.ERROR_VIEW')),
          );
        },
      }),
    );
  }

  private fetchThumb(image: ImagingStudyImage): void {
    this.subs.add(
      this.imagesService.content(image.id).subscribe({
        next: (blob) => {
          if (!this.images().some((i) => i.id === image.id)) return;
          const url = URL.createObjectURL(blob);
          this.objectUrls.set(image.id, url);
          this.thumbs.update((t) => ({
            ...t,
            [image.id]: this.sanitizer.bypassSecurityTrustUrl(url),
          }));
        },
        // The tile keeps its placeholder; opening it says why.
        error: () => undefined,
      }),
    );
  }

  private clearViewerUrl(): void {
    this.viewerImage.set(null);
    this.viewerPdf.set(null);
    if (this.viewerUrl) {
      URL.revokeObjectURL(this.viewerUrl);
      this.viewerUrl = null;
    }
  }

  private revokeAll(): void {
    for (const url of this.objectUrls.values()) URL.revokeObjectURL(url);
    this.objectUrls.clear();
  }

  private serverMessage(err: unknown): string | null {
    if (err instanceof HttpErrorResponse && err.status >= 400 && err.status < 500) {
      const message = (err.error as { message?: unknown } | null)?.message;
      return typeof message === 'string' && message.trim() ? message : null;
    }
    return null;
  }
}
