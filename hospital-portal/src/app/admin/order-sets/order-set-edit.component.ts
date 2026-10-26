import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  OrderItemType,
  OrderSetItem,
  OrderSetRequest,
  OrderSetService,
  OrderSetSummary,
} from '../../services/order-set.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { AuthService } from '../../auth/auth.service';

type Mode = 'new' | 'edit';

const ADMISSION_TYPES = [
  'EMERGENCY',
  'ELECTIVE',
  'URGENT',
  'NEWBORN',
  'TRANSFER',
  'OBSERVATION',
  'DAY_CASE',
  'LABOR_DELIVERY',
  'PSYCHIATRIC',
];

/**
 * A wire value and the key of what the user reads for it. QA D859: the item
 * types, units, routes and frequencies were shown as their wire codes
 * (MEDICATION, tablet, TOPICAL, QID) in French and Spanish.
 */
interface Choice<V extends string = string> {
  value: V;
  /** Absent for a symbol every language writes the same way (mg, g, mL). */
  labelKey?: string;
}

const ORDER_ITEM_TYPES: Choice<OrderItemType>[] = [
  { value: 'MEDICATION', labelKey: 'ORDER_SETS.ITEM_TYPE.MEDICATION' },
  { value: 'LAB', labelKey: 'ORDER_SETS.ITEM_TYPE.LAB' },
  { value: 'IMAGING', labelKey: 'ORDER_SETS.ITEM_TYPE.IMAGING' },
  { value: 'DIET', labelKey: 'ORDER_SETS.ITEM_TYPE.DIET' },
  { value: 'ACTIVITY', labelKey: 'ORDER_SETS.ITEM_TYPE.ACTIVITY' },
  { value: 'MONITORING', labelKey: 'ORDER_SETS.ITEM_TYPE.MONITORING' },
];

/** Maps an item type to the JSONB key the backend dispatcher reads as the item name. */
const NAME_KEY_BY_TYPE: Record<string, keyof OrderSetItem> = {
  MEDICATION: 'medicationName',
  LAB: 'orderName',
  IMAGING: 'studyType',
  DIET: 'dietType',
  ACTIVITY: 'activityLevel',
  MONITORING: 'monitoringType',
};

const DOSE_UNITS: Choice[] = [
  { value: 'mg' },
  { value: 'g' },
  { value: 'mcg', labelKey: 'ORDER_SETS.DOSE_UNIT.MCG' },
  { value: 'mL' },
  { value: 'IU', labelKey: 'ORDER_SETS.DOSE_UNIT.IU' },
  { value: 'tablet', labelKey: 'ORDER_SETS.DOSE_UNIT.TABLET' },
  { value: 'puff', labelKey: 'ORDER_SETS.DOSE_UNIT.PUFF' },
  { value: 'drop', labelKey: 'ORDER_SETS.DOSE_UNIT.DROP' },
  { value: 'unit', labelKey: 'ORDER_SETS.DOSE_UNIT.UNIT' },
];
const FREQUENCY_PRESETS: Choice[] = [
  { value: 'QD', labelKey: 'ORDER_SETS.FREQUENCY.QD' },
  { value: 'BID', labelKey: 'ORDER_SETS.FREQUENCY.BID' },
  { value: 'TID', labelKey: 'ORDER_SETS.FREQUENCY.TID' },
  { value: 'QID', labelKey: 'ORDER_SETS.FREQUENCY.QID' },
  { value: 'Q4H', labelKey: 'ORDER_SETS.FREQUENCY.Q4H' },
  { value: 'Q6H', labelKey: 'ORDER_SETS.FREQUENCY.Q6H' },
  { value: 'Q8H', labelKey: 'ORDER_SETS.FREQUENCY.Q8H' },
  { value: 'PRN', labelKey: 'ORDER_SETS.FREQUENCY.PRN' },
];
const ROUTES: Choice[] = [
  { value: 'PO', labelKey: 'ORDER_SETS.ROUTE.PO' },
  { value: 'IV', labelKey: 'ORDER_SETS.ROUTE.IV' },
  { value: 'IM', labelKey: 'ORDER_SETS.ROUTE.IM' },
  { value: 'SC', labelKey: 'ORDER_SETS.ROUTE.SC' },
  { value: 'PR', labelKey: 'ORDER_SETS.ROUTE.PR' },
  { value: 'INH', labelKey: 'ORDER_SETS.ROUTE.INH' },
  { value: 'TOPICAL', labelKey: 'ORDER_SETS.ROUTE.TOPICAL' },
  { value: 'NASAL', labelKey: 'ORDER_SETS.ROUTE.NASAL' },
];

