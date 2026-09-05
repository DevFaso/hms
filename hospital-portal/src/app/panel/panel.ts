import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';

import {
  PanelAssignment,
  PanelOverviewRow,
  PanelRole,
  PanelService,
} from '../services/panel.service';
import { StaffResponse, StaffService } from '../services/staff.service';
import { PatientResponse } from '../services/patient.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

/**
 * Panel management (Tier 2 item 37).
 *
 * <p>Three surfaces on one page: the caller's own live panel (or a hint
 * when they have no staff profile at the active hospital), the empanelment
 * form (shared patient picker + a staff select, mirroring the backend's
 * supersede-on-reassign rule), and — admins only — the per-(provider, role)
 * overview whose rows drill into that provider's panel for that role.
 *
 * <p>Every request here is hospital-pinned server-side, so a super-admin in
 * GLOBAL view has nothing to ask yet: loads are deferred until the scope
 * chip pins a hospital, and everything reloads (and the drilldown clears)
 * on scopeChange.
 */
@Component({
  selector: 'app-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule,
    TranslateModule,
    PatientPickerComponent,
    HospitalScopeChipComponent,
  ],
  templateUrl: './panel.html',
  styleUrl: './panel.scss',
})
export class PanelComponent implements OnInit {
  private readonly panelService = inject(PanelService);
  private readonly staffService = inject(StaffService);
  private readonly roleCtx = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly roles: PanelRole[] = ['PRIMARY_PROVIDER', 'CHW'];

  /** Both worklists ask for one server page; the server caps at 200 anyway. */
  private static readonly PAGE_SIZE = 200;

  /* ── my panel ── */
  myPanelRows = signal<PanelAssignment[]>([]);
  myPanelTotal = signal(0);
  myPanelLoading = signal(false);
  /** True when the caller has no staff profile here — a hint, not an error. */
  noStaffProfile = signal(false);

  /* ── empanelment form ── */
  showAssign = signal(false);
  saving = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  staffOptions = signal<StaffResponse[]>([]);
  staffFilter = signal('');
  formStaffId = signal<string>('');
  formRole = signal<PanelRole>('PRIMARY_PROVIDER');
  formAssignedOn = signal<string>('');

  /* ── admin overview ── */
  overviewRows = signal<PanelOverviewRow[]>([]);
  overviewFailed = signal(false);
  drilldownProvider = signal<PanelOverviewRow | null>(null);
  drilldownRows = signal<PanelAssignment[]>([]);
  drilldownTotal = signal(0);

  /* ── end-assignment modal ── */
  ending = signal<PanelAssignment | null>(null);
  endReason = signal('');

  /* ── dialog focus management (registries pattern) ── */
  private readonly assignDialog = viewChild<ElementRef<HTMLElement>>('assignDialog');
  private readonly endDialog = viewChild<ElementRef<HTMLElement>>('endDialog');
  private dialogOpener: HTMLElement | null = null;

  readonly pickerHospitalId = computed(() => this.roleCtx.effectiveHospitalIdForRequest());
  /** Null in a super-admin's global view — nothing hospital-pinned can load yet. */
  readonly scopeReady = computed(() => this.pickerHospitalId() != null);
  readonly isAdmin = computed(() =>
    this.roleCtx.hasAnyActiveRole(['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN']),
  );

  /** The staff select filtered client-side; see the ceiling note in openAssign. */
  readonly filteredStaff = computed(() => {
    const term = this.staffFilter().trim().toLowerCase();
    const options = this.staffOptions();
    if (!term) return options;
    return options.filter((s) => (s.name ?? '').toLowerCase().includes(term));
  });

  readonly myPanelTruncated = computed(() => this.myPanelTotal() > this.myPanelRows().length);
  readonly drilldownTruncated = computed(() => this.drilldownTotal() > this.drilldownRows().length);

  ngOnInit(): void {
    this.reloadForScope();
  }

  /** The chip pinned (or cleared) a hospital: all panel state belongs to the old scope. */
  onScopeChange(_hospitalId: string | null): void {
    this.reloadForScope();
  }

  private reloadForScope(): void {
    this.drilldownProvider.set(null);
    this.drilldownRows.set([]);
    this.drilldownTotal.set(0);
    this.overviewRows.set([]);
    this.overviewFailed.set(false);
    this.myPanelRows.set([]);
    this.myPanelTotal.set(0);
    this.noStaffProfile.set(false);
    this.staffOptions.set([]);
    if (!this.scopeReady()) {
      // Global view: the backend refuses unpinned panel reads by design.
      return;
    }
    this.loadMyPanel();
    if (this.isAdmin()) {
      this.loadOverview();
    }
  }

  loadMyPanel(): void {
    this.myPanelLoading.set(true);
    this.panelService.myPanel(0, PanelComponent.PAGE_SIZE).subscribe({
      next: (page) => {
        this.myPanelLoading.set(false);
        this.myPanelRows.set(page.content ?? []);
        this.myPanelTotal.set(page.totalElements ?? 0);
        this.noStaffProfile.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.myPanelLoading.set(false);
        // 400 "no staff profile" is an expected state for pure-admin
        // accounts, not an outage — render the hint, keep the page usable.
        if (err.status === 400) {
          this.noStaffProfile.set(true);
        } else {
          this.toast.error(this.translate.instant('PANEL.LOAD_FAILED'));
        }
      },
    });
  }

