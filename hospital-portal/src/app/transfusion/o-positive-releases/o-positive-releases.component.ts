import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  AntiDDecision,
  OPositiveProtocol,
  OPositiveReleaseDecision,
  TransfusionService,
} from '../../services/transfusion.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';

/**
 * Emergency O RhD positive releases (V380, owner decision 2026-11-25): the hospital's protocol, and every
 * release with what is still owed on it: the physician's countersignature, the biologist's review, the
 * follow-up of an RhD positive exposure.
 *
 * <p>Who may decide a release is the server's rule; this page holds no copy of it. It shows what the server
 * recorded and offers each action to the roles the server admits; the server still decides on submit.
 */
@Component({
  selector: 'app-o-positive-releases',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    ServerTimePipe,
    FormControlStyleDirective,
    SubmitGuardDirective,
  ],
  templateUrl: './o-positive-releases.component.html',
  styleUrl: './o-positive-releases.component.scss',
  changeDetection: ChangeDetectionStrategy.Eager,
})
export class OPositiveReleasesComponent implements OnInit {
  private readonly transfusion = inject(TransfusionService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /* ── Role gates: mirror TransfusionController ── */
  readonly canSetProtocol = this.roleContext.hasAnyActiveRole(['ROLE_LAB_DIRECTOR']);
  readonly canReview = this.roleContext.hasAnyActiveRole(['ROLE_LAB_DIRECTOR']);
  readonly canCountersign = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_SURGEON',
    'ROLE_LAB_DIRECTOR',
  ]);
  readonly canFollowUp = this.roleContext.hasAnyActiveRole(['ROLE_DOCTOR', 'ROLE_SURGEON']);

  readonly antiDDecisions: AntiDDecision[] = [
    'ANTI_D_IMMUNOGLOBULIN',
    'RED_CELL_EXCHANGE',
    'NOT_INDICATED',
  ];

  loading = signal(false);
  loadFailed = signal(false);
  protocol = signal<OPositiveProtocol | null>(null);
  releases = signal<OPositiveReleaseDecision[]>([]);
  openOnly = signal(true);
  busyId = signal<string | null>(null);
  savingProtocol = signal(false);
  editingProtocol = signal(false);

  /** The protocol as it is being edited. */
  draft: OPositiveProtocol = {
    active: false,
    femaleAgeCutoff: 50,
    boysLowRisk: false,
    reviewDeadlineHours: 24,
  };

  /** What is being typed for one release, by its id. */
  reviewNotes: Record<string, string> = {};
  /** Known antibody: « nobody else can be reached », stated by the person taking a second step. */
  noOtherReachable: Record<string, boolean> = {};
  followUpDecision: Record<string, AntiDDecision | ''> = {};
  followUpNotes: Record<string, string> = {};

  ngOnInit(): void {
    this.loadProtocol();
    this.loadReleases();
  }

  private loadProtocol(): void {
    this.transfusion.getPositiveProtocol().subscribe({
      next: (protocol) => this.protocol.set(protocol),
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR')),
    });
  }

  loadReleases(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.transfusion.listPositiveReleases(this.openOnly()).subscribe({
      next: (rows) => {
        this.releases.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR'));
      },
    });
  }

  setOpenOnly(openOnly: boolean): void {
    this.openOnly.set(openOnly);
    this.loadReleases();
  }

  /* ── The protocol ── */

  editProtocol(): void {
    const current = this.protocol();
    if (!current) return;
    this.draft = { ...current };
    this.editingProtocol.set(true);
  }

  cancelProtocol(): void {
    this.editingProtocol.set(false);
  }

  saveProtocol(): void {
    if (this.savingProtocol()) return;
    this.savingProtocol.set(true);
    this.transfusion
      .setPositiveProtocol({
        active: this.draft.active,
        femaleAgeCutoff: Number(this.draft.femaleAgeCutoff),
        boysLowRisk: this.draft.boysLowRisk,
        reviewDeadlineHours: Number(this.draft.reviewDeadlineHours),
      })
      .subscribe({
        next: (saved) => {
          this.protocol.set(saved);
          this.savingProtocol.set(false);
          this.editingProtocol.set(false);
          this.toast.success(this.translate.instant('TRANSFUSION.OPOS.PROTOCOL_SAVED'));
        },
        error: (err: unknown) => {
          this.savingProtocol.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('TRANSFUSION.OPOS.ACTION_ERROR'),
          );
        },
      });
  }

  /* ── What is owed on one release ── */

  countersignOwed(row: OPositiveReleaseDecision): boolean {
    return !!row.countersignDueAt && !row.countersignedAt;
  }

  reviewOwed(row: OPositiveReleaseDecision): boolean {
    return !row.reviewedAt;
  }

  /** The bag of this release was not given (returned unused, or released again): nothing follows from it. */
  bagNotGiven(row: OPositiveReleaseDecision): boolean {
    return row.disposition === 'RETURNED_UNUSED' || row.disposition === 'SUPERSEDED';
  }

  followUpOwed(row: OPositiveReleaseDecision): boolean {
    return (
      row.followUpRequired &&
      !this.bagNotGiven(row) &&
      (!row.antiDDecision || !row.patientInformedAt)
    );
  }

  /**
   * A known antibody takes two different people (signature, review). The statement that nobody else can be
   * reached is offered to whoever can still take a step; the server asks for it only of the same person.
   */
  secondPersonAsked(row: OPositiveReleaseDecision): boolean {
    return (
      row.riskTier === 'KNOWN_ANTIBODY' &&
      ((this.countersignOwed(row) && this.canCountersign) ||
        (this.reviewOwed(row) && this.canReview))
    );
  }

  countersign(row: OPositiveReleaseDecision): void {
    this.act(
      row,
      this.transfusion.countersignPositiveRelease(row.id, !!this.noOtherReachable[row.id]),
      'COUNTERSIGNED',
      { noOtherPersonReachable: 'opos-noother-' + row.id },
    );
  }

  review(row: OPositiveReleaseDecision): void {
    this.act(
      row,
      this.transfusion.reviewPositiveRelease(
        row.id,
        (this.reviewNotes[row.id] ?? '').trim(),
        !!this.noOtherReachable[row.id],
      ),
      'REVIEWED',
      { noOtherPersonReachable: 'opos-noother-' + row.id },
    );
  }

  /** While the administration is not recorded only a note is kept; it completes nothing. */
  saveProspectiveNote(row: OPositiveReleaseDecision): void {
    const note = this.noteOf(row);
    if (!note.note) return;
    this.act(row, this.transfusion.recordPositiveFollowUp(row.id, note), 'NOTE_SAVED', {
      note: 'opos-fu-note-' + row.id,
    });
  }

  /** The bag came back unused and nothing wrote it yet: the follow-up is closed with that reason. */
  closeNotGiven(row: OPositiveReleaseDecision): void {
    this.act(
      row,
      this.transfusion.recordPositiveFollowUp(row.id, { bagNotGiven: true }),
      'FOLLOW_UP_CLOSED',
    );
  }

  /** The clinician's anti-D or exchange decision, written once. */
  recordAntiD(row: OPositiveReleaseDecision): void {
    const decision = this.followUpDecision[row.id];
    if (row.antiDDecision || !decision) return;
    this.act(
      row,
      this.transfusion.recordPositiveFollowUp(row.id, {
        antiDDecision: decision,
        ...this.noteOf(row),
      }),
      'FOLLOW_UP_SAVED',
      { antiDDecision: 'opos-antid-' + row.id, note: 'opos-fu-note-' + row.id },
    );
  }

  /** The patient was told of the exposure and of the follow-up. */
  recordInformed(row: OPositiveReleaseDecision): void {
    if (row.patientInformedAt) return;
    this.act(
      row,
      this.transfusion.recordPositiveFollowUp(row.id, {
        patientInformed: true,
        ...this.noteOf(row),
      }),
      'FOLLOW_UP_SAVED',
    );
  }

  private noteOf(row: OPositiveReleaseDecision): { note?: string } {
    const note = (this.followUpNotes[row.id] ?? '').trim();
    return note ? { note } : {};
  }

  private act(
    row: OPositiveReleaseDecision,
    call: ReturnType<TransfusionService['countersignPositiveRelease']>,
    doneKey: string,
    fieldIds: Readonly<Record<string, string>> = {},
  ): void {
    if (this.busyId()) return;
    this.busyId.set(row.id);
    call.subscribe({
      next: (updated) => {
        this.busyId.set(null);
        this.releases.update((rows) => rows.map((r) => (r.id === updated.id ? updated : r)));
        delete this.reviewNotes[row.id];
        delete this.noOtherReachable[row.id];
        delete this.followUpDecision[row.id];
        delete this.followUpNotes[row.id];
        this.toast.success(this.translate.instant('TRANSFUSION.OPOS.' + doneKey));
      },
      error: (err: unknown) => {
        this.busyId.set(null);
        // A 400 naming a field marks that field with the server's reason.
        const refused = refusedFieldReasons(err, fieldIds);
        if (refused.length > 0) {
          refuseFields(
            this.host.nativeElement,
            refused.map(([id, message]) => ({ id, message })),
            Object.values(fieldIds),
          );
        }
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('TRANSFUSION.OPOS.ACTION_ERROR'),
        );
      },
    });
  }
}
