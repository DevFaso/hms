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
import { Observable } from 'rxjs';

import {
  IncompatibleMonitoringCheck,
  IncompatibleMonitoringOutcome,
  IncompatibleReleaseDecision,
  IncompatibleReleaseProtocol,
  TransfusionService,
} from '../../services/transfusion.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';

/**
 * Releases despite an incompatible crossmatch (V381, owner decision 2026-11-26, option B): the hospital's
 * fallback setting « Libération incompatible sans médecin joignable », and every such release with what is
 * still owed on it: the countersignature of a physician when it went out with none reachable, and the
 * monitoring afterwards (the provisional checklist and the haemovigilance outcome).
 *
 * <p>Who may authorise, declare or countersign is the server's rule; this page holds no copy of it. It shows
 * what the server recorded and offers each action to the roles the server admits; the server still decides.
 */
@Component({
  selector: 'app-incompatible-releases',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, ServerTimePipe, FormControlStyleDirective],
  templateUrl: './incompatible-releases.component.html',
  // The same cards and steps as the O positive releases beside it.
  styleUrl: '../o-positive-releases/o-positive-releases.component.scss',
  changeDetection: ChangeDetectionStrategy.Eager,
})
export class IncompatibleReleasesComponent implements OnInit {
  private readonly transfusion = inject(TransfusionService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /* ── Role gates: mirror TransfusionController ── */
  readonly canSetProtocol = this.roleContext.hasAnyActiveRole(['ROLE_LAB_DIRECTOR']);
  readonly canCountersign = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_SURGEON',
    'ROLE_LAB_DIRECTOR',
  ]);
  readonly canMonitor = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_SURGEON',
    'ROLE_MIDWIFE',
    'ROLE_NURSE',
  ]);

  /** What a clinician may state: whether the bag was given at all is the record's to say. */
  readonly outcomes: IncompatibleMonitoringOutcome[] = ['NO_REACTION', 'REACTION_REPORTED'];

  loading = signal(false);
  loadFailed = signal(false);
  protocol = signal<IncompatibleReleaseProtocol | null>(null);
  releases = signal<IncompatibleReleaseDecision[]>([]);
  openOnly = signal(true);
  busyId = signal<string | null>(null);
  savingProtocol = signal(false);
  editingProtocol = signal(false);

  /** The setting as it is being edited. */
  draftEnabled = false;
  draftRoles: Record<string, boolean> = {};

  /** What is being ticked or typed for one release, by its id. */
  checks: Record<string, Partial<Record<IncompatibleMonitoringCheck, boolean>>> = {};
  outcome: Record<string, IncompatibleMonitoringOutcome | ''> = {};
  notes: Record<string, string> = {};

  ngOnInit(): void {
    this.loadProtocol();
    this.loadReleases();
  }

  private loadProtocol(): void {
    this.transfusion.getIncompatibleProtocol().subscribe({
      next: (protocol) => this.protocol.set(protocol),
      error: () => this.toast.error(this.translate.instant('TRANSFUSION.LOAD_ERROR')),
    });
  }

  loadReleases(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.transfusion.listIncompatibleReleases(this.openOnly()).subscribe({
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

  /* ── The setting ── */

  editProtocol(): void {
    const current = this.protocol();
    if (!current) return;
    this.draftEnabled = current.fallbackEnabled;
    this.draftRoles = {};
    for (const role of current.allowedDeclarerRoles ?? []) {
      this.draftRoles[role] = current.declarerRoles.includes(role);
    }
    this.editingProtocol.set(true);
  }

  cancelProtocol(): void {
    this.editingProtocol.set(false);
  }

  /** The roles ticked. None ticked would mean « nobody »: the switch is the off state, so save waits for one. */
  draftRoleList(): string[] {
    return Object.keys(this.draftRoles).filter((role) => this.draftRoles[role]);
  }

  saveProtocol(): void {
    if (this.savingProtocol() || this.draftRoleList().length === 0) return;
    this.savingProtocol.set(true);
    this.transfusion
      .setIncompatibleProtocol({
        fallbackEnabled: this.draftEnabled,
        declarerRoles: this.draftRoleList(),
      })
      .subscribe({
        next: (saved) => {
          this.protocol.set(saved);
          this.savingProtocol.set(false);
          this.editingProtocol.set(false);
          this.toast.success(this.translate.instant('TRANSFUSION.INCOMPAT.SETTING_SAVED'));
        },
        error: (err: unknown) => {
          this.savingProtocol.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('TRANSFUSION.INCOMPAT.ACTION_ERROR'),
          );
        },
      });
  }

  /* ── What is owed on one release ── */

  countersignOwed(row: IncompatibleReleaseDecision): boolean {
    return !!row.countersignDueAt && !row.countersignedAt;
  }

  monitoringOwed(row: IncompatibleReleaseDecision): boolean {
    return !row.monitoringOutcome;
  }

  /** The patient could not be told before the transfusion, and nobody recorded telling them since. */
  informOwed(row: IncompatibleReleaseDecision): boolean {
    return row.patientInformed === 'NOT_POSSIBLE' && !row.patientInformedAt;
  }

  isChecked(row: IncompatibleReleaseDecision, check: IncompatibleMonitoringCheck): boolean {
    return row.monitoringChecksDone.includes(check) || !!this.checks[row.id]?.[check];
  }

  setChecked(
    row: IncompatibleReleaseDecision,
    check: IncompatibleMonitoringCheck,
    checked: boolean,
  ): void {
    this.checks[row.id] = { ...this.checks[row.id], [check]: checked };
  }

  /** The checks ticked on this page that the server does not hold yet. */
  private newChecks(row: IncompatibleReleaseDecision): IncompatibleMonitoringCheck[] {
    const ticked = this.checks[row.id] ?? {};
    return row.monitoringChecklist.filter(
      (check) => ticked[check] && !row.monitoringChecksDone.includes(check),
    );
  }

  hasNewChecks(row: IncompatibleReleaseDecision): boolean {
    return this.newChecks(row).length > 0;
  }

  private noteOf(row: IncompatibleReleaseDecision): { note?: string } {
    const note = (this.notes[row.id] ?? '').trim();
    return note ? { note } : {};
  }

  countersign(row: IncompatibleReleaseDecision): void {
    this.act(row, this.transfusion.countersignIncompatibleRelease(row.id), 'COUNTERSIGNED');
  }

  saveChecks(row: IncompatibleReleaseDecision): void {
    const checksDone = this.newChecks(row);
    if (checksDone.length === 0 && !this.noteOf(row).note) return;
    this.act(
      row,
      this.transfusion.recordIncompatibleMonitoring(row.id, {
        ...(checksDone.length > 0 ? { checksDone } : {}),
        ...this.noteOf(row),
      }),
      'CHECKS_SAVED',
      { note: 'incompat-note-' + row.id },
    );
  }

  /** The haemovigilance prompt: whether a reaction was seen. It closes the monitoring. */
  recordOutcome(row: IncompatibleReleaseDecision): void {
    const outcome = this.outcome[row.id];
    if (!outcome || row.monitoringOutcome) return;
    const checksDone = this.newChecks(row);
    this.act(
      row,
      this.transfusion.recordIncompatibleMonitoring(row.id, {
        outcome,
        ...(checksDone.length > 0 ? { checksDone } : {}),
        ...this.noteOf(row),
      }),
      'OUTCOME_SAVED',
      { outcome: 'incompat-outcome-' + row.id, note: 'incompat-note-' + row.id },
    );
  }

  recordInformed(row: IncompatibleReleaseDecision): void {
    if (!this.informOwed(row)) return;
    this.act(
      row,
      this.transfusion.recordIncompatibleMonitoring(row.id, { patientInformed: true }),
      'INFORMED_SAVED',
    );
  }

  private act(
    row: IncompatibleReleaseDecision,
    call: Observable<IncompatibleReleaseDecision>,
    doneKey: string,
    fieldIds: Readonly<Record<string, string>> = {},
  ): void {
    if (this.busyId()) return;
    this.busyId.set(row.id);
    call.subscribe({
      next: (updated) => {
        this.busyId.set(null);
        this.releases.update((rows) => rows.map((r) => (r.id === updated.id ? updated : r)));
        delete this.checks[row.id];
        delete this.outcome[row.id];
        delete this.notes[row.id];
        this.toast.success(this.translate.instant('TRANSFUSION.INCOMPAT.' + doneKey));
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
          refusalReason(err) ?? this.translate.instant('TRANSFUSION.INCOMPAT.ACTION_ERROR'),
        );
      },
    });
  }
}
