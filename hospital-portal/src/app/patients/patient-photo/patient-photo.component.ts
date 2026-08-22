import {
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../../core/toast.service';
import { RegistrationExtrasService } from '../../services/registration-extras.service';

/**
 * Patient photo avatar + capture modal (P3 #21).
 *
 * The binary is fetched as an authenticated blob — an <img src> URL would
 * carry no bearer token, and the photo endpoint is deliberately NOT the
 * permitAll /uploads/** static path (a patient photo is PHI). Capture
 * offers file upload and a webcam frame-grab (the eMAR scanner's
 * getUserMedia pattern plus the canvas half it never needed).
 */
@Component({
  selector: 'app-patient-photo',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './patient-photo.component.html',
  styleUrl: './patient-photo.component.scss',
})
export class PatientPhotoComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) patientId!: string;
  @Input() photoUpdatedAt: string | null = null;
  @Input() initials = '?';
  @Input() canEdit = false;

  private readonly extrasService = inject(RegistrationExtrasService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  @ViewChild('cameraVideo') cameraVideo?: ElementRef<HTMLVideoElement>;

  readonly photoUrl = signal<SafeUrl | null>(null);
  readonly showModal = signal(false);
  readonly saving = signal(false);
  readonly cameraActive = signal(false);
  readonly cameraError = signal('');

  private objectUrl: string | null = null;
  private mediaStream: MediaStream | null = null;
  private hasPhoto = false;

  ngOnChanges(): void {
    const nowHasPhoto = !!this.photoUpdatedAt;
    if (nowHasPhoto && !this.hasPhoto) {
      this.loadPhoto();
    }
    this.hasPhoto = nowHasPhoto;
  }

  ngOnDestroy(): void {
    this.releaseObjectUrl();
    this.stopCamera();
  }

  private loadPhoto(): void {
    this.extrasService.getPhotoBlob(this.patientId).subscribe({
      next: (blob) => {
        this.releaseObjectUrl();
        this.objectUrl = URL.createObjectURL(blob);
        this.photoUrl.set(this.sanitizer.bypassSecurityTrustUrl(this.objectUrl));
      },
      error: () => {
        // A missing photo renders as initials — not an error surface.
        this.photoUrl.set(null);
      },
    });
  }

  private releaseObjectUrl(): void {
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
      this.objectUrl = null;
    }
  }

  openModal(): void {
    if (!this.canEdit) return;
    this.cameraError.set('');
    this.showModal.set(true);
  }

  closeModal(): void {
    if (this.saving()) return;
    this.stopCamera();
    this.showModal.set(false);
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      this.upload(file, file.name);
    }
    input.value = '';
  }

  async startCamera(): Promise<void> {
    this.cameraError.set('');
    if (!navigator.mediaDevices?.getUserMedia) {
      this.cameraError.set(this.translate.instant('PATIENT_PHOTO.CAMERA_UNSUPPORTED'));
      return;
    }
    try {
      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'user' },
      });
      this.cameraActive.set(true);
      // The video element appears on the next change-detection pass.
      setTimeout(() => {
        const video = this.cameraVideo?.nativeElement;
        if (video && this.mediaStream) {
          video.srcObject = this.mediaStream;
          video.play().catch(() => {
            /* autoplay refusal surfaces as a black frame; capture still works */
          });
        }
      });
    } catch {
      this.cameraError.set(this.translate.instant('PATIENT_PHOTO.CAMERA_DENIED'));
    }
  }

  stopCamera(): void {
    this.mediaStream?.getTracks().forEach((track) => track.stop());
    this.mediaStream = null;
    this.cameraActive.set(false);
  }

  captureFrame(): void {
    const video = this.cameraVideo?.nativeElement;
    if (!video || video.videoWidth === 0) {
      this.cameraError.set(this.translate.instant('PATIENT_PHOTO.CAPTURE_FAILED'));
      return;
    }
    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    canvas.getContext('2d')?.drawImage(video, 0, 0);
    canvas.toBlob(
      (blob) => {
        if (blob) {
          this.stopCamera();
          this.upload(blob, 'webcam-capture.jpg');
        } else {
          this.cameraError.set(this.translate.instant('PATIENT_PHOTO.CAPTURE_FAILED'));
        }
      },
      'image/jpeg',
      0.9,
    );
  }

  private upload(file: Blob, filename: string): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.extrasService.uploadPhoto(this.patientId, file, filename).subscribe({
      next: () => {
        this.saving.set(false);
        this.showModal.set(false);
        this.toast.success(this.translate.instant('PATIENT_PHOTO.SAVED'));
        this.loadPhoto();
        this.hasPhoto = true;
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PATIENT_PHOTO.SAVE_FAILED'),
        );
      },
    });
  }

  removePhoto(): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.extrasService.deletePhoto(this.patientId).subscribe({
      next: () => {
        this.saving.set(false);
        this.showModal.set(false);
        this.releaseObjectUrl();
        this.photoUrl.set(null);
        this.hasPhoto = false;
        this.toast.success(this.translate.instant('PATIENT_PHOTO.REMOVED'));
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(
          err?.error?.message ?? this.translate.instant('PATIENT_PHOTO.SAVE_FAILED'),
        );
      },
    });
  }
}
