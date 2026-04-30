import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, Subscription, takeUntil } from 'rxjs';

import {
  FiveRightsCheck,
  MarVerificationRequest,
  MarVerificationResponse,
  NurseMedicationTask,
  NurseTaskService,
} from '../../services/nurse-task.service';
import { ToastService } from '../../core/toast.service';

type LoadState = 'loading' | 'ready' | 'empty' | 'error';
type ScanField = 'patient' | 'medication';

interface BarcodeDetectorLike {
  detect(source: ImageBitmapSource): Promise<{ rawValue: string }[]>;
}

interface BarcodeDetectorCtor {
  new (init?: { formats?: string[] }): BarcodeDetectorLike;
  getSupportedFormats?(): Promise<string[]>;
}

const FIVE_RIGHTS_ORDER: readonly FiveRightsCheck[] = [
  'PATIENT',
  'DRUG',
  'DOSE',
  'ROUTE',
  'TIME',
];

const FIVE_RIGHTS_LABELS: Record<FiveRightsCheck, string> = {
  PATIENT: 'Right Patient',
  DRUG: 'Right Drug',
  DOSE: 'Right Dose',
  ROUTE: 'Right Route',
  TIME: 'Right Time',
};

/**
 * Inpatient eMAR five-rights barcode-scan loop (P1 #8).
 *
 * <p>Loads pending MAR tasks for the signed-in nurse, walks each one through
 * the bedside scan workflow (patient wristband → medication label → dose /
 * route confirm), then submits the verify call. The server is the source of
 * truth for whether the five rights pass; this component only mirrors the
 * outcomes for display. If any right fails the nurse must record an override
 * reason before recording GIVEN.
 */