interface EditableItem extends OrderSetItem {
  _synonymsText: string;
}

/**
 * Admin form for authoring or editing a CPOE order-set template. Each item is
 * edited in a structured per-row form (gap #19): synonyms (chip-style list)
 * and dose ranges (min/max/unit/frequency) replace the v0 raw-JSON textarea.
 *
 * <p>Save behaviour: in {@code edit} mode the backend creates a new
 * version row and freezes the parent (see V65). The version-history
 * sidebar walks the parent chain via {@link OrderSetService#versions}.
 */
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

@Component({
  selector: 'app-order-set-edit',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, FormsModule, RouterLink, TranslateModule, EnumLabelPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './order-set-edit.component.html',
  styleUrls: ['./order-set-edit.component.scss'],
})
export class OrderSetEditComponent implements OnInit {
  protected readonly admissionTypes = ADMISSION_TYPES;
  protected readonly orderItemTypes = ORDER_ITEM_TYPES;
  protected readonly doseUnits = DOSE_UNITS;
  protected readonly frequencyPresets = FREQUENCY_PRESETS;
  protected readonly routes = ROUTES;

  protected readonly mode = signal<Mode>('new');
  protected readonly saving = signal(false);
  protected readonly loadError = signal(false);
  protected readonly versionHistory = signal<OrderSetSummary[]>([]);
  protected readonly items = signal<EditableItem[]>([]);
  /**
   * QA D859: an empty save said nothing on the page (the toast sits outside
   * the form). The fields that block a save are now marked where they are.
   */
  protected readonly nameMissing = signal(false);
  protected readonly itemsMissingName = signal<number[]>([]);

