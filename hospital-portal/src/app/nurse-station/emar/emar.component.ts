import { HttpErrorResponse } from '@angular/common/http';
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
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, Subscription, takeUntil } from 'rxjs';

import {
  FiveRightsCheck,
  MarVerificationRequest,
  MarVerificationResponse,
  NurseMedicationTask,
  NurseTaskService,
} from '../../services/nurse-task.service';
import { ToastService } from '../../core/toast.service';
import { CdsCardListComponent } from '../../shared/cds-card/cds-card.component';
import { CdsCard } from '../../shared/cds-card/cds-card.model';
import { MedicationRoutePipe } from '../../shared/pipes/medication-route.pipe';
import { isServerTimeToday } from '../../shared/date-utils';
import { WitnessPickerComponent } from '../witness-picker/witness-picker.component';

import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
type LoadState = 'loading' | 'ready' | 'empty' | 'error';
type ScanField = 'patient' | 'medication';

interface BarcodeDetectorLike {
  detect(source: ImageBitmapSource): Promise<{ rawValue: string }[]>;
}

interface BarcodeDetectorCtor {
  new (init?: { formats?: string[] }): BarcodeDetectorLike;
  getSupportedFormats?(): Promise<string[]>;
}

const FIVE_RIGHTS_ORDER: readonly FiveRightsCheck[] = ['PATIENT', 'DRUG', 'DOSE', 'ROUTE', 'TIME'];

