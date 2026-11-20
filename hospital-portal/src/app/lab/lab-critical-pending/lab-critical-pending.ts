import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

import { LabResultResponse, LabService } from '../../services/lab.service';
import { RoleContextService } from '../../core/role-context.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { LabValuePipe } from '../shared/lab-value.pipe';

/**
 * r65 D2183: the hospital's critical lab results still waiting for a
 * clinician's read-back, for the hospital admin the escalation pages from
 * round 2.
 *
 * <p>Read-only on purpose. A critical value is closed by a clinician's
 * read-back (`POST /lab-results/{id}/critical-read-back`, doctor, nurse or
 * midwife; E9 #67 took it from admins). The admin is paged to get a clinician
 * on it, so the screen says who ordered it, since when it has been paging,
 * and what to do; it offers no acknowledge control that would answer 403.
 */
@Component({
  selector: 'app-lab-critical-pending',
  standalone: true,
  imports: [CommonModule, TranslateModule, ServerTimePipe, LabValuePipe],
  templateUrl: './lab-critical-pending.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './lab-critical-pending.scss',
})
export class LabCriticalPendingComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly roleContext = inject(RoleContextService);

  loading = signal(true);
  failed = signal(false);
  noScope = signal(false);
  rows = signal<LabResultResponse[]>([]);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const hospitalId = this.roleContext.effectiveHospitalIdForRequest();
    if (!hospitalId) {
      this.noScope.set(true);
      this.loading.set(false);
      return;
    }
    this.noScope.set(false);
    this.loading.set(true);
    this.failed.set(false);
    this.labService.criticalUnacknowledged(hospitalId).subscribe({
      next: (rows) => {
        // Longest-paging first: the one that has waited longest is the one to chase.
        const sorted = [...(rows ?? [])].sort((a, b) =>
          (a.criticalNotifiedAt ?? '').localeCompare(b.criticalNotifiedAt ?? ''),
        );
        this.rows.set(sorted);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        // Never drawn as "nothing waiting": an outage and an empty list are opposite facts.
        console.error('Failed to load the critical lab results', err);
        this.rows.set([]);
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }
}