  protected form: {
    name: string;
    description: string;
    admissionType: string;
    clinicalGuidelines: string;
  } = {
    name: '',
    description: '',
    admissionType: 'ELECTIVE',
    clinicalGuidelines: '',
  };
  private editingId: string | null = null;

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly orderSetService = inject(OrderSetService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id || id === 'new') {
      this.mode.set('new');
      this.items.set([this.blankItem()]);
      return;
    }
    this.mode.set('edit');
    this.editingId = id;
    this.orderSetService.getById(id).subscribe({
      next: (os) => {
        this.form.name = os.name;
        this.form.description = os.description ?? '';
        this.form.admissionType = os.admissionType;
        this.form.clinicalGuidelines = os.clinicalGuidelines ?? '';
        this.items.set(this.toEditable(os.orderItems ?? []));
      },
      error: () => this.loadError.set(true),
    });
    this.orderSetService.versions(id).subscribe({
      next: (rows) => this.versionHistory.set(rows),
      error: () => this.versionHistory.set([]),
    });
  }

  protected addItem(): void {
    this.items.update((rows) => [...rows, this.blankItem()]);
  }

  protected removeItem(index: number): void {
    this.items.update((rows) => rows.filter((_, i) => i !== index));
    this.itemsMissingName.set([]);
  }

  protected itemMissingName(index: number): boolean {
    return this.itemsMissingName().includes(index);
  }

  /** What an option reads as: its translation, or the symbol itself. */
  protected choiceLabel(choice: Choice): string {
    return choice.labelKey ? this.translate.instant(choice.labelKey) : choice.value;
  }

  protected trackByIndex(index: number): number {
    return index;
  }

  protected save(): void {
    const itemsOut = this.items().map((it) => this.toPersisted(it));
    const nameMissing = !this.form.name?.trim();
    const unnamed = itemsOut
      .map((it, i) => (this.itemDisplayName(it).trim() ? -1 : i))
      .filter((i) => i !== -1);
    this.nameMissing.set(nameMissing);
    this.itemsMissingName.set(unnamed);
    if (nameMissing) {
      this.toast.error(this.translate.instant('ORDER_SETS.NAME_REQUIRED'));
      return;
    }
    if (itemsOut.length === 0) {
      this.toast.error(this.translate.instant('ORDER_SETS.ITEM_REQUIRED'));
      return;
    }
    if (unnamed.length > 0) {
      this.toast.error(
        this.translate.instant('ORDER_SETS.ITEM_NAME_MISSING', { position: unnamed[0] + 1 }),
      );
      return;
    }

    const hospitalId = this.roleContext.activeHospitalId ?? '';
    const staffId = this.auth.getUserProfile()?.staffId ?? '';
    if (!hospitalId || !staffId) {
      this.toast.error(this.translate.instant('ORDER_SETS.CONTEXT_MISSING'));
      return;
    }

    const req: OrderSetRequest = {
      name: this.form.name,
      description: this.form.description,
      admissionType: this.form.admissionType,
      hospitalId,
      orderItems: itemsOut,
      clinicalGuidelines: this.form.clinicalGuidelines,
      createdByStaffId: staffId,
    };

    this.saving.set(true);
    const op$ =
      this.mode() === 'edit' && this.editingId
        ? this.orderSetService.update(this.editingId, req)
        : this.orderSetService.create(req);
    op$.subscribe({
      next: () => {
        this.saving.set(false);
        this.toast.success(this.translate.instant('ORDER_SETS.SAVED'));
        this.router.navigate(['/admin/order-sets']);
      },
      error: () => {
        this.saving.set(false);
        this.toast.error(this.translate.instant('ORDER_SETS.SAVE_FAILED'));
      },
    });
  }

  protected isMedicationLike(type: string | undefined): boolean {
    return !type || type === 'MEDICATION';
  }

  protected nameKey(type: string | undefined): keyof OrderSetItem {
    const key = type ? NAME_KEY_BY_TYPE[type] : undefined;
    return key ?? 'medicationName';
  }

  protected itemName(it: OrderSetItem): string {
    return (it[this.nameKey(it.orderType)] as string) ?? '';
  }

  protected setItemName(it: EditableItem, value: string): void {
    const key = this.nameKey(it.orderType);
    (it as Record<string, unknown>)[key as string] = value;
  }

  protected itemDisplayName(it: OrderSetItem): string {
    return (
      (it.medicationName as string) ||
      (it.orderName as string) ||
      (it.testName as string) ||
      (it.studyType as string) ||
      (it.dietType as string) ||
      (it.activityLevel as string) ||
      (it.monitoringType as string) ||
      ''
    );
  }

  private blankItem(): EditableItem {
    return {
      orderType: 'MEDICATION',
      medicationName: '',
      dose: '',
      route: '',
      frequency: '',
      synonyms: [],
      _synonymsText: '',
    };
  }

  private toEditable(items: OrderSetItem[]): EditableItem[] {
    if (items.length === 0) return [this.blankItem()];
    return items.map((it) => ({
      ...it,
      synonyms: Array.isArray(it.synonyms) ? it.synonyms : [],
      _synonymsText: Array.isArray(it.synonyms) ? it.synonyms.join(', ') : '',
    }));
  }

  private toPersisted(it: EditableItem): OrderSetItem {
    const { _synonymsText, ...rest } = it;
    const synonyms = (_synonymsText || '')
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    const out: OrderSetItem = { ...rest };
    if (synonyms.length > 0) {
      out.synonyms = synonyms;
    } else {
      delete out.synonyms;
    }
    if (out.doseRangeMin === undefined || (out.doseRangeMin as unknown) === null) {
      delete out.doseRangeMin;
    }
    if (out.doseRangeMax === undefined || (out.doseRangeMax as unknown) === null) {
      delete out.doseRangeMax;
    }
    return out;
  }
}
