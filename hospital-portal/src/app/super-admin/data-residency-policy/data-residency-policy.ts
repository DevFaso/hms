import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import { OrganizationRegion } from '../../services/data-residency.model';
import { RegionPolicyService } from '../../services/region-policy.service';
import { RegionPolicyRow } from '../../services/region-policy.model';

interface EditState {
  region: OrganizationRegion;
  retentionDays: number | null;
  defaultExportFormat: string;
  targetDeploymentUrl: string;
  busy: boolean;
  errorKey: string | null;
}

@Component({
  selector: 'app-data-residency-policy',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule],
  templateUrl: './data-residency-policy.html',
  styleUrl: './data-residency-policy.scss',
})
export class DataResidencyPolicyComponent implements OnInit {
  private readonly service = inject(RegionPolicyService);

  readonly loading = signal(true);
  readonly errored = signal(false);
  readonly rows = signal<RegionPolicyRow[]>([]);
  readonly editing = signal<EditState | null>(null);

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.service
      .list()
      .pipe(
        catchError(() => {
          this.errored.set(true);
          return of([] as RegionPolicyRow[]);
        }),
      )
      .subscribe((rows) => {
        this.rows.set(rows);
        this.loading.set(false);
      });
  }

  startEdit(row: RegionPolicyRow): void {
    this.editing.set({
      region: row.region,
      retentionDays: row.retentionDays,
      defaultExportFormat: row.defaultExportFormat ?? '',
      targetDeploymentUrl: row.targetDeploymentUrl ?? '',
      busy: false,
      errorKey: null,
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
    this.editing.set({ ...state, busy: true, errorKey: null });

    const body = {
      retentionDays: state.retentionDays ?? null,
      defaultExportFormat: state.defaultExportFormat.trim() || null,
      targetDeploymentUrl: state.targetDeploymentUrl.trim() || null,
    };

    this.service
      .update(state.region, body)
      .pipe(
        catchError(() => {
          this.editing.update((current) =>
            current
              ? { ...current, busy: false, errorKey: 'REGION_POLICY.ERROR.UPDATE_FAILED' }
              : current,
          );
          return of(null);
        }),
      )
      .subscribe((updated) => {
        if (!updated) return;
        this.rows.update((rows) => rows.map((r) => (r.region === updated.region ? updated : r)));
        this.editing.set(null);
      });
  }
}
