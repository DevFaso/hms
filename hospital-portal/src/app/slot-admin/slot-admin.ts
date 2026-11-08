import {
  Component,
  DestroyRef,
  ElementRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  AppointmentSlotResponse,
  DepartmentOption,
  SessionTemplateRequest,
  SessionTemplateResponse,
  SlotGenerationResult,
  SLOT_PAGE_SIZE,
  SlotInventoryService,
  VisitTypeRequest,
  VisitTypeResponse,
} from '../services/slot-inventory.service';
import { StaffService, isClinicalProvider, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { finalize } from 'rxjs';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { ClockTimePipe } from '../shared/pipes/clock-time.pipe';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { refuseFields } from '../shared/forms/invalid-fields';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
import { RangePagerComponent } from '../shared/range-pager/range-pager.component';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
type AdminSection = 'visit-types' | 'templates' | 'slots';

/** QA D1306 (round 28): the fields each form may refuse, so a fixed one loses its mark. */
const VT_FIELD_IDS = ['vt-code', 'vt-duration', 'vt-name'] as const;
const TPL_FIELD_IDS = [
  'tpl-staff',
  'tpl-department',
  'tpl-start',
  'tpl-end',
  'tpl-effective-from',
] as const;

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
  imports: [
    DateTimePipe,
    SubmitGuardDirective,
    ModalDialogDirective,
    ClockTimePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    RangePagerComponent,
  ],
  templateUrl: './slot-admin.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './slot-admin.scss',
})
export class SlotAdminComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly slotService = inject(SlotInventoryService);
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);
  private readonly roleContext = inject(RoleContextService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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
  /** QA D1390: the server's paging of the window's open slots. */
  slotPage = signal(0);
  slotPageSize = signal(SLOT_PAGE_SIZE);
  slotTotal = signal(0);
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
    this.loadSection(section);
  }

  /** Each section fetches lazily and once; a scope change empties them all first. */
  private loadSection(section: AdminSection): void {
    if (this.loadedSections.has(section)) {
      return;
    }
    if (section === 'visit-types') {
      this.loadVisitTypes();
    }
    if (section === 'templates') {
      this.loadTemplates();
    }
    if (section === 'slots') {
      this.searchSlots();
    }
  }

  /** Sections fetched for the current scope; an empty answer counts as fetched. */
  private readonly loadedSections = new Set<AdminSection>();

  dayLabel(day: number): string {
    return this.translate.instant('SLOT_ADMIN.DAY_' + day);
  }

  // ── Visit types ────────────────────────────────────────────

  loadVisitTypes(): void {
    this.vtLoading.set(true);
    this.slotService
      .listVisitTypes(this.vtShowInactive())
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.vtLoading.set(false)),
      )
      .subscribe({
        next: (rows) => {
          this.visitTypes.set(rows);
          this.loadedSections.add('visit-types');
          this.vtLoading.set(false);
        },
        error: (err) => {
          this.toast.error(
            err?.error?.message ?? this.translate.instant('SLOT_ADMIN.VT_LOAD_ERROR'),
          );
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
    // QA D1306 (round 28): the refused fields are marked, not only a toast.
    const root = this.host.nativeElement;
    if (!this.vtForm.code.trim() || !this.vtForm.name.trim()) {
      const message = this.translate.instant('SLOT_ADMIN.VT_REQUIRED');
      const blank = [
        this.vtForm.code.trim() ? null : 'vt-code',
        this.vtForm.name.trim() ? null : 'vt-name',
      ].filter((id): id is string => id !== null);
      refuseFields(root, blank, VT_FIELD_IDS, message);
      this.toast.error(message);
      return;
    }
    if (!this.vtForm.durationMinutes || this.vtForm.durationMinutes < 1) {
      const message = this.translate.instant('SLOT_ADMIN.VT_DURATION_INVALID');
      refuseFields(root, ['vt-duration'], VT_FIELD_IDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(root, [], VT_FIELD_IDS, '');
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

  async toggleVisitTypeActive(row: VisitTypeResponse): Promise<void> {
    // QA CX-010: one click retired it; restoring asks nothing.
    if (row.active) {
      const confirmed = await this.confirm.ask({
        titleKey: 'SLOT_ADMIN.VT_RETIRE_CONFIRM',
        params: { name: row.name },
        confirmKey: 'COMMON.CONFIRM',
      });
      if (!confirmed) return;
    }
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
    this.slotService
      .listTemplates(this.tplShowInactive())
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.tplLoading.set(false)),
      )
      .subscribe({
        next: (rows) => {
          this.templates.set(rows);
          this.loadedSections.add('templates');
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

  /** The first rule the template form breaks: its message key and the fields to mark. */
  private templateRefusal(form: SessionTemplateRequest): { key: string; ids: string[] } | null {
    if (!form.staffId || !form.departmentId) {
      const ids = [form.staffId ? null : 'tpl-staff', form.departmentId ? null : 'tpl-department'];
      return { key: 'SLOT_ADMIN.TPL_REQUIRED', ids: ids.filter((id): id is string => !!id) };
    }
    if (!form.startTime || !form.endTime || form.endTime <= form.startTime) {
      const ids = form.startTime ? ['tpl-end'] : ['tpl-start'];
      if (!form.startTime && !form.endTime) ids.push('tpl-end');
      return { key: 'SLOT_ADMIN.TPL_WINDOW_INVALID', ids };
    }
    if (!form.effectiveFrom) {
      return { key: 'SLOT_ADMIN.TPL_EFFECTIVE_REQUIRED', ids: ['tpl-effective-from'] };
    }
    return null;
  }

  submitTemplate(): void {
    const form = this.tplForm;
    const refusal = this.templateRefusal(form);
    if (refusal) {
      // QA D1306 (round 28): the refused fields are marked, not only a toast.
      const message = this.translate.instant(refusal.key);
      refuseFields(this.host.nativeElement, refusal.ids, TPL_FIELD_IDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], TPL_FIELD_IDS, '');
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

  async toggleTemplateActive(row: SessionTemplateResponse): Promise<void> {
    if (row.active) {
      const confirmed = await this.confirm.ask({
        titleKey: 'SLOT_ADMIN.TPL_RETIRE_CONFIRM',
        confirmKey: 'COMMON.CONFIRM',
      });
      if (!confirmed) return;
    }
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

  /**
   * QA D1390 (D1347 class): one page of the window's open slots, with the
   * server's total on screen; the search button reads the first page again.
   */
  searchSlots(page = 0): void {
    this.slotsLoading.set(true);
    this.slotService
      .searchOpenPage(
        {
          staffId: this.slotFilterStaffId || undefined,
          departmentId: this.slotFilterDepartmentId || undefined,
          from: this.slotFrom || undefined,
          to: this.slotTo || undefined,
        },
        page,
        this.slotPageSize(),
      )
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.slotsLoading.set(false)),
      )
      .subscribe({
        next: (slice) => {
          const rows = slice?.content ?? [];
          const total = slice?.totalElements ?? rows.length;
          if (rows.length === 0 && page > 0 && total > 0) {
            // The last page emptied (a slot was blocked): read the new last page.
            this.searchSlots(Math.max(0, Math.ceil(total / this.slotPageSize()) - 1));
            return;
          }
          this.slots.set(rows);
          this.slotTotal.set(total);
          this.slotPage.set(slice?.number ?? page);
          this.slotPageSize.set(slice?.size ?? this.slotPageSize());
          this.loadedSections.add('slots');
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
        this.searchSlots(this.slotPage());
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
        this.searchSlots(this.slotPage());
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
      // The pinned hospital: only reachable from buttons that are disabled
      // while no hospital is in scope, so the id is never absent here.
      const hospitalId = this.roleContext.effectiveHospitalIdForRequest() ?? undefined;
      this.staffService.list(hospitalId ?? undefined).subscribe({
        next: (staff) => this.staffOptions.set(staff.filter(isClinicalProvider)),
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
