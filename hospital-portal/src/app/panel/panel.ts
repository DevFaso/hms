import { Component, OnInit, computed, inject, signal } from '@angular/core';
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
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

/**
 * Panel management (Tier 2 item 37).
 *
 * <p>Three surfaces on one page: the caller's own live panel (every
 * clinical role has one — or a hint when they have no staff profile at
 * the active hospital), the empanelment form (shared patient picker +
 * a staff select, mirroring the backend's supersede-on-reassign rule),
 * and — admins only — the per-provider overview whose rows drill into
 * that provider's panel.
 */
@Component({
  selector: 'app-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, TranslateModule, PatientPickerComponent],
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
  formStaffId = signal<string>('');
  formRole = signal<PanelRole>('PRIMARY_PROVIDER');
  formAssignedOn = signal<string>('');

  /* ── admin overview ── */
  overviewRows = signal<PanelOverviewRow[]>([]);
  overviewFailed = signal(false);
  drilldownProvider = signal<PanelOverviewRow | null>(null);
  drilldownRows = signal<PanelAssignment[]>([]);

  /* ── end-assignment modal ── */
  ending = signal<PanelAssignment | null>(null);
  endReason = signal('');

  readonly pickerHospitalId = computed(() => this.roleCtx.effectiveHospitalIdForRequest());
  readonly isAdmin = computed(() =>
    this.roleCtx.hasAnyActiveRole(['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN']),
  );

  ngOnInit(): void {
    this.loadMyPanel();
    if (this.isAdmin()) {
      this.loadOverview();
    }
  }

  loadMyPanel(): void {
    this.myPanelLoading.set(true);
    this.panelService.myPanel(0, 200).subscribe({
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

  openAssign(): void {
    this.showAssign.set(true);
    this.selectedPatient.set(null);
    this.formStaffId.set('');
    this.formRole.set('PRIMARY_PROVIDER');
    this.formAssignedOn.set('');
    if (this.staffOptions().length === 0) {
      this.staffService.list(this.pickerHospitalId() ?? undefined).subscribe({
        next: (staff) => this.staffOptions.set(staff),
        error: () => this.toast.error(this.translate.instant('PANEL.STAFF_LOAD_FAILED')),
      });
    }
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
          this.showAssign.set(false);
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
    this.panelService.providerPanel(row.providerStaffId, 0, 200).subscribe({
      next: (page) => this.drilldownRows.set(page.content ?? []),
      error: () => this.toast.error(this.translate.instant('PANEL.LOAD_FAILED')),
    });
  }

  openEnd(assignment: PanelAssignment): void {
    this.ending.set(assignment);
    this.endReason.set('');
  }

  submitEnd(): void {
    const assignment = this.ending();
    const reason = this.endReason().trim();
    if (!assignment || !reason || this.saving()) return;
    this.saving.set(true);
    this.panelService.end(assignment.patientId, assignment.id, reason).subscribe({
      next: () => {
        this.saving.set(false);
        this.ending.set(null);
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
}