  loadOverview(): void {
    this.panelService.overview().subscribe({
      next: (rows) => {
        this.overviewRows.set(rows);
        this.overviewFailed.set(false);
      },
      // Unavailable, never "no panels": an outage must not read as an
      // empty hospital (same stance as the registries counts).
      error: () => this.overviewFailed.set(true),
    });
  }

  openAssign(event?: Event): void {
    this.dialogOpener = (event?.currentTarget as HTMLElement) ?? null;
    this.showAssign.set(true);
    this.selectedPatient.set(null);
    this.staffFilter.set('');
    this.formStaffId.set('');
    this.formRole.set('PRIMARY_PROVIDER');
    this.formAssignedOn.set('');
    if (this.staffOptions().length === 0) {
      // KNOWN CEILING: StaffService.list returns the first 200 active staff.
      // The client-side filter makes those findable; a facility beyond 200
      // clinical staff needs a paged/searchable owner endpoint (deferred —
      // this deployment's hospitals are far below that today).
      this.staffService.list(this.pickerHospitalId() ?? undefined).subscribe({
        next: (staff) => this.staffOptions.set(staff),
        error: () => this.toast.error(this.translate.instant('PANEL.STAFF_LOAD_FAILED')),
      });
    }
    this.focusDialogSoon(() => this.assignDialog()?.nativeElement);
  }

  closeAssign(): void {
    this.showAssign.set(false);
    this.restoreOpenerFocus();
  }

  onPatientSelected(patient: PatientResponse | null): void {
    this.selectedPatient.set(patient);
  }

  submitAssign(): void {
    const patient = this.selectedPatient();
    const staffId = this.formStaffId();
    if (!patient || !staffId || this.saving()) return;
    this.saving.set(true);
    this.panelService
      .assign(patient.id, {
        providerStaffId: staffId,
        panelRole: this.formRole(),
        assignedOn: this.formAssignedOn() || undefined,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.closeAssign();
          this.toast.success(this.translate.instant('PANEL.ASSIGNED'));
          this.loadMyPanel();
          if (this.isAdmin()) this.loadOverview();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(err.error?.message ?? this.translate.instant('PANEL.ACTION_FAILED'));
        },
      });
  }

  openDrilldown(row: PanelOverviewRow): void {
    this.drilldownProvider.set(row);
    this.drilldownRows.set([]);
    this.drilldownTotal.set(0);
    // Role passed through: the overview counts one (provider, role) pair,
    // so the drilldown must show exactly that cohort.
    this.panelService
      .providerPanel(row.providerStaffId, row.panelRole, 0, PanelComponent.PAGE_SIZE)
      .subscribe({
        next: (page) => {
          this.drilldownRows.set(page.content ?? []);
          this.drilldownTotal.set(page.totalElements ?? 0);
        },
        error: () => this.toast.error(this.translate.instant('PANEL.LOAD_FAILED')),
      });
  }

  openEnd(assignment: PanelAssignment, event?: Event): void {
    this.dialogOpener = (event?.currentTarget as HTMLElement) ?? null;
    this.ending.set(assignment);
    this.endReason.set('');
    this.focusDialogSoon(() => this.endDialog()?.nativeElement);
  }

  closeEnd(): void {
    this.ending.set(null);
    this.restoreOpenerFocus();
  }

  submitEnd(): void {
    const assignment = this.ending();
    const reason = this.endReason().trim();
    if (!assignment || !reason || this.saving()) return;
    this.saving.set(true);
    this.panelService.end(assignment.patientId, assignment.id, reason).subscribe({
      next: () => {
        this.saving.set(false);
        this.closeEnd();
        this.toast.success(this.translate.instant('PANEL.ENDED'));
        this.loadMyPanel();
        const drill = this.drilldownProvider();
        if (drill) this.openDrilldown(drill);
        if (this.isAdmin()) this.loadOverview();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('PANEL.ACTION_FAILED'));
      },
    });
  }

  roleKey(role: PanelRole): string {
    return role === 'CHW' ? 'PANEL.ROLE_CHW' : 'PANEL.ROLE_PRIMARY';
  }

  /* ── Dialog focus: move in on open, cycle on Tab, restore on close ── */

  trapTab(event: KeyboardEvent, dialog: HTMLElement): void {
    const focusables = Array.from(
      dialog.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((el) => !el.hasAttribute('disabled'));
    if (focusables.length === 0) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusDialogSoon(resolve: () => HTMLElement | undefined): void {
    // The dialog renders on the next change-detection pass.
    setTimeout(() => resolve()?.focus(), 0);
  }

  private restoreOpenerFocus(): void {
    this.dialogOpener?.focus();
    this.dialogOpener = null;
  }
}
