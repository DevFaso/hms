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
} from '../../services/integration-health.model';

interface StatusChip {
  status: IntegrationHealthStatus;
  labelKey: string;
  color: string;
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
}
