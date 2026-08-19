import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

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

const ORDER_ITEM_TYPES: OrderItemType[] = [
  'MEDICATION',
  'LAB',
  'IMAGING',
  'DIET',
  'ACTIVITY',
  'MONITORING',
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

const DOSE_UNITS = ['mg', 'g', 'mcg', 'mL', 'IU', 'tablet', 'puff', 'drop', 'unit'];
const FREQUENCY_PRESETS = ['QD', 'BID', 'TID', 'QID', 'Q4H', 'Q6H', 'Q8H', 'PRN'];
const ROUTES = ['PO', 'IV', 'IM', 'SC', 'PR', 'INH', 'TOPICAL', 'NASAL'];

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
@Component({
  selector: 'app-order-set-edit',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule],
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
  }

  protected trackByIndex(index: number): number {
    return index;
  }

  protected save(): void {
    const itemsOut = this.items().map((it) => this.toPersisted(it));
    if (itemsOut.length === 0) {
      this.toast.error('Add at least one order item');
      return;
    }
    const missing = itemsOut.findIndex((it) => !this.itemDisplayName(it));
    if (missing !== -1) {
      this.toast.error(`Item #${missing + 1} is missing a name`);
      return;
    }

    const hospitalId = this.roleContext.activeHospitalId ?? '';
    const staffId = this.auth.getUserProfile()?.staffId ?? '';
    if (!hospitalId || !staffId) {
      this.toast.error('Missing hospital or staff context');
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
        this.toast.success('Order set saved');
        this.router.navigate(['/admin/order-sets']);
      },
      error: () => {
        this.saving.set(false);
        this.toast.error('Could not save order set');
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
