import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { LabResultPage, LabResultResponse, LabService } from '../../services/lab.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';

/**
 * Release worklist (B14).
 *
 * <p>Wave 1 made release a human act: with
 * `hms.lab.auto-verification.enabled=false` — the default — a result, whether
 * an analyzer sent it or somebody typed it, stays unreleased until a member of
 * the laboratory says it may be acted on. `GET /lab-results/pending-release`
 * was built for exactly that queue and no web surface called it, so on the
 * portal a result waiting for release was invisible: nothing listed it,
 * nothing counted it, and it waited for ever.
 *
 * <p><b>Why a screen and not a tab on the approval queue.</b>
 * `lab-approval-queue` reads like a neighbour but is not one: it is the
 * catalogue workflow — `LabTestDefinition` rows moving through QA review and
 * director approval, with validation studies and Levey-Jennings charts. It
 * shares no row type, no endpoint and no audience rule with a queue of
 * patients' results. A tab there would have been two screens in one file.
 *
 * <p>Who sees what: the endpoint admits five laboratory roles, and release
 * admits four (`LabResultAuthority.RELEASE_EXPRESSION`). A technician and a
 * quality manager therefore read this queue and get no release control — that
 * is deliberate, not an oversight, and a spec pins it.
 */
@Component({
  selector: 'app-lab-release-worklist',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './lab-release-worklist.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './lab-release-worklist.scss',
})
export class LabReleaseWorklistComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /**
   * POST /lab-results/{id}/release — LabResultAuthority.RELEASE_EXPRESSION.
   *
   * <p>Read live on every call, never captured into a field at construction:
   * a role snapshot taken in the constructor survives a hospital-scope change
   * and then answers for the hospital the user has left (the defect fixed on
   * this same button in #723).
   */
  private static readonly RELEASE_ROLES = [
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
    'ROLE_SUPER_ADMIN',
  ];

  readonly pageSize = 25;

  loading = signal(true);
  error = signal<string | null>(null);
  page = signal<LabResultPage | null>(null);
  pageIndex = signal(0);

  /** The row whose release is being confirmed; null when no dialog is open. */
  releaseTarget = signal<LabResultResponse | null>(null);
  /** Id of the row being released, so the control cannot double-fire. */
  releasingId = signal<string | null>(null);
  /** Why the last release attempt failed — shown in the dialog, which stays open. */
  releaseError = signal<string | null>(null);
  /** What the last successful release was, so the screen says what happened. */
  justReleased = signal<{ patient: string; test: string } | null>(null);

  rows = computed(() => this.page()?.content ?? []);
  totalAwaiting = computed(() => this.page()?.totalElements ?? 0);
  totalPages = computed(() => {
    const p = this.page();
    if (!p) {
      return 1;
    }
    return Math.max(1, p.totalPages || Math.ceil(p.totalElements / this.pageSize));
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.labService.listPendingRelease(this.pageIndex(), this.pageSize).subscribe({
      next: (result) => {
        this.page.set(result);
        this.loading.set(false);
        // Releasing the last row of the last page leaves the reader on a page
        // that no longer exists — an empty queue that is not empty. Step back
        // once and re-read; pageIndex only ever decreases here, so this ends.
        if (result.content.length === 0 && this.pageIndex() > 0 && result.totalElements > 0) {
          this.pageIndex.set(this.pageIndex() - 1);
          this.load();
        }
      },
      error: (err) => {
        // Never render a refusal or an outage as an empty queue: "nothing to
        // release" and "we could not ask" are opposite facts for a laboratory.
        console.error('Failed to load the lab release worklist', err);
        this.page.set(null);
        this.error.set(this.translate.instant('LAB_RELEASE.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  /**
   * Whether to offer the release control for this row.
   *
   * <p>Two gates, both the backend's: the role list on the endpoint, read live
   * from the active role context; and B1's rule that releasing is the running
   * laboratory's attestation of its own work. The queue is already the
   * performing hospital's (`findPendingReleaseHandledBy` coalesces the
   * performing hospital with the ordering one), so the second check should
   * never fire here — it is kept because a control that answers 400 to the
   * person it was shown to teaches them to distrust the screen, and the queue
   * predicate is not this component's to guarantee.
   */
  canReleaseResult(r: LabResultResponse): boolean {
    if (r.released) {
      return false;
    }
    if (!this.roleContext.hasAnyActiveRole(LabReleaseWorklistComponent.RELEASE_ROLES)) {
      return false;
    }
    return !this.releaseBelongsElsewhere(r);
  }

  /**
   * The row is another laboratory's to release (B1).
   *
   * <p>Separate from {@link canReleaseResult} so the screen can say WHICH
   * reason applies. One message for both would tell a technician — who simply
   * may not release anything — that the result is released elsewhere, which
   * is untrue and sends them chasing a hospital that is not involved.
   */
  releaseBelongsElsewhere(r: LabResultResponse): boolean {
    const performing = r.performingHospitalId;
    if (!performing || this.roleContext.isSuperAdmin()) {
      return false;
    }
    return performing !== this.roleContext.effectiveHospitalIdForRequest();
  }

  /** Releasing is a clinical attestation: it is confirmed, never one click. */
  askRelease(r: LabResultResponse): void {
    if (!this.canReleaseResult(r) || this.releasingId()) {
      return;
    }
    this.releaseError.set(null);
    this.releaseTarget.set(r);
  }

  cancelRelease(): void {
    if (this.releasingId()) {
      return;
    }
    this.releaseTarget.set(null);
    this.releaseError.set(null);
  }

  confirmRelease(): void {
    const target = this.releaseTarget();
    if (!target || this.releasingId()) {
      return;
    }
    // The in-flight guard is set BEFORE the call is dispatched; a guard read
    // after the dispatch guards nothing (the #443 lesson).
    this.releasingId.set(target.id);
    this.releaseError.set(null);
    this.labService.releaseResult(target.id).subscribe({
      next: () => {
        this.releasingId.set(null);
        this.releaseTarget.set(null);
        this.justReleased.set({ patient: target.patientFullName, test: target.labTestName });
        this.toast.success(this.translate.instant('LAB_RELEASE.RELEASED'));
        // Re-read rather than splice the row out locally: the row leaves the
        // list because the server says it did, and the waiting count and the
        // paging stay the server's answer too.
        this.load();
      },
      error: (err) => {
        // The row stays. A queue that drops a row whose release failed tells
        // the laboratory a result is signed off when it is not.
        this.releasingId.set(null);
        const message = err?.error?.message ?? this.translate.instant('LAB_RELEASE.RELEASE_ERROR');
        this.releaseError.set(message);
        this.toast.error(message);
      },
    });
  }

  dismissJustReleased(): void {
    this.justReleased.set(null);
  }

  previousPage(): void {
    if (this.pageIndex() > 0) {
      this.pageIndex.set(this.pageIndex() - 1);
      this.load();
    }
  }

  nextPage(): void {
    if (this.pageIndex() + 1 < this.totalPages()) {
      this.pageIndex.set(this.pageIndex() + 1);
      this.load();
    }
  }

  /** LOW / HIGH / NORMAL / UNSPECIFIED, as LabResultMapper.determineSeverityFlag emits them. */
  severityKey(r: LabResultResponse): string {
    const flag = (r.severityFlag ?? '').toUpperCase();
    return flag === 'LOW' || flag === 'HIGH' || flag === 'NORMAL' ? flag : 'UNSPECIFIED';
  }

  severityClass(r: LabResultResponse): string {
    const key = this.severityKey(r);
    return `flag-badge flag-${key.toLowerCase()}`;
  }

  /** A critical result on this queue is the one a reader must release first. */
  isCritical(r: LabResultResponse): boolean {
    return !!r.criticalNotifiedAt;
  }
}
