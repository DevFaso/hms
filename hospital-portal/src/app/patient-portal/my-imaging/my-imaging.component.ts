import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientImagingImage,
  PatientImagingReport,
  PatientImagingService,
  PatientImagingStudy,
} from '../../services/patient-imaging.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { printOnly } from '../../shared/print/print-only';
import { DialogA11yDirective } from '../../shared/a11y/dialog-a11y.directive';
import { refusalReason } from '../../shared/refusal-reason';

/**
 * QA D2342 (r63, High): « Imagerie », the patient's released imaging reports.
 *
 * Each study lists its released reports (the signed original, then its signed
 * addenda) with the signer and the signing date, and the key images released
 * with it (#1223). Nothing unsigned ever reaches this page: the server lists
 * released reports only, as the patient's copy. Images are read as
 * authenticated blobs from the patient's own endpoint, never a public URL.
 */
@Component({
  selector: 'app-my-imaging',
  standalone: true,
  imports: [TranslateModule, EnumLabelPipe, ServerTimePipe, DateTimePipe, DialogA11yDirective],
  templateUrl: './my-imaging.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./my-imaging.component.scss', '../patient-portal-pages.scss'],
})
export class MyImagingComponent implements OnInit {
  private readonly imaging = inject(PatientImagingService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly studies = signal<PatientImagingStudy[]>([]);
  readonly loading = signal(true);
  readonly failed = signal(false);
  readonly failureReason = signal<string | null>(null);
  readonly empty = computed(() => !this.loading() && !this.failed() && this.studies().length === 0);

  /** Image id → object URL of its bytes, once read. */
  readonly thumbs = signal<Record<string, SafeUrl>>({});
  /** The image open full size, or null. */
  readonly viewer = signal<{ url: SafeUrl; caption: string | null } | null>(null);

  private readonly objectUrls = new Map<string, string>();

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      for (const url of this.objectUrls.values()) URL.revokeObjectURL(url);
      this.objectUrls.clear();
    });
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.failureReason.set(null);
    this.imaging.studies().subscribe({
      next: (list) => {
        this.studies.set(list ?? []);
        this.loading.set(false);
        for (const study of list ?? []) {
          for (const image of study.images ?? []) {
            if (this.isPicture(image)) this.readThumb(image);
          }
        }
      },
      error: (err: unknown) => {
        this.failureReason.set(refusalReason(err));
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  isPicture(image: PatientImagingImage): boolean {
    return image.contentType.startsWith('image/');
  }

  /** The original read is "Compte rendu", a later signed version is labelled by its kind. */
  isAddendum(report: PatientImagingReport, index: number): boolean {
    return index > 0 || report.reportStatus === 'ADDENDUM';
  }

  studyTitle(study: PatientImagingStudy): string | null {
    return study.studyType || study.reports[0]?.reportTitle || null;
  }

  private readThumb(image: PatientImagingImage): void {
    if (this.objectUrls.has(image.id)) return;
    this.imaging.image(image.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        this.objectUrls.set(image.id, url);
        this.thumbs.update((all) => ({
          ...all,
          [image.id]: this.sanitizer.bypassSecurityTrustUrl(url),
        }));
      },
      // A picture that cannot be read leaves its placeholder; the report text stays.
      error: () => undefined,
    });
  }

  openImage(image: PatientImagingImage): void {
    const known = this.objectUrls.get(image.id);
    if (known && this.isPicture(image)) {
      this.viewer.set({
        url: this.sanitizer.bypassSecurityTrustUrl(known),
        caption: image.caption ?? null,
      });
      return;
    }
    // A PDF opens in the browser's own viewer, from the authenticated bytes.
    this.imaging.image(image.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        this.objectUrls.set(image.id, url);
        if (this.isPicture(image)) {
          this.viewer.set({
            url: this.sanitizer.bypassSecurityTrustUrl(url),
            caption: image.caption ?? null,
          });
        } else {
          window.open(url, '_blank', 'noopener');
        }
      },
      error: () => undefined,
    });
  }

  closeViewer(): void {
    this.viewer.set(null);
  }

  /** Prints this study's reports alone (shared print isolation). */
  print(study: PatientImagingStudy): void {
    const card = this.host.nativeElement.querySelector<HTMLElement>(
      `[data-study-id="${CSS.escape(study.imagingOrderId)}"]`,
    );
    if (card) printOnly(card);
  }
}