@Component({
  selector: 'app-emar',
  standalone: true,
  imports: [CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './emar.component.html',
  styleUrl: './emar.component.scss',
})
export class EmarComponent implements OnInit, OnDestroy {
  protected readonly state = signal<LoadState>('loading');
  protected readonly tasks = signal<NurseMedicationTask[]>([]);
  protected readonly activeTask = signal<NurseMedicationTask | null>(null);

  protected readonly patientScan = signal('');
  protected readonly medicationScan = signal('');
  protected readonly doseScan = signal('');
  protected readonly routeScan = signal('');
  protected readonly overrideReason = signal('');

  protected readonly verification = signal<MarVerificationResponse | null>(null);
  protected readonly verifyInFlight = signal(false);
  protected readonly administerInFlight = signal(false);
  protected readonly scannerActive = signal<ScanField | null>(null);
  protected readonly scannerError = signal<string | null>(null);

  protected readonly fiveRights = FIVE_RIGHTS_ORDER;
  protected readonly fiveRightsLabel = (k: FiveRightsCheck) => FIVE_RIGHTS_LABELS[k];

  protected readonly canVerify = computed(() => {
    const t = this.activeTask();
    return (
      !!t &&
      this.patientScan().trim().length > 0 &&
      this.medicationScan().trim().length > 0 &&
      this.doseScan().trim().length > 0 &&
      this.routeScan().trim().length > 0
    );
  });

  protected readonly needsOverride = computed(() => {
    const v = this.verification();
    return !!v && !v.allPassed;
  });

  protected readonly canAdminister = computed(() => {
    const v = this.verification();
    if (!v) return false;
    if (v.allPassed) return true;
    return this.overrideReason().trim().length > 0;
  });

  @ViewChild('scannerVideo') protected scannerVideo?: ElementRef<HTMLVideoElement>;

  private readonly nurseTaskService = inject(NurseTaskService);
  private readonly toast = inject(ToastService);
  private readonly destroyed$ = new Subject<void>();
  private loadSub?: Subscription;
  private scanLoop?: ReturnType<typeof setTimeout>;
  private mediaStream?: MediaStream;

  ngOnInit(): void {
    this.loadTasks();
  }

  ngOnDestroy(): void {
    this.stopScanner();
    this.loadSub?.unsubscribe();
    this.destroyed$.next();
    this.destroyed$.complete();
  }

  protected loadTasks(): void {
    this.state.set('loading');
    this.loadSub?.unsubscribe();
    this.loadSub = this.nurseTaskService
      .getMedicationMAR({ status: 'PENDING' })
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: (tasks) => {
          this.tasks.set(tasks);
          this.state.set(tasks.length === 0 ? 'empty' : 'ready');
        },
        error: () => this.state.set('error'),
      });
  }

  protected selectTask(task: NurseMedicationTask): void {
    this.activeTask.set(task);
    this.patientScan.set('');
    this.medicationScan.set('');
    this.doseScan.set(task.dose ?? '');
    this.routeScan.set(task.route ?? '');
    this.overrideReason.set('');
    this.verification.set(null);
    this.scannerError.set(null);
    this.stopScanner();
  }

  protected closeTask(): void {
    this.activeTask.set(null);
    this.verification.set(null);
    this.stopScanner();
  }

  protected onScanInput(field: ScanField, value: string): void {
    if (field === 'patient') this.patientScan.set(value);
    else this.medicationScan.set(value);
  }

  protected onDoseInput(value: string): void {
    this.doseScan.set(value);
  }

  protected onRouteInput(value: string): void {
    this.routeScan.set(value);
  }

  protected onOverrideInput(value: string): void {
    this.overrideReason.set(value);
  }

  protected verify(): void {
    const task = this.activeTask();
    if (!task || !this.canVerify()) return;
    const request: MarVerificationRequest = {
      patientScanValue: this.patientScan().trim(),
      medicationScanValue: this.medicationScan().trim(),
      doseScanValue: this.doseScan().trim(),
      routeScanValue: this.routeScan().trim(),
    };
    this.verifyInFlight.set(true);
    this.nurseTaskService
      .verifyMedication(task.id, request)
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: (resp) => {
          this.verification.set(resp);
          this.verifyInFlight.set(false);
          if (resp.allPassed) {
            this.toast.success('All five rights verified.');
          } else {
            this.toast.error(`Failed: ${resp.failedChecks.join(', ')}`);
          }
        },
        error: () => {
          this.verifyInFlight.set(false);
          this.toast.error('Verification failed. Try again.');
        },
      });
  }

  protected administer(status: 'GIVEN' | 'HELD' | 'REFUSED'): void {
    const task = this.activeTask();
    if (!task) return;
    if (status === 'GIVEN' && !this.canAdminister()) return;

    this.administerInFlight.set(true);
    const payload: { status: string; note?: string; overrideReason?: string } = { status };
    if (this.needsOverride() && status === 'GIVEN') {
      payload.overrideReason = this.overrideReason().trim();
    }

    this.nurseTaskService
      .administerMedication(task.id, payload)
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: () => {
          this.administerInFlight.set(false);
          this.toast.success(`Recorded as ${status}.`);
          this.closeTask();
          this.loadTasks();
        },
        error: () => {
          this.administerInFlight.set(false);
          this.toast.error('Could not record administration.');
        },
      });
  }

  /* ── Camera scanner (BarcodeDetector) ────────────────────────────────── */

  protected async startScanner(field: ScanField): Promise<void> {
    this.scannerError.set(null);
    const Detector = (globalThis as unknown as { BarcodeDetector?: BarcodeDetectorCtor })
      .BarcodeDetector;
    if (!Detector) {
      this.scannerError.set(
        'Live scanner not supported on this device — type or paste the value below.',
      );
      return;
    }
    try {
      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment' },
      });
      this.scannerActive.set(field);
      // Defer until template renders the video element.
      setTimeout(() => this.runDetectLoop(new Detector({ formats: ['qr_code', 'code_128'] })), 0);
    } catch {
      this.scannerError.set('Camera permission denied — type or paste the value below.');
      this.stopScanner();
    }
  }

  protected stopScanner(): void {
    if (this.scanLoop != null) {
      clearTimeout(this.scanLoop);
      this.scanLoop = undefined;
    }
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach((t) => t.stop());
      this.mediaStream = undefined;
    }
    this.scannerActive.set(null);
  }

  private runDetectLoop(detector: BarcodeDetectorLike): void {
    const video = this.scannerVideo?.nativeElement;
    if (!video || !this.mediaStream) return;
    video.srcObject = this.mediaStream;
    video.play().catch(() => undefined);

    const tick = async () => {
      const field = this.scannerActive();
      if (!field || !video || !this.mediaStream) return;
      try {
        const codes = await detector.detect(video);
        if (codes.length > 0) {
          const value = codes[0].rawValue;
          this.onScanInput(field, value);
          this.toast.success(`Scanned ${field}: ${value.slice(0, 24)}`);
          this.stopScanner();
          return;
        }
      } catch {
        // ignore frame-level decode failures
      }
      this.scanLoop = setTimeout(tick, 250);
    };
    void tick();
  }
}
