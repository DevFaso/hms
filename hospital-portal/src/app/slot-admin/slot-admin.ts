import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  AppointmentSlotResponse,
  DepartmentOption,
  SessionTemplateRequest,
  SessionTemplateResponse,
  SlotGenerationResult,
  SlotInventoryService,
  VisitTypeRequest,
  VisitTypeResponse,
} from '../services/slot-inventory.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';

type AdminSection = 'visit-types' | 'templates' | 'slots';

/**
 * Slot-inventory administration (P2 #11).
 *
 * PR #459 shipped the model with no way in: no CRUD for visit_types or
 * session_templates (so generate() could only answer slotsCreated=0), and all
 * five /slots endpoints had zero callers. This page populates the catalog,
 * runs generation, and shows what the search actually returns — turning the
 * foundation into something a scheduler can operate.
 */
@Component({
  selector: 'app-slot-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './slot-admin.html',
  styleUrl: './slot-admin.scss',
})
export class SlotAdminComponent implements OnInit {
  private readonly slotService = inject(SlotInventoryService);
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);

  section = signal<AdminSection>('visit-types');

  readonly days = [1, 2, 3, 4, 5, 6, 7];

  // ── Visit types ────────────────────────────────────────────
  visitTypes = signal<VisitTypeResponse[]>([]);
  vtLoading = signal(false);
  vtShowInactive = signal(false);
  vtModalOpen = signal(false);
  vtEditingId = signal<string | null>(null);
  vtSaving = signal(false);
  vtForm: VisitTypeRequest = this.emptyVisitType();

  // ── Session templates ──────────────────────────────────────
  templates = signal<SessionTemplateResponse[]>([]);
  tplLoading = signal(false);
  tplShowInactive = signal(false);
  tplModalOpen = signal(false);
  tplEditingId = signal<string | null>(null);
  tplSaving = signal(false);
  tplForm: SessionTemplateRequest = this.emptyTemplate();

  staffOptions = signal<StaffResponse[]>([]);
  departments = signal<DepartmentOption[]>([]);

  // ── Generation + slot preview ──────────────────────────────
  genFrom = '';
  genTo = '';
  generating = signal(false);
  lastGeneration = signal<SlotGenerationResult | null>(null);

  slots = signal<AppointmentSlotResponse[]>([]);
  slotsLoading = signal(false);
  slotFilterStaffId = '';
  slotFilterDepartmentId = '';
  slotFrom = '';
  slotTo = '';

  blockTarget = signal<AppointmentSlotResponse | null>(null);
  blockReason = '';
  blocking = signal(false);
  releasingId = signal<string | null>(null);

  ngOnInit(): void {
    this.loadVisitTypes();
  }

  setSection(section: AdminSection): void {
    this.section.set(section);
    if (section === 'visit-types' && this.visitTypes().length === 0) {
      this.loadVisitTypes();
    }
    if (section === 'templates' && this.templates().length === 0) {
      this.loadTemplates();
    }
    if (section === 'slots' && this.slots().length === 0) {
      this.searchSlots();
    }
  }

  dayLabel(day: number): string {
    return this.translate.instant('SLOT_ADMIN.DAY_' + day);
  }

  // ── Visit types ────────────────────────────────────────────

  loadVisitTypes(): void {
    this.vtLoading.set(true);
    this.slotService.listVisitTypes(this.vtShowInactive()).subscribe({
      next: (rows) => {
        this.visitTypes.set(rows);
        this.vtLoading.set(false);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('SLOT_ADMIN.VT_LOAD_ERROR'));
        this.vtLoading.set(false);
      },
    });
  }

  setVtShowInactive(value: boolean): void {
    this.vtShowInactive.set(value);
    this.loadVisitTypes();
  }

  openCreateVisitType(): void {
    this.vtForm = this.emptyVisitType();
    this.vtEditingId.set(null);
    this.ensureFormOptions();
    this.vtModalOpen.set(true);
  }

  openEditVisitType(row: VisitTypeResponse): void {
    this.vtForm = {
      departmentId: row.departmentId,
      code: row.code,
      name: row.name,
      description: row.description,
      durationMinutes: row.durationMinutes,
      patientBookable: row.patientBookable,
    };
    this.vtEditingId.set(row.id);
    this.ensureFormOptions();
    this.vtModalOpen.set(true);
  }

  closeVisitTypeModal(): void {
    this.vtModalOpen.set(false);
    this.vtEditingId.set(null);
    this.vtForm = this.emptyVisitType();
  }

  submitVisitType(): void {
    if (!this.vtForm.code.trim() || !this.vtForm.name.trim()) {
      this.toast.error(this.translate.instant('SLOT_ADMIN.VT_REQUIRED'));
      return;
    }
    if (!this.vtForm.durationMinutes || this.vtForm.durationMinutes < 1) {
      this.toast.error(this.translate.instant('SLOT_ADMIN.VT_DURATION_INVALID'));
      return;
    }
    this.vtSaving.set(true);
    const id = this.vtEditingId();
    const request = { ...this.vtForm, departmentId: this.vtForm.departmentId || undefined };
    const request$ = id
      ? this.slotService.updateVisitType(id, request)
      : this.slotService.createVisitType(request);
    request$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('SLOT_ADMIN.VT_SAVED'));
        this.vtSaving.set(false);
        this.closeVisitTypeModal();
        this.loadVisitTypes();
      },
      error: (err) => {
        // "exists but was retired; reactivate it" is actionable — verbatim.
        this.toast.error(err?.error?.message ?? this.translate.instant('SLOT_ADMIN.VT_SAVE_ERROR'));
        this.vtSaving.set(false);
      },
    });
  }

  toggleVisitTypeActive(row: VisitTypeResponse): void {
    const request$ = row.active
      ? this.slotService.deactivateVisitType(row.id)
      : this.slotService.reactivateVisitType(row.id);
    request$.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(row.active ? 'SLOT_ADMIN.VT_RETIRED' : 'SLOT_ADMIN.VT_RESTORED'),
        );
        this.loadVisitTypes();
      },
      error: (err) =>
        this.toast.error(err?.error?.message ?? this.translate.instant('SLOT_ADMIN.VT_SAVE_ERROR')),
    });
  }

  // ── Session templates ──────────────────────────────────────

  loadTemplates(): void {
    this.tplLoading.set(true);
    this.slotService.listTemplates(this.tplShowInactive()).subscribe({
      next: (rows) => {
        this.templates.set(rows);
        this.tplLoading.set(false);
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('SLOT_ADMIN.TPL_LOAD_ERROR'),
        );
        this.tplLoading.set(false);
      },
    });
  }

  setTplShowInactive(value: boolean): void {
    this.tplShowInactive.set(value);
    this.loadTemplates();
  }

  openCreateTemplate(): void {
    this.tplForm = this.emptyTemplate();
    this.tplEditingId.set(null);
    this.ensureFormOptions();
    this.tplModalOpen.set(true);
  }

  openEditTemplate(row: SessionTemplateResponse): void {
    this.tplForm = {
      staffId: row.staffId,
      departmentId: row.departmentId,
      visitTypeId: row.visitTypeId,
      dayOfWeek: row.dayOfWeek,
      startTime: row.startTime,
      endTime: row.endTime,
      slotMinutes: row.slotMinutes,
      effectiveFrom: row.effectiveFrom,
      effectiveTo: row.effectiveTo,
      notes: row.notes,
    };
    this.tplEditingId.set(row.id);
    this.ensureFormOptions();
    this.tplModalOpen.set(true);
  }

  closeTemplateModal(): void {
    this.tplModalOpen.set(false);
    this.tplEditingId.set(null);
    this.tplForm = this.emptyTemplate();
  }

  submitTemplate(): void {
    const form = this.tplForm;
    if (!form.staffId || !form.departmentId) {
      this.toast.error(this.translate.instant('SLOT_ADMIN.TPL_REQUIRED'));
      return;
    }
    if (!form.startTime || !form.endTime || form.endTime <= form.startTime) {
      this.toast.error(this.translate.instant('SLOT_ADMIN.TPL_WINDOW_INVALID'));
      return;
    }
    if (!form.effectiveFrom) {
      this.toast.error(this.translate.instant('SLOT_ADMIN.TPL_EFFECTIVE_REQUIRED'));
      return;
    }
    this.tplSaving.set(true);
    const id = this.tplEditingId();
    const request = { ...form, visitTypeId: form.visitTypeId || undefined };
    const request$ = id
      ? this.slotService.updateTemplate(id, request)
      : this.slotService.createTemplate(request);
    request$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('SLOT_ADMIN.TPL_SAVED'));
        this.tplSaving.set(false);
        this.closeTemplateModal();
        this.loadTemplates();
      },
      error: (err) => {
        // The backend refusals name the problem ("window shorter than one
        // slot; nothing would ever be generated") — verbatim, always.
        this.toast.error(
          err?.error?.message ?? this.translate.instant('SLOT_ADMIN.TPL_SAVE_ERROR'),
        );
        this.tplSaving.set(false);
      },
    });
  }

  toggleTemplateActive(row: SessionTemplateResponse): void {
    const request$ = row.active
      ? this.slotService.deactivateTemplate(row.id)
      : this.slotService.reactivateTemplate(row.id);
    request$.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(row.active ? 'SLOT_ADMIN.TPL_RETIRED' : 'SLOT_ADMIN.TPL_RESTORED'),
        );
        this.loadTemplates();
      },
      error: (err) =>
        this.toast.error(
          err?.error?.message ?? this.translate.instant('SLOT_ADMIN.TPL_SAVE_ERROR'),
        ),
    });
  }

  // ── Generation + slots ─────────────────────────────────────

  runGeneration(): void {
    if (this.generating()) {
      return;
    }
    // Guard set BEFORE dispatch — the #443 lesson.
    this.generating.set(true);
    this.slotService.generate(this.genFrom || undefined, this.genTo || undefined).subscribe({
      next: (result) => {
        this.lastGeneration.set(result);
        this.generating.set(false);
        this.toast.success(
          this.translate.instant('SLOT_ADMIN.GENERATED', {
            created: result.slotsCreated,
            templates: result.templatesApplied,
            skipped: result.skippedExisting,
          }),
        );
        if (this.section() === 'slots') {
          this.searchSlots();
        }
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('SLOT_ADMIN.GENERATE_ERROR'),
        );
        this.generating.set(false);
      },
    });
  }

  searchSlots(): void {
    this.slotsLoading.set(true);
    this.slotService
      .searchOpen({
        staffId: this.slotFilterStaffId || undefined,
        departmentId: this.slotFilterDepartmentId || undefined,
        from: this.slotFrom || undefined,
        to: this.slotTo || undefined,
        limit: 200,
      })
      .subscribe({
        next: (rows) => {
          this.slots.set(rows);
          this.slotsLoading.set(false);
          this.ensureFormOptions();
        },
        error: (err) => {
          this.toast.error(
            err?.error?.message ?? this.translate.instant('SLOT_ADMIN.SLOTS_LOAD_ERROR'),
          );
          this.slotsLoading.set(false);
        },
      });
  }

  confirmBlock(slot: AppointmentSlotResponse): void {
    this.blockReason = '';
    this.blockTarget.set(slot);
  }

  cancelBlock(): void {
    this.blockTarget.set(null);
  }

  executeBlock(): void {
    const target = this.blockTarget();
    if (!target || this.blocking()) {
      return;
    }
    this.blocking.set(true);
    this.slotService.block(target.id, this.blockReason || undefined).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('SLOT_ADMIN.BLOCKED'));
        this.blocking.set(false);
        this.blockTarget.set(null);
        this.searchSlots();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('SLOT_ADMIN.BLOCK_ERROR'));
        this.blocking.set(false);
      },
    });
  }

  release(slot: AppointmentSlotResponse): void {
    if (this.releasingId()) {
      return;
    }
    this.releasingId.set(slot.id);
    this.slotService.release(slot.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('SLOT_ADMIN.RELEASED'));
        this.releasingId.set(null);
        this.searchSlots();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('SLOT_ADMIN.RELEASE_ERROR'));
        this.releasingId.set(null);
      },
    });
  }

  // ── Shared options ─────────────────────────────────────────

  private ensureFormOptions(): void {
    if (this.staffOptions().length === 0) {
      const hospitalId =
        this.roleContext.activeHospitalId ?? this.auth.getHospitalId() ?? undefined;
      this.staffService.list(hospitalId ?? undefined).subscribe({
        next: (staff) => this.staffOptions.set(staff),
        error: () => this.staffOptions.set([]),
      });
    }
    if (this.departments().length === 0) {
      this.slotService.listDepartments().subscribe({
        next: (deps) => this.departments.set(deps),
        error: () => this.departments.set([]),
      });
    }
  }

  private emptyVisitType(): VisitTypeRequest {
    return { code: '', name: '', durationMinutes: 30, patientBookable: false };
  }

  private emptyTemplate(): SessionTemplateRequest {
    return {
      staffId: '',
      departmentId: '',
      dayOfWeek: 1,
      startTime: '09:00',
      endTime: '12:00',
      slotMinutes: 20,
      effectiveFrom: '',
    };
  }
}
