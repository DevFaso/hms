import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import { IntegrationHealthService } from '../../services/integration-health.service';
import {
  IntegrationHealthRow,
  IntegrationHealthStatus,
  IntegrationHealthSummary,
  IntegrationHistoryBucket,
  IntegrationProbeResult,
} from '../../services/integration-health.model';

interface StatusChip {
  status: IntegrationHealthStatus;
  labelKey: string;
  color: string;
}

interface RowAction {
  busy: boolean;
  result: IntegrationProbeResult | null;
  errorKey: string | null;
}

interface HistoryState {
  loading: boolean;
  error: boolean;
  buckets: IntegrationHistoryBucket[];
  /** SVG path data for the sparkline polyline. */
  sparklinePath: string;
}

@Component({
  selector: 'app-integration-health',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslateModule, DatePipe],
  templateUrl: './integration-health.html',
  styleUrl: './integration-health.scss',
})
export class IntegrationHealthComponent implements OnInit {
  private readonly service = inject(IntegrationHealthService);

  readonly loading = signal(true);
  readonly errored = signal(false);
  readonly summary = signal<IntegrationHealthSummary | null>(null);
  readonly expandedIntegrationId = signal<string | null>(null);

  /** MVP-3b — per-row probe / resync state keyed by integrationId. */
  readonly rowActions = signal<Record<string, RowAction>>({});

  /** MVP-3b — history-drawer state keyed by integrationId. */
  readonly historyState = signal<Record<string, HistoryState>>({});

  readonly integrations = computed<IntegrationHealthRow[]>(
    () => this.summary()?.integrations ?? [],
  );

  readonly chips = computed<StatusChip[]>(() => {
    const s = this.summary();
    if (!s) return [];
    return [
      { status: 'HEALTHY', labelKey: 'INTEGRATION_HEALTH.CHIP.HEALTHY', color: '#10b981' },
      { status: 'DEGRADED', labelKey: 'INTEGRATION_HEALTH.CHIP.DEGRADED', color: '#f59e0b' },
      { status: 'FAILING', labelKey: 'INTEGRATION_HEALTH.CHIP.FAILING', color: '#ef4444' },
      { status: 'NO_HISTORY', labelKey: 'INTEGRATION_HEALTH.CHIP.NO_HISTORY', color: '#94a3b8' },
    ];
  });

  countFor(status: IntegrationHealthStatus): number {
    const s = this.summary();
    if (!s) return 0;
    switch (status) {
      case 'HEALTHY':
        return s.healthyCount;
      case 'DEGRADED':
        return s.degradedCount;
      case 'FAILING':
        return s.failingCount;
      case 'NO_HISTORY':
        return s.noHistoryCount;
    }
  }

  statusColor(status: IntegrationHealthStatus): string {
    switch (status) {
      case 'HEALTHY':
        return '#10b981';
      case 'DEGRADED':
        return '#f59e0b';
      case 'FAILING':
        return '#ef4444';
      case 'NO_HISTORY':
      default:
        return '#94a3b8';
    }
  }

  toggle(integrationId: string): void {
    this.expandedIntegrationId.update((current) =>
      current === integrationId ? null : integrationId,
    );
    // Lazy-load history the first time the row is expanded.
    if (this.expandedIntegrationId() === integrationId) {
      const history = this.historyState()[integrationId];
      if (!history) {
        this.loadHistory(integrationId);
      }
    }
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.service
      .getInventory()
      .pipe(catchError(() => of(null)))
      .subscribe((result) => {
        if (result === null) {
          this.errored.set(true);
        } else {
          this.summary.set(result);
        }
        this.loading.set(false);
      });
  }

  // ── MVP-3b row actions ──────────────────────────────────────────────

  rowAction(integrationId: string): RowAction {
    return (
      this.rowActions()[integrationId] ?? {
        busy: false,
        result: null,
        errorKey: null,
      }
    );
  }