const FIVE_RIGHTS_LABEL_KEYS: Record<FiveRightsCheck, string> = {
  PATIENT: 'EMAR.RIGHT.PATIENT',
  DRUG: 'EMAR.RIGHT.DRUG',
  DOSE: 'EMAR.RIGHT.DOSE',
  ROUTE: 'EMAR.RIGHT.ROUTE',
  TIME: 'EMAR.RIGHT.TIME',
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
  imports: [
    SubmitGuardDirective,
    CommonModule,
    FormsModule,
    TranslateModule,
    ServerTimePipe,
    CdsCardListComponent,
    MedicationRoutePipe,
    WitnessPickerComponent,
  ],
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
  /** QA D922: the second clinician who witnesses a controlled dose. */
  protected readonly witnessUserId = signal<string | null>(null);

  protected readonly verification = signal<MarVerificationResponse | null>(null);
  protected readonly verifyInFlight = signal(false);
  protected readonly administerInFlight = signal(false);
  protected readonly scannerActive = signal<ScanField | null>(null);
  protected readonly scannerError = signal<string | null>(null);

  protected readonly fiveRights = FIVE_RIGHTS_ORDER;
  protected readonly fiveRightsLabel = (k: FiveRightsCheck) =>
    this.translate.instant(FIVE_RIGHTS_LABEL_KEYS[k]);

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

  /**
   * QA D799: the cross-reactivity advisory the prescriber saw, before the
   * dose is given. The bedside verify's answer is the fresher one (the
   * allergy list may have changed since the queue loaded); until then the
   * row's. Never a block: Record Given stays governed by the five rights.
   */
  protected readonly allergyAdvisories = computed<CdsCard[]>(() => {
    const fromVerify = this.verification()?.cdsAdvisories;
    if (fromVerify) return fromVerify;
    return this.activeTask()?.cdsAdvisories ?? [];
  });

  protected readonly needsOverride = computed(() => {
    const v = this.verification();
    return !!v && !v.allPassed;
  });

  /**
   * Pharmacist verification outstanding (Tier 2 item 33). Distinct from a
   * failed five-rights check: the five rights are about this dose at this
   * bedside and a nurse may override them with a recorded reason, whereas
   * this one says nobody with a pharmacy qualification has looked at the
   * prescription yet. **There is no override for it here.** The server
   * refuses GIVEN regardless, so offering an override box would only
   * manufacture a reason for a refusal that is going to happen anyway.
   */
  protected readonly blockedPendingPharmacist = computed(() => {
    const t = this.activeTask();
    return !!t && !!t.requiresPharmacistVerification && !t.pharmacistVerified;
  });

  /** QA D922: a controlled drug is given only with a witness. */
  protected readonly needsWitness = computed(() => !!this.activeTask()?.controlledSubstance);

  protected readonly canAdminister = computed(() => {
    if (this.blockedPendingPharmacist()) return false;
    if (this.needsWitness() && !this.witnessUserId()) return false;
    const v = this.verification();
    if (!v) return false;
    if (v.allPassed) return true;
    return this.overrideReason().trim().length > 0;
  });

  /**
   * QA D1124: why "Record given" refuses, shown next to it; null when the
   * dose may be recorded. The button is never silently disabled.
   */
  protected administerBlockReason(): string | null {
    if (this.blockedPendingPharmacist()) {
      return this.translate.instant('EMAR.PHARMACIST_BLOCKED_TITLE');
    }
    if (this.needsWitness() && !this.witnessUserId()) {
      return this.translate.instant('SUBMIT_GUARD.WITNESS');
    }
    const v = this.verification();
    if (!v) return this.translate.instant('SUBMIT_GUARD.VERIFY_FIRST');
    if (!v.allPassed && this.overrideReason().trim().length === 0) {
      return this.translate.instant('SUBMIT_GUARD.OVERRIDE_REASON');
    }
    return null;
  }

  @ViewChild('scannerVideo') protected scannerVideo?: ElementRef<HTMLVideoElement>;

  private readonly nurseTaskService = inject(NurseTaskService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
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

  /** QA D949: an overdue dose from another day shows its date, not the time alone. */
  protected isDueToday(dueTime: string | null | undefined): boolean {
    return isServerTimeToday(dueTime);
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
    // QA D1025: the nurse confirms the dose and the route she is about to
    // give; a prefilled prescribed value made "Bonne dose" and "Bonne voie"
    // pass without anyone checking. The prescription is shown beside them.
    this.doseScan.set('');
    this.routeScan.set('');
    this.overrideReason.set('');
    this.holdRefuseReason.set('');
    this.witnessUserId.set(null);
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
    if (field === 'patient') {
      if (this.patientScan() === value) return;
      this.patientScan.set(value);
    } else {
      if (this.medicationScan() === value) return;
      this.medicationScan.set(value);
    }
    this.invalidateVerification();
  }

  protected onDoseInput(value: string): void {
    if (this.doseScan() === value) return;
    this.doseScan.set(value);
    this.invalidateVerification();
  }

  protected onRouteInput(value: string): void {
    if (this.routeScan() === value) return;
    this.routeScan.set(value);
    this.invalidateVerification();
  }

  protected onOverrideInput(value: string): void {
    this.overrideReason.set(value);
  }

  /**
   * Drop the previous verification result whenever any input the server
   * matched against changes. Without this guard a nurse could verify, edit
   * the dose to something the prescription doesn't allow, then proceed to
   * GIVEN using the now-stale all-pass outcome.
   */
  private invalidateVerification(): void {
    if (this.verification() !== null) {
      this.verification.set(null);
    }
    if (this.overrideReason() !== '') {
      this.overrideReason.set('');
    }
  }

  protected verify(): void {
    const task = this.activeTask();
    if (!task || !this.canVerify()) return;
    // Capture the task id at call time. If the nurse switches to a different
    // task while this request is in-flight, a late response must not paint
    // the previous patient's outcomes onto the now-active row — that would
    // be a clinical safety hazard.
    const taskIdAtCall = task.id;
    const request: MarVerificationRequest = {
      patientScanValue: this.patientScan().trim(),
      medicationScanValue: this.medicationScan().trim(),
      doseScanValue: this.doseScan().trim(),
      routeScanValue: this.routeScan().trim(),
    };
    this.verifyInFlight.set(true);
    this.nurseTaskService
      .verifyMedication(taskIdAtCall, request)
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: (resp) => {
          if (this.activeTask()?.id !== taskIdAtCall) return;
          this.verification.set(resp);
          this.verifyInFlight.set(false);
          if (resp.allPassed) {
            this.toast.success(this.translate.instant('EMAR.TOAST.ALL_RIGHTS_VERIFIED'));
            // QA D799: five rights passed is not "nothing to read" — the
            // cross-reactivity advisory is repeated next to the green toast.
            const advisory = resp.cdsAdvisories?.[0];
            if (advisory) this.toast.info(advisory.summary);
          } else {
            this.toast.error(
              this.translate.instant('EMAR.TOAST.RIGHTS_FAILED', {
                checks: resp.failedChecks.map((c) => this.fiveRightsLabel(c)).join(', '),
              }),
            );
          }
        },
        error: (err: HttpErrorResponse) => {
          if (this.activeTask()?.id !== taskIdAtCall) return;
          this.verifyInFlight.set(false);
          this.toast.error(
            this.serverReason(err) ?? this.translate.instant('EMAR.TOAST.VERIFY_FAILED'),
          );
        },
      });
  }

  /** QA D1159: why the dose is held or refused; required for those two outcomes. */
  protected readonly holdRefuseReason = signal('');

  protected holdRefuseReasonMissing(): boolean {
    return this.holdRefuseReason().trim() === '';
  }

  protected administer(status: 'GIVEN' | 'HELD' | 'REFUSED'): void {
    const task = this.activeTask();
    if (!task) return;
    if (status === 'GIVEN' && !this.canAdminister()) return;
    // QA D1159: never a HELD/REFUSED record without its reason (the buttons' guard says so first).
    const holdReason = this.holdRefuseReason().trim();
    if (status !== 'GIVEN' && !holdReason) return;

    this.administerInFlight.set(true);
    const payload: {
      status: string;
      note?: string;
      overrideReason?: string;
      witnessUserId?: string;
    } = { status };
    if (this.needsOverride() && status === 'GIVEN') {
      payload.overrideReason = this.overrideReason().trim();
    }
    if (status !== 'GIVEN') payload.note = holdReason;
    const witness = this.witnessUserId();
    if (status === 'GIVEN' && this.needsWitness() && witness) {
      payload.witnessUserId = witness;
    }

    this.nurseTaskService
      .administerMedication(task.id, payload)
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: () => {
          this.administerInFlight.set(false);
          this.toast.success(
            this.translate.instant('EMAR.TOAST.RECORDED', {
              status: this.translate.instant(`EMAR.ADMIN_STATUS.${status}`),
            }),
          );
          this.closeTask();
          this.loadTasks();
        },
        error: (err: HttpErrorResponse) => {
          this.administerInFlight.set(false);
          // QA D233: a five-rights refusal carries its reason; show it.
          this.toast.error(
            this.serverReason(err) ?? this.translate.instant('EMAR.TOAST.ADMINISTER_FAILED'),
          );
        },
      });
  }

  /** The server's own refusal message (a 4xx body), when it sent one. */
  private serverReason(err: HttpErrorResponse | null | undefined): string | null {
    if (!err || err.status < 400 || err.status >= 500) return null;
    const message = (err.error as { message?: unknown } | null)?.message;
    return typeof message === 'string' && message.trim() ? message.trim() : null;
  }

  /* ── Camera scanner (BarcodeDetector) ────────────────────────────────── */

  protected async startScanner(field: ScanField): Promise<void> {
    // Tear down any prior session first; otherwise tapping Scan for the
    // other field while the camera is already running would acquire a second
    // MediaStream and leak a still-running video track until ngOnDestroy.
    this.stopScanner();
    this.scannerError.set(null);
    const Detector = (globalThis as unknown as { BarcodeDetector?: BarcodeDetectorCtor })
      .BarcodeDetector;
    if (!Detector) {
      this.scannerError.set(this.translate.instant('EMAR.SCANNER_UNSUPPORTED'));
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
      this.scannerError.set(this.translate.instant('EMAR.SCANNER_PERMISSION_DENIED'));
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
          this.toast.success(
            this.translate.instant('EMAR.TOAST.SCANNED', {
              field: this.translate.instant(
                field === 'patient' ? 'EMAR.SCAN_FIELD.PATIENT' : 'EMAR.SCAN_FIELD.MEDICATION',
              ),
              value: value.slice(0, 24),
            }),
          );
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
