import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  LabQcEvent,
  LabService,
  LabTestDefinition,
  LabTestDefinitionApprovalRequest,
  LabTestValidationStudy,
  LabTestValidationStudyRequest,
  ValidationStudyType,
} from '../../services/lab.service';
import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../core/toast.service';
import { TranslateModule } from '@ngx-translate/core';

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

@Component({
  selector: 'app-lab-approval-queue',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './lab-approval-queue.html',
  styleUrl: './lab-approval-queue.scss',
})
export class LabApprovalQueueComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  definitions = signal<LabTestDefinition[]>([]);
  filtered = signal<LabTestDefinition[]>([]);
  searchTerm = '';

  activeStatusFilter = signal<LabTestDefinition['approvalStatus'] | 'ALL'>('ALL');

  readonly isLabDirector = this.auth.hasAnyRole(['ROLE_LAB_DIRECTOR']);
  readonly isQualityManager = this.auth.hasAnyRole(['ROLE_QUALITY_MANAGER']);
  readonly isSuperAdmin = this.auth.hasAnyRole(['ROLE_SUPER_ADMIN']);

  // Approval modal
  showApprovalModal = signal(false);
  approvalTarget = signal<LabTestDefinition | null>(null);
  approvalAction = signal<LabTestDefinitionApprovalRequest['action'] | ''>('');
  approvalRejectionReason = signal('');
  processingApproval = signal(false);

  // ── Validation study detail panel ──────────────────────────────────
  selectedDefinition = signal<LabTestDefinition | null>(null);
  validationStudies = signal<LabTestValidationStudy[]>([]);
  loadingStudies = signal(false);

  // ── QC control chart ───────────────────────────────────────────────
  qcEvents = signal<LabQcEvent[]>([]);
  loadingQc = signal(false);
  readonly qcChartGroups = computed(() => this.buildChartGroups(this.qcEvents()));

  // Add-study form
  showStudyModal = signal(false);
  savingStudy = signal(false);
  studyForm: LabTestValidationStudyRequest = this.emptyStudyForm();

  readonly studyTypes: ValidationStudyType[] = [
    'PRECISION',
    'ACCURACY',
    'REFERENCE_RANGE',
    'METHOD_COMPARISON',
    'INTERFERENCE',
    'CARRYOVER',
    'LINEARITY',
  ];

  ngOnInit(): void {
    this.loadQueue();
  }

  loadQueue(): void {
    this.loading.set(true);
    // Fetch all pending definitions (both PENDING_QA_REVIEW and PENDING_DIRECTOR_APPROVAL)
    this.labService.searchTestDefinitions({ size: 200 }).subscribe({
      next: ({ content }) => {
        const pending = content.filter(
          (d) =>
            d.approvalStatus === 'PENDING_QA_REVIEW' ||
            d.approvalStatus === 'PENDING_DIRECTOR_APPROVAL' ||
            d.approvalStatus === 'DRAFT',
        );
        this.definitions.set(pending);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load approval queue');
        this.loading.set(false);
      },
    });
  }

  setStatusFilter(status: LabTestDefinition['approvalStatus'] | 'ALL'): void {
    this.activeStatusFilter.set(status);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.definitions();
    const status = this.activeStatusFilter();
    if (status !== 'ALL') {
      list = list.filter((d) => d.approvalStatus === status);
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (d) =>
          (d.testName ?? '').toLowerCase().includes(term) ||
          (d.testCode ?? '').toLowerCase().includes(term) ||
          (d.category ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  countByStatus(status: LabTestDefinition['approvalStatus']): number {
    return this.definitions().filter((d) => d.approvalStatus === status).length;
  }

  // ── Approval modal ──────────────────────────────────────────────────

  openApprovalModal(
    def: LabTestDefinition,
    action: LabTestDefinitionApprovalRequest['action'],
  ): void {
    this.approvalTarget.set(def);
    this.approvalAction.set(action);
    this.approvalRejectionReason.set('');
    this.showApprovalModal.set(true);
  }

  closeApprovalModal(): void {
    this.showApprovalModal.set(false);
    this.approvalTarget.set(null);
    this.approvalAction.set('');
    this.approvalRejectionReason.set('');
  }

  submitApprovalAction(): void {
    const def = this.approvalTarget();
    const action = this.approvalAction() as LabTestDefinitionApprovalRequest['action'];
    if (!def || !action) return;

    const req: LabTestDefinitionApprovalRequest = { action };
    if (action === 'REJECT') {
      req.rejectionReason = this.approvalRejectionReason();
    }

    this.processingApproval.set(true);
    this.labService.submitApprovalAction(def.id, req).subscribe({
      next: () => {
        this.toast.success('Approval action recorded');
        this.closeApprovalModal();
        this.processingApproval.set(false);
        this.loadQueue();
      },
      error: () => {
        this.toast.error('Action failed — check role permissions or current status');
        this.processingApproval.set(false);
      },
    });
  }

  // ── Definition detail panel ────────────────────────────────────────

  viewDefinition(def: LabTestDefinition): void {
    this.selectedDefinition.set(def);
    this.validationStudies.set([]);
    this.qcEvents.set([]);

    this.loadingStudies.set(true);
    this.labService.getValidationStudies(def.id).subscribe({
      next: (studies) => {
        this.validationStudies.set(studies);
        this.loadingStudies.set(false);
      },
      error: () => {
        this.toast.error('Failed to load validation studies');
        this.loadingStudies.set(false);
      },
    });

    this.loadingQc.set(true);
    this.labService.getQcEventsByDefinition(def.id).subscribe({
      next: (events) => {
        this.qcEvents.set(events);
        this.loadingQc.set(false);
      },
      error: () => {
        this.loadingQc.set(false);
      },
    });
  }

  closeDetail(): void {
    this.selectedDefinition.set(null);
    this.validationStudies.set([]);
    this.qcEvents.set([]);
  }

  // ── Add-study modal ────────────────────────────────────────────────

  emptyStudyForm(): LabTestValidationStudyRequest {
    return {
      studyType: 'PRECISION',
      studyDate: new Date().toISOString().split('T')[0],
      passed: false,
    };
  }

  openStudyModal(): void {
    this.studyForm = this.emptyStudyForm();
    this.showStudyModal.set(true);
  }

  closeStudyModal(): void {
    this.showStudyModal.set(false);
  }

  saveStudy(): void {
    const def = this.selectedDefinition();
    if (!def) return;
    this.savingStudy.set(true);
    this.labService.createValidationStudy(def.id, this.studyForm).subscribe({
      next: (study) => {
        this.validationStudies.update((list) => [study, ...list]);
        this.toast.success('Validation study recorded');
        this.closeStudyModal();
        this.savingStudy.set(false);
      },
      error: () => {
        this.toast.error('Failed to save validation study');
        this.savingStudy.set(false);
      },
    });
  }

  deleteStudy(study: LabTestValidationStudy): void {
    this.labService.deleteValidationStudy(study.id).subscribe({
      next: () => {
        this.validationStudies.update((list) => list.filter((s) => s.id !== study.id));
        this.toast.success('Study removed');
      },
      error: () => this.toast.error('Failed to remove validation study'),
    });
  }

  studyPassedClass(passed: boolean): string {
    return passed ? 'status-badge status-completed' : 'status-badge status-cancelled';
  }

  getApprovalStatusClass(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'status-badge status-completed';
      case 'APPROVED':
        return 'status-badge status-collected';
      case 'PENDING_QA_REVIEW':
      case 'PENDING_DIRECTOR_APPROVAL':
        return 'status-badge status-pending';
      case 'DRAFT':
        return 'status-badge status-in_progress';
      case 'REJECTED':
        return 'status-badge status-cancelled';
      case 'RETIRED':
        return 'status-badge';
      default:
        return 'status-badge';
    }
  }

  // ── Levey-Jennings chart computation ──────────────────────────────

  private readonly ML = 58;
  private readonly MR = 12;
  private readonly MT = 15;
  private readonly MB = 42;
  private readonly CW = 520;
  private readonly CH = 200;
  private readonly PW = 450; // CW - ML - MR
  private readonly PH = 143; // CH - MT - MB
  private readonly YSPAN = 3.5;

  private buildChartGroups(events: LabQcEvent[]): QcChartGroup[] {
    const levels = ['LOW_CONTROL', 'HIGH_CONTROL'] as const;
    const groups: QcChartGroup[] = [];

    for (const level of levels) {
      const levelEvents = events
        .filter((e) => e.qcLevel === level)
        .sort((a, b) => new Date(a.recordedAt).getTime() - new Date(b.recordedAt).getTime());

      if (levelEvents.length === 0) continue;

      if (levelEvents.length < 3) {
        groups.push({
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
        });
        continue;
      }

      const vals = levelEvents.map((e) => e.measuredValue);
      const mean = vals.reduce((a, b) => a + b, 0) / vals.length;
      const variance = vals.reduce((a, v) => a + (v - mean) ** 2, 0) / vals.length;
      const sd = Math.sqrt(variance);

      if (sd === 0) {
        groups.push({
          level,
          mean,
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
        });
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
        const zone: 'ok' | 'warn' | 'fail' = dev > 2 ? 'fail' : dev > 1 ? 'warn' : 'ok';
        const dateStr = new Date(e.recordedAt).toLocaleDateString('en-US', {
          month: 'short',
          day: 'numeric',
          year: 'numeric',
        });
        return { x: xToSvg(i), y: yToSvg(v), zone, title: `${dateStr} | ${v.toFixed(3)}` };
      });

      const polyline = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');

      const prec = sd < 1 ? 3 : sd < 10 ? 2 : 1;
      const f = (v: number) => v.toFixed(prec);
      const yAxisLabels = [
        { y: p3sY, label: `+3s (${f(mean + 3 * sd)})` },
        { y: p2sY, label: `+2s` },
        { y: p1sY, label: `+1s` },
        { y: meanY, label: `\u0058\u0305 ${f(mean)}` },
        { y: m1sY, label: `-1s` },
        { y: m2sY, label: `-2s` },
        { y: m3sY, label: `-3s (${f(mean - 3 * sd)})` },
      ];

      const xAxisLabels: { x: number; label: string }[] = [];
      const fmtDate = (e: LabQcEvent) =>
        new Date(e.recordedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
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
}
