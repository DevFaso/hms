import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, of } from 'rxjs';

import { OrganizationRegion } from '../../services/data-residency.model';
import { RegionPolicyService } from '../../services/region-policy.service';
import { RegionPolicyRow } from '../../services/region-policy.model';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

/**
 * QA D867: the export formats TenantExportPackager knows, shown by name.
 * Empty means "the global default".
 */
export const EXPORT_FORMATS: readonly string[] = ['STANDARD', 'GDPR_PORTABILITY'];

/** The actor a seeded or scheduled change is stamped with. */
const SYSTEM_ACTOR = 'system';

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
  imports: [FormsModule, RouterLink, TranslateModule, DatePipe, ServerTimePipe],
  templateUrl: './data-residency-policy.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './data-residency-policy.scss',
})
export class DataResidencyPolicyComponent implements OnInit {
  private readonly service = inject(RegionPolicyService);

  readonly loading = signal(true);
  readonly errored = signal(false);
  readonly rows = signal<RegionPolicyRow[]>([]);
  readonly editing = signal<EditState | null>(null);

  // MVP-c3 foot-guns — when the running deployment has only the stub
  // TenantProvisioningClient, writing target_deployment_url would be
  // rejected by the backend with a 400. The UI mirrors that signal:
  // the column becomes read-only with a tooltip explaining why. The
  // signal stays optimistic (true) until /capabilities resolves so a
  // slow capabilities call doesn't lock the deployment column on
  // first paint.
  readonly remoteProvisioningCapable = signal(true);

  ngOnInit(): void {
    this.refresh();
    this.loadCapabilities();
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

  private loadCapabilities(): void {
    this.service
      .capabilities()
      .pipe(
        catchError(() => {
          // Capability fetch failure → stay optimistic (column editable);
          // the backend write guard remains the source of truth.
          return of({ remoteProvisioningCapable: true });
        }),
      )
      .subscribe((caps) => this.remoteProvisioningCapable.set(caps.remoteProvisioningCapable));
  }

  /** The formats the editor offers: the known ones, plus the row's own when it is another. */
  exportFormatOptions(current: string): string[] {
    return current && !EXPORT_FORMATS.includes(current)
      ? [...EXPORT_FORMATS, current]
      : [...EXPORT_FORMATS];
  }

  /** A known format by its translated name; another value as written. */
  exportFormatKey(format: string | null): string | null {
    return format && EXPORT_FORMATS.includes(format)
      ? `REGION_POLICY.EXPORT_FORMAT.${format}`
      : null;
  }

  /** QA D867: the platform's own changes are signed "system"; say so in words. */
  isSystemActor(actor: string | null | undefined): boolean {
    return (actor ?? '').trim().toLowerCase() === SYSTEM_ACTOR;
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