  probe(event: Event, integrationId: string): void {
    event.stopPropagation();
    this.beginAction(integrationId);
    this.service
      .probe(integrationId)
      .pipe(catchError(() => of(null)))
      .subscribe((result) => this.finishAction(integrationId, 'probe', result));
  }

  resync(event: Event, integrationId: string): void {
    event.stopPropagation();
    this.beginAction(integrationId);
    this.service
      .resync(integrationId)
      .pipe(catchError(() => of(null)))
      .subscribe((result) => this.finishAction(integrationId, 'resync', result));
  }

  private beginAction(integrationId: string): void {
    this.rowActions.update((current) => ({
      ...current,
      [integrationId]: { busy: true, result: null, errorKey: null },
    }));
  }

  private finishAction(
    integrationId: string,
    kind: 'probe' | 'resync',
    result: IntegrationProbeResult | null,
  ): void {
    // Copilot review fix — surface a kind-specific error key so a
    // resync failure doesn't claim "Probe failed".
    const errorKey =
      result === null
        ? kind === 'probe'
          ? 'INTEGRATION_HEALTH.PROBE.ERROR'
          : 'INTEGRATION_HEALTH.RESYNC.ERROR'
        : null;
    this.rowActions.update((current) => ({
      ...current,
      [integrationId]: { busy: false, result, errorKey },
    }));
    // After a probe / resync the inventory may have moved; re-fetch
    // the recorder snapshot in the background so the chips and per-row
    // status pill stay in sync.
    if (result !== null) {
      this.silentRefresh();
      // Refresh history if the drawer is open for this row.
      if (this.expandedIntegrationId() === integrationId) {
        this.loadHistory(integrationId);
      }
    }
  }

  private silentRefresh(): void {
    this.service
      .getInventory()
      .pipe(catchError(() => of(null)))
      .subscribe((result) => {
        if (result !== null) {
          this.summary.set(result);
        }
      });
  }

  // ── MVP-3b history drawer ───────────────────────────────────────────

  history(integrationId: string): HistoryState {
    return (
      this.historyState()[integrationId] ?? {
        loading: false,
        error: false,
        buckets: [],
        sparklinePath: '',
      }
    );
  }

  loadHistory(integrationId: string): void {
    this.historyState.update((current) => ({
      ...current,
      [integrationId]: { loading: true, error: false, buckets: [], sparklinePath: '' },
    }));
    this.service
      .getHistory(integrationId, 24)
      .pipe(catchError(() => of(null)))
      .subscribe((buckets) => {
        if (buckets === null) {
          this.historyState.update((current) => ({
            ...current,
            [integrationId]: { loading: false, error: true, buckets: [], sparklinePath: '' },
          }));
          return;
        }
        this.historyState.update((current) => ({
          ...current,
          [integrationId]: {
            loading: false,
            error: false,
            buckets,
            sparklinePath: this.toSparklinePath(buckets),
          },
        }));
      });
  }

  /**
   * Build an SVG `<polyline points="…">` value from the bucket counts.
   * Coordinate space is 0..100 wide, 0..30 tall (matches the inline
   * SVG viewBox in the template). Each bucket plots its failure-ratio:
   *   ratio = failing / max(1, healthy + degraded + failing)
   * which maps 0 → bottom (good), 1 → top (bad). NO_HISTORY buckets
   * (zero on every counter) are plotted at the bottom so a quiet
   * window doesn't look like a spike.
   */
  private toSparklinePath(buckets: IntegrationHistoryBucket[]): string {
    if (buckets.length === 0) {
      return '';
    }
    const w = 100;
    const h = 30;
    const stepX = buckets.length === 1 ? 0 : w / (buckets.length - 1);
    return buckets
      .map((b, i) => {
        const total = b.healthyCount + b.degradedCount + b.failingCount;
        const ratio = total === 0 ? 0 : b.failingCount / total;
        const x = i * stepX;
        const y = h - ratio * h;
        return `${x.toFixed(2)},${y.toFixed(2)}`;
      })
      .join(' ');
  }
}
