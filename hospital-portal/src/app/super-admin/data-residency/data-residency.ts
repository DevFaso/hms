import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import { DataResidencyService } from '../../services/data-residency.service';
import { OrganizationRegion, OrganizationRegionRow } from '../../services/data-residency.model';

interface EditState {
  organizationId: string;
  region: OrganizationRegion;
  reason: string;
  busy: boolean;
  error: string | null;
}

@Component({
  selector: 'app-super-admin-data-residency',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule],
  templateUrl: './data-residency.html',
  styleUrl: './data-residency.scss',
})
export class DataResidencyComponent implements OnInit {
  private readonly service = inject(DataResidencyService);

  readonly loading = signal(true);
  readonly errored = signal(false);
  readonly rows = signal<OrganizationRegionRow[]>([]);
  readonly availableRegions = signal<OrganizationRegion[]>([]);
  readonly filterRegion = signal<OrganizationRegion | ''>('');
  readonly editing = signal<EditState | null>(null);

  readonly visibleRows = computed<OrganizationRegionRow[]>(() => {
    const filter = this.filterRegion();
    const all = this.rows();
    return filter ? all.filter((r) => r.region === filter) : all;
  });

  readonly distribution = computed<Map<OrganizationRegion, number>>(() => {
    const counts = new Map<OrganizationRegion, number>();
    for (const row of this.rows()) {
      counts.set(row.region, (counts.get(row.region) ?? 0) + 1);
    }
    return counts;
  });

  /** Sorted distribution entries (highest count first) for the chip strip. */
  readonly distributionEntries = computed<{ region: OrganizationRegion; count: number }[]>(() =>
    Array.from(this.distribution().entries())
      .map(([region, count]) => ({ region, count }))
      .sort((a, b) => b.count - a.count),
  );

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.errored.set(false);
    // Fire both calls in parallel via Promise.all-equivalent: the picker is
    // tiny (13 codes) so request order does not matter.
    this.service
      .listAvailableRegions()
      .pipe(catchError(() => of([] as OrganizationRegion[])))
      .subscribe((regions) => this.availableRegions.set(regions));

    this.service
      .getRegionSnapshot()
      .pipe(
        catchError(() => {
          this.errored.set(true);
          return of([] as OrganizationRegionRow[]);
        }),
      )
      .subscribe((rows) => {
        this.rows.set(rows);
        this.loading.set(false);
      });
  }

  startEdit(row: OrganizationRegionRow): void {
    this.editing.set({
      organizationId: row.organizationId,
      region: row.region,
      reason: '',
      busy: false,
      error: null,
    });
  }

  cancelEdit(): void {
    this.editing.set(null);
  }

  patchEdit<K extends keyof EditState>(key: K, value: EditState[K]): void {
    this.editing.update((current) => (current ? { ...current, [key]: value } : current));
  }

  submitEdit(): void {
    const state = this.editing();
    if (!state) return;
    this.editing.set({ ...state, busy: true, error: null });
    this.service
      .updateRegion(state.organizationId, {
        region: state.region,
        reason: state.reason?.trim() || undefined,
      })
      .pipe(
        catchError(() => {
          this.editing.update((current) =>
            current
              ? { ...current, busy: false, error: 'ORG_REGION.ERROR.UPDATE_FAILED' }
              : current,
          );
          return of(null);
        }),
      )
      .subscribe((updated) => {
        if (!updated) return;
        this.rows.update((rows) =>
          rows.map((r) => (r.organizationId === updated.organizationId ? updated : r)),
        );
        this.editing.set(null);
      });
  }

  setFilter(region: OrganizationRegion | ''): void {
    this.filterRegion.set(region);
  }
}
