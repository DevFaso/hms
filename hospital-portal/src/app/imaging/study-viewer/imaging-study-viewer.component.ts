import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { Subscription } from 'rxjs';
import {
  ImagingPacsService,
  ImagingStudyInstance,
  ImagingStudyViewer,
  ImagingViewerStatus,
} from '../../services/imaging-pacs.service';

/** The viewer's states the template words: the server's, plus the two of the request itself. */
export type ImagingViewerUiState = 'IDLE' | 'LOADING' | 'ERROR' | ImagingViewerStatus;

/**
 * The in-app image viewer of one imaging report (V317).
 *
 * Every frame comes through e-Keneya's own API (`/imaging/dicom/reports/{id}/...`),
 * already rendered to JPEG/PNG by the PACS (DICOMweb `/rendered`), so the
 * browser shows a plain image: no DICOM parser, no viewer bundle, no direct
 * connection to the PACS. Frames are fetched with the session as Blobs and
 * shown through object URLs, revoked as soon as they are replaced.
 *
 * Loads nothing until the clinician asks: a report list does not hit the PACS.
 */
@Component({
  selector: 'app-imaging-study-viewer',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './imaging-study-viewer.component.html',
  styleUrl: './imaging-study-viewer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImagingStudyViewerComponent {
  private readonly pacs = inject(ImagingPacsService);

  /** The report whose study is shown. */
  readonly reportId = input.required<string>();
  /** Whether the report already names its study (a study UID or an accession number). */
  readonly studyLinked = input(false);

  readonly state = signal<ImagingViewerUiState>('IDLE');
  readonly manifest = signal<ImagingStudyViewer | null>(null);
  readonly index = signal(0);
  readonly frame = signal(1);
  readonly imageUrl = signal<string | null>(null);
  readonly imageLoading = signal(false);
  readonly imageFailed = signal(false);
  readonly invert = signal(false);
  readonly brightness = signal(100);
  readonly contrast = signal(100);
  readonly zoom = signal(1);

  readonly instances = computed<ImagingStudyInstance[]>(() => this.manifest()?.instances ?? []);
  readonly current = computed<ImagingStudyInstance | null>(
    () => this.instances()[this.index()] ?? null,
  );
  /** The last position of the image slider (the manifest is the whole list the server sent). */
  readonly lastIndex = computed(() => Math.max(0, this.instances().length - 1));
  /** The pager ends: the first/last image, the first/last frame. */
  readonly hasPrev = computed(() => this.index() > 0);
  readonly hasNext = computed(() => this.index() < this.lastIndex());
  readonly frameHasPrev = computed(() => this.frame() > 1);
  readonly frameHasNext = computed(() => this.frame() < this.frameCount());
  readonly frameCount = computed(() => Math.max(1, this.current()?.numberOfFrames ?? 1));
  readonly imageFilter = computed(
    () =>
      `brightness(${this.brightness()}%) contrast(${this.contrast()}%)` +
      (this.invert() ? ' invert(1)' : ''),
  );

  private frameSub: Subscription | null = null;
  private manifestSub: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.manifestSub?.unsubscribe();
      this.frameSub?.unsubscribe();
      this.revoke();
    });
  }

  /** Asks the server for the study: its state and, when available, its images. */
  open(): void {
    this.manifestSub?.unsubscribe();
    this.state.set('LOADING');
    this.manifestSub = this.pacs.viewer(this.reportId()).subscribe({
      next: (m) => {
        this.manifest.set(m);
        this.state.set(m.status);
        this.index.set(0);
        this.frame.set(1);
        if (m.status === 'AVAILABLE' && m.instances.length > 0) {
          this.loadFrame();
        }
      },
      error: () => this.state.set('ERROR'),
    });
  }

  close(): void {
    this.manifestSub?.unsubscribe();
    this.frameSub?.unsubscribe();
    this.revoke();
    this.manifest.set(null);
    this.state.set('IDLE');
  }

  go(delta: number): void {
    const count = this.instances().length;
    if (count === 0) return;
    const next = Math.min(count - 1, Math.max(0, this.index() + delta));
    if (next === this.index()) return;
    this.index.set(next);
    this.frame.set(1);
    this.loadFrame();
  }

  goTo(position: number): void {
    const count = this.instances().length;
    const next = Math.min(count - 1, Math.max(0, Math.round(position)));
    if (Number.isNaN(next) || next === this.index()) return;
    this.index.set(next);
    this.frame.set(1);
    this.loadFrame();
  }

  goFrame(delta: number): void {
    const next = Math.min(this.frameCount(), Math.max(1, this.frame() + delta));
    if (next === this.frame()) return;
    this.frame.set(next);
    this.loadFrame();
  }

  onKey(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        event.preventDefault();
        this.go(1);
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        event.preventDefault();
        this.go(-1);
        break;
      case 'PageDown':
        event.preventDefault();
        this.goFrame(1);
        break;
      case 'PageUp':
        event.preventDefault();
        this.goFrame(-1);
        break;
    }
  }

  setBrightness(value: string): void {
    this.brightness.set(clamp(Number(value), 20, 300));
  }

  setContrast(value: string): void {
    this.contrast.set(clamp(Number(value), 20, 300));
  }

  zoomBy(factor: number): void {
    this.zoom.set(clamp(Math.round(this.zoom() * factor * 100) / 100, 0.5, 4));
  }

  reset(): void {
    this.invert.set(false);
    this.brightness.set(100);
    this.contrast.set(100);
    this.zoom.set(1);
  }

  private loadFrame(): void {
    const instance = this.current();
    if (!instance) return;
    this.frameSub?.unsubscribe();
    this.imageLoading.set(true);
    this.imageFailed.set(false);
    this.frameSub = this.pacs.frame(this.reportId(), instance, this.frame()).subscribe({
      next: (blob) => {
        this.revoke();
        this.imageUrl.set(URL.createObjectURL(blob));
        this.imageLoading.set(false);
      },
      error: () => {
        this.revoke();
        this.imageLoading.set(false);
        this.imageFailed.set(true);
      },
    });
  }

  private revoke(): void {
    const url = this.imageUrl();
    if (url) {
      URL.revokeObjectURL(url);
      this.imageUrl.set(null);
    }
  }
}

function clamp(value: number, min: number, max: number): number {
  if (Number.isNaN(value)) return min;
  return Math.min(max, Math.max(min, value));
}
