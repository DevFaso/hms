import {
  Component,
  ElementRef,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { forkJoin, of } from 'rxjs';
import {
  LabService,
  LabQcSummary,
  LabQcEvent,
  LabQcTarget,
  LabTestDefinition,
  LabValidationSummary,
  QcEventLevel,
} from '../../services/lab.service';
import { RoleContextService } from '../../core/role-context.service';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { ToastService } from '../../core/toast.service';
import { currentLocale, formatDecimal } from '../../shared/i18n/app-locale';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { serverInstant } from '../../shared/date-utils';
import { MeasurePipe } from '../../shared/pipes/measure.pipe';
import { LocaleNumberPipe } from '../../shared/pipes/lab-value.pipe';

interface QcPoint {
  x: number;
  y: number;
  zone: 'ok' | 'warn' | 'fail';
  title: string;
}

interface QcZoneRect {
  y: number;
  height: number;
  fill: string;
}

interface QcChartGroup {
  level: string;
  mean: number;
  sd: number;
  points: QcPoint[];
  polyline: string;
  meanY: number;
  p1sY: number;
  m1sY: number;
  p2sY: number;
  m2sY: number;
  p3sY: number;
  m3sY: number;
  zoneRects: QcZoneRect[];
  yAxisLabels: { y: number; label: string }[];
  xAxisLabels: { x: number; label: string }[];
  insufficient: boolean;
}

/** r63 D2304: the request fields a QC refusal can name, by the control that shows them. */
const RUN_FIELD_IDS: Readonly<Record<string, string>> = {
  testDefinitionId: 'qc-test',
  qcLevel: 'qc-level',
  measuredValue: 'qc-measured',
  expectedValue: 'qc-expected',
  lotNumber: 'qc-lot',
};
const TARGET_FIELD_IDS: Readonly<Record<string, string>> = {
  targetMean: 'qc-target-mean',
  targetSd: 'qc-target-sd',
};

@Component({
  selector: 'app-lab-qc-dashboard',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    MeasurePipe,
    ServerTimePipe,
    CommonModule,
    TranslateModule,
    LocaleNumberPipe,
  ],
  templateUrl: './lab-qc-dashboard.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './lab-qc-dashboard.scss',
})
export class LabQcDashboardComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** r63 D2304: who may read validation studies (their endpoint's roles); the bench does not. */
  private static readonly VALIDATION_ROLES = [
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
    'ROLE_QUALITY_MANAGER',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ];
  /** r63 D2304: POST /lab-qc-events. */
  private static readonly RECORD_ROLES = [
    'ROLE_LAB_TECHNICIAN',
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
    'ROLE_QUALITY_MANAGER',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ];
  readonly levels: QcEventLevel[] = ['LOW_CONTROL', 'HIGH_CONTROL'];

  // ── r63 D2304: recording a QC run ─────────────────────────
  showRecordForm = signal(false);
  tests = signal<LabTestDefinition[]>([]);
  qcTestId = signal('');
  qcLevel = signal<QcEventLevel>('LOW_CONTROL');
  qcAnalyzer = signal('');
  qcLot = signal('');
  qcMeasured = signal('');
  qcExpected = signal('');
  qcAccepted = signal<boolean | null>(null);
  qcNotes = signal('');
  savingRun = signal(false);
  recordError = signal<string | null>(null);

  // ── r63 D2304: the director's targets for the selected test ──
  targets = signal<LabQcTarget[]>([]);
  targetMean = signal('');
  targetSd = signal('');
  targetLot = signal('');
  savingTarget = signal(false);

  /** The target for the selected test and level, if the director set one. */
  readonly currentTarget = computed(
    () => this.targets().find((t) => t.qcLevel === this.qcLevel()) ?? null,
  );

  /** Where the value being typed sits against the target, in SDs; null without a target. */
  readonly liveZ = computed(() => {
    const target = this.currentTarget();
    const measured = this.parseNumber(this.qcMeasured());
    if (!target || measured == null || !(target.targetSd > 0)) {
      return null;
    }
    return (measured - target.targetMean) / target.targetSd;
  });

  /** WITHIN_2SD, OUTSIDE_2SD or OUTSIDE_3SD, the server's own reading (LabQcStatistics). */
  readonly liveFlag = computed(() => {
    const z = this.liveZ();
    if (z == null) return null;
    const distance = Math.abs(z);
    if (distance > 3) return 'OUTSIDE_3SD';
    if (distance > 2) return 'OUTSIDE_2SD';
    return 'WITHIN_2SD';
  });

  /** The latest runs of the expanded test, newest first. */
  readonly recentRuns = computed(() =>
    [...this.qcEvents()]
      .sort((a, b) => serverInstant(b.recordedAt).getTime() - serverInstant(a.recordedAt).getTime())
      .slice(0, 10),
  );

  loading = signal(true);
  qcSummary = signal<LabQcSummary[]>([]);
  validationSummary = signal<LabValidationSummary[]>([]);

  // ── Drill-down state ──────────────────────────────────────
  expandedDefinitionId = signal<string | null>(null);
  loadingChart = signal(false);
  qcEvents = signal<LabQcEvent[]>([]);
  readonly qcChartGroups = computed(() => this.buildChartGroups(this.qcEvents()));

  // ── Computed aggregates ───────────────────────────────────
  totalQcEvents = computed(() => this.qcSummary().reduce((s, r) => s + r.totalEvents, 0));
  totalQcFailures = computed(() => this.qcSummary().reduce((s, r) => s + r.failedEvents, 0));
  totalValidationStudies = computed(() =>
    this.validationSummary().reduce((s, r) => s + r.totalStudies, 0),
  );
  overallPassRate = computed(() => {
    const total = this.totalValidationStudies();
    const passed = this.validationSummary().reduce((s, r) => s + r.passedStudies, 0);
    return total > 0 ? (passed / total) * 100 : 0;
  });

  ngOnInit(): void {
    // r63 D2304: the technician reaches this page for QC; validation studies
    // are read only by the roles their endpoint admits.
    forkJoin({
      qc: this.labService.getQcSummary(),
      validation: this.canSeeValidation()
        ? this.labService.getValidationSummary()
        : of([] as LabValidationSummary[]),
    }).subscribe({
      next: ({ qc, validation }) => {
        this.qcSummary.set(qc);
        this.validationSummary.set(validation);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_QC.TOAST.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  canSeeValidation(): boolean {
    return this.roleContext.hasAnyActiveRole(LabQcDashboardComponent.VALIDATION_ROLES);
  }

  /** r63 D2304: recording a run needs a role the endpoint admits and a hospital to record it at. */
  canRecord(): boolean {
    return (
      this.roleContext.hasAnyActiveRole(LabQcDashboardComponent.RECORD_ROLES) &&
      !!this.roleContext.effectiveHospitalIdForRequest()
    );
  }

  /** r63 D2304: only the laboratory director sets (and thereby approves) a target. */
  canSetTargets(): boolean {
    return this.roleContext.hasAnyActiveRoleExactly(['ROLE_LAB_DIRECTOR']);
  }

  inputValue(event: Event): string {
    return (
      (event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement | null)?.value ??
      ''
    );
  }

  openRecordForm(testDefinitionId?: string): void {
    this.recordError.set(null);
    this.showRecordForm.set(true);
    if (this.tests().length === 0) {
      this.labService.listTestDefinitions().subscribe({
        next: (list) => {
          const quantitative = list.filter(
            (t) => t.isActive && !t.culture && !t.expectedResult?.trim(),
          );
          this.tests.set(quantitative);
          this.selectTest(
            quantitative.some((t) => t.id === testDefinitionId) ? testDefinitionId! : '',
          );
        },
        error: () => this.toast.error(this.translate.instant('LAB_QC.RECORD.TESTS_LOAD_FAILED')),
      });
    } else {
      this.selectTest(this.tests().some((t) => t.id === testDefinitionId) ? testDefinitionId! : '');
    }
  }

  closeRecordForm(): void {
    if (this.savingRun()) return;
    this.showRecordForm.set(false);
  }

  selectTest(testDefinitionId: string): void {
    this.qcTestId.set(testDefinitionId);
    this.targets.set([]);
    if (!testDefinitionId) return;
    this.labService.getQcTargets(testDefinitionId).subscribe({
      next: (targets) => {
        this.targets.set(targets);
        this.syncTargetInputs();
      },
      error: () => this.targets.set([]),
    });
  }

  selectLevel(level: string): void {
    this.qcLevel.set(level === 'HIGH_CONTROL' ? 'HIGH_CONTROL' : 'LOW_CONTROL');
    this.syncTargetInputs();
  }

  private syncTargetInputs(): void {
    const target = this.currentTarget();
    this.targetMean.set(target ? String(target.targetMean) : '');
    this.targetSd.set(target ? String(target.targetSd) : '');
    this.targetLot.set(target?.lotNumber ?? '');
  }

  private parseNumber(raw: string): number | null {
    const text = (raw ?? '').trim().replace(',', '.');
    if (!/^-?\d+(\.\d+)?$/.test(text)) return null;
    const value = Number(text);
    return Number.isFinite(value) ? value : null;
  }

  /** What the form still needs before a run can be recorded. */
  recordIncomplete(): boolean {
    const needsExpected = !this.currentTarget();
    return (
      !this.qcTestId() ||
      this.parseNumber(this.qcMeasured()) == null ||
      (needsExpected && this.parseNumber(this.qcExpected()) == null) ||
      this.qcAccepted() === null
    );
  }

  /**
   * QA D1124: the record button is never disabled for a blank field; the
   * submit guard names the blank required fields, or says the decision is missing.
   */
  recordGuard(): boolean | string {
    if (
      this.recordIncomplete() &&
      this.qcAccepted() === null &&
      this.qcTestId() &&
      this.parseNumber(this.qcMeasured()) != null
    ) {
      return this.translate.instant('LAB_QC.RECORD.DECISION_REQUIRED');
    }
    return this.recordIncomplete();
  }

  recordRun(): void {
    const hospitalId = this.roleContext.effectiveHospitalIdForRequest();
    const measured = this.parseNumber(this.qcMeasured());
    if (!hospitalId || measured == null || this.recordIncomplete() || this.savingRun()) return;
    // The expected value is the director's target mean when the field is left blank.
    const expected =
      this.parseNumber(this.qcExpected()) ?? this.currentTarget()?.targetMean ?? null;
    this.savingRun.set(true);
    this.recordError.set(null);
    this.labService
      .recordQcEvent({
        hospitalId,
        testDefinitionId: this.qcTestId(),
        qcLevel: this.qcLevel(),
        measuredValue: measured,
        ...(expected != null ? { expectedValue: expected } : {}),
        ...(this.qcAnalyzer().trim() ? { analyzerId: this.qcAnalyzer().trim() } : {}),
        ...(this.qcLot().trim() ? { lotNumber: this.qcLot().trim() } : {}),
        ...(this.qcNotes().trim() ? { notes: this.qcNotes().trim() } : {}),
        accepted: this.qcAccepted() === true,
      })
      .subscribe({
        next: () => {
          this.savingRun.set(false);
          this.toast.success(
            this.translate.instant(
              this.qcAccepted() ? 'LAB_QC.RECORD.SAVED_ACCEPTED' : 'LAB_QC.RECORD.SAVED_REJECTED',
            ),
          );
          // The next control is usually the other level of the same test.
          this.qcMeasured.set('');
          this.qcExpected.set('');
          this.qcAccepted.set(null);
          this.qcNotes.set('');
          this.reloadAfterRecord();
        },
        error: (err: unknown) => {
          this.savingRun.set(false);
          this.markRefusedFields(err, RUN_FIELD_IDS);
          this.recordError.set(
            refusalReason(err) ?? this.translate.instant('LAB_QC.RECORD.SAVE_FAILED'),
          );
        },
      });
  }

  saveTarget(): void {
    const mean = this.parseNumber(this.targetMean());
    const sd = this.parseNumber(this.targetSd());
    if (!this.qcTestId() || mean == null || sd == null || sd <= 0 || this.savingTarget()) return;
    this.savingTarget.set(true);
    this.labService
      .saveQcTarget({
        testDefinitionId: this.qcTestId(),
        qcLevel: this.qcLevel(),
        targetMean: mean,
        targetSd: sd,
        ...(this.targetLot().trim() ? { lotNumber: this.targetLot().trim() } : {}),
      })
      .subscribe({
        next: (saved) => {
          this.savingTarget.set(false);
          this.targets.set([...this.targets().filter((t) => t.qcLevel !== saved.qcLevel), saved]);
          this.toast.success(this.translate.instant('LAB_QC.TARGET.SAVED'));
        },
        error: (err: unknown) => {
          this.savingTarget.set(false);
          this.markRefusedFields(err, TARGET_FIELD_IDS);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('LAB_QC.TARGET.SAVE_FAILED'),
          );
        },
      });
  }

  /** A 400 that names fields marks each one under its control (QA D1245 class). */
  private markRefusedFields(err: unknown, idByField: Readonly<Record<string, string>>): void {
    const refused = refusedFieldReasons(err, idByField);
    if (refused.length > 0) {
      refuseFields(
        this.host.nativeElement,
        refused.map(([id, message]) => ({ id, message })),
        [...new Set(Object.values(idByField))],
      );
    }
  }

  targetIncomplete(): boolean {
    const sd = this.parseNumber(this.targetSd());
    return this.parseNumber(this.targetMean()) == null || sd == null || sd <= 0;
  }

  private reloadAfterRecord(): void {
    this.labService.getQcSummary().subscribe({
      next: (qc) => this.qcSummary.set(qc),
      error: () => this.toast.error(this.translate.instant('LAB_QC.TOAST.LOAD_FAILED')),
    });
    const expanded = this.expandedDefinitionId();
    if (expanded) {
      this.labService.getQcEventsByDefinition(expanded).subscribe({
        next: (events) => this.qcEvents.set(events),
        error: () => this.toast.error(this.translate.instant('LAB_QC.TOAST.CHART_LOAD_FAILED')),
      });
    }
  }

  // ── Drill-down: toggle chart for a test definition ────────
  toggleChart(definitionId: string): void {
    if (this.expandedDefinitionId() === definitionId) {
      this.expandedDefinitionId.set(null);
      this.qcEvents.set([]);
      return;
    }
    this.expandedDefinitionId.set(definitionId);
    this.loadingChart.set(true);
    this.labService.getQcEventsByDefinition(definitionId).subscribe({
      next: (events) => {
        this.qcEvents.set(events);
        this.loadingChart.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('LAB_QC.TOAST.CHART_LOAD_FAILED'));
        this.loadingChart.set(false);
      },
    });
  }

  // ── Levey-Jennings chart computation ──────────────────────
  private readonly ML = 58;
  private readonly MR = 12;
  private readonly MT = 15;
  private readonly MB = 42;
  private readonly CW = 520;
  private readonly CH = 200;
  private readonly PW = this.CW - this.ML - this.MR; // 450
  private readonly PH = this.CH - this.MT - this.MB; // 143
  private readonly YSPAN = 3.5;

  private buildChartGroups(events: LabQcEvent[]): QcChartGroup[] {
    const levels = ['LOW_CONTROL', 'HIGH_CONTROL'] as const;
    const groups: QcChartGroup[] = [];

    for (const level of levels) {
      const levelEvents = events
        .filter((e) => e.qcLevel === level)
        .sort(
          (a, b) => serverInstant(a.recordedAt).getTime() - serverInstant(b.recordedAt).getTime(),
        );

      if (levelEvents.length === 0) continue;

      const emptyGroup: QcChartGroup = {
        level,
        mean: 0,
        sd: 0,
        points: [],
        polyline: '',
        meanY: 0,
        p1sY: 0,
        m1sY: 0,
        p2sY: 0,
        m2sY: 0,
        p3sY: 0,
        m3sY: 0,
        zoneRects: [],
        yAxisLabels: [],
        xAxisLabels: [],
        insufficient: true,
      };

      // r63 D2304: the director's target (snapshotted on the latest run) is the
      // chart's centre and spread when set; otherwise the runs' own statistics.
      const latest = levelEvents[levelEvents.length - 1];
      const hasTarget = latest.targetMean != null && (latest.targetSd ?? 0) > 0;
      if (!hasTarget && levelEvents.length < 3) {
        groups.push(emptyGroup);
        continue;
      }

      const vals = levelEvents.map((e) => e.measuredValue);
      const ownMean = vals.reduce((a, b) => a + b, 0) / vals.length;
      const variance = vals.reduce((a, v) => a + (v - ownMean) ** 2, 0) / vals.length;
      const mean = hasTarget ? (latest.targetMean as number) : ownMean;
      const sd = hasTarget ? (latest.targetSd as number) : Math.sqrt(variance);

      if (sd === 0) {
        groups.push(emptyGroup);
        continue;
      }

      const n = levelEvents.length;
      const yRange = 2 * this.YSPAN * sd;
      const yBot = mean - this.YSPAN * sd;
      const yToSvg = (v: number) => this.MT + this.PH - ((v - yBot) / yRange) * this.PH;
      const xToSvg = (i: number) => this.ML + (n > 1 ? (i / (n - 1)) * this.PW : this.PW / 2);

      const meanY = yToSvg(mean);
      const p1sY = yToSvg(mean + sd);
      const m1sY = yToSvg(mean - sd);
      const p2sY = yToSvg(mean + 2 * sd);
      const m2sY = yToSvg(mean - 2 * sd);
      const p3sY = yToSvg(mean + 3 * sd);
      const m3sY = yToSvg(mean - 3 * sd);

      const bottomEdge = this.MT + this.PH;
      const zoneRects: QcZoneRect[] = [
        { y: this.MT, height: p3sY - this.MT, fill: '#fee2e2' },
        { y: p3sY, height: p2sY - p3sY, fill: '#fef3c7' },
        { y: p2sY, height: p1sY - p2sY, fill: '#fffbeb' },
        { y: p1sY, height: m1sY - p1sY, fill: '#f0fdf4' },
        { y: m1sY, height: m2sY - m1sY, fill: '#fffbeb' },
        { y: m2sY, height: m3sY - m2sY, fill: '#fef3c7' },
        { y: m3sY, height: bottomEdge - m3sY, fill: '#fee2e2' },
      ];

      const points: QcPoint[] = levelEvents.map((e, i) => {
        const v = e.measuredValue;
        const dev = Math.abs(v - mean) / sd;
        const zone = this.classifyZone(dev);
        const dateStr = serverInstant(e.recordedAt).toLocaleDateString(currentLocale(), {
          month: 'short',
          day: 'numeric',
          year: 'numeric',
        });
        return { x: xToSvg(i), y: yToSvg(v), zone, title: `${dateStr} | ${formatDecimal(v, 3)}` };
      });

      const polyline = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');

      const prec = this.sdPrecision(sd);
      const f = (v: number) => formatDecimal(v, prec);
      const yAxisLabels = [
        { y: p3sY, label: `+3s (${f(mean + 3 * sd)})` },
        { y: p2sY, label: `+2s` },
        { y: p1sY, label: `+1s` },
        { y: meanY, label: `X\u0305 ${f(mean)}` },
        { y: m1sY, label: `-1s` },
        { y: m2sY, label: `-2s` },
        { y: m3sY, label: `-3s (${f(mean - 3 * sd)})` },
      ];

      const xAxisLabels: { x: number; label: string }[] = [];
      const fmtDate = (e: LabQcEvent) =>
        serverInstant(e.recordedAt).toLocaleDateString(currentLocale(), {
          month: 'short',
          day: 'numeric',
        });
      xAxisLabels.push({ x: xToSvg(0), label: fmtDate(levelEvents[0]) });
      if (n > 2) {
        const mid = Math.floor(n / 2);
        xAxisLabels.push({ x: xToSvg(mid), label: fmtDate(levelEvents[mid]) });
      }
      if (n > 1) {
        xAxisLabels.push({ x: xToSvg(n - 1), label: fmtDate(levelEvents[n - 1]) });
      }

      groups.push({
        level,
        mean,
        sd,
        points,
        polyline,
        meanY,
        p1sY,
        m1sY,
        p2sY,
        m2sY,
        p3sY,
        m3sY,
        zoneRects,
        yAxisLabels,
        xAxisLabels,
        insufficient: false,
      });
    }

    return groups;
  }

  private classifyZone(dev: number): 'ok' | 'warn' | 'fail' {
    if (dev > 2) return 'fail';
    if (dev > 1) return 'warn';
    return 'ok';
  }

  private sdPrecision(sd: number): number {
    if (sd < 1) return 3;
    if (sd < 10) return 2;
    return 1;
  }
}
