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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  OrderItemType,
  OrderSetItem,
  OrderSetRequest,
  OrderSetService,
  OrderSetSummary,
} from '../../services/order-set.service';
import { MedicationCatalogItemResponse, PharmacyService } from '../../services/pharmacy.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { AuthService } from '../../auth/auth.service';
import { focusFirstInvalid } from '../../shared/forms/invalid-fields';

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
/** QA D1378: the coded as-needed frequency preset; picking it ticks the item as needed. */
const PRN_PRESET = 'PRN';

export const FREQUENCY_PRESETS: Choice[] = [
  { value: 'QD', labelKey: 'ORDER_SETS.FREQUENCY.QD' },
  { value: 'BID', labelKey: 'ORDER_SETS.FREQUENCY.BID' },
  { value: 'TID', labelKey: 'ORDER_SETS.FREQUENCY.TID' },
  { value: 'QID', labelKey: 'ORDER_SETS.FREQUENCY.QID' },
  { value: 'Q4H', labelKey: 'ORDER_SETS.FREQUENCY.Q4H' },
  { value: 'Q6H', labelKey: 'ORDER_SETS.FREQUENCY.Q6H' },
  { value: 'Q8H', labelKey: 'ORDER_SETS.FREQUENCY.Q8H' },
  { value: PRN_PRESET, labelKey: 'ORDER_SETS.FREQUENCY.PRN' },
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
import { allRows } from '../../shared/all-pages';
import { refusalReason } from '../../shared/refusal-reason';
import { joinReasons } from '../../shared/required-fields';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { PRN_MAX_INTERVAL_HOURS } from '../../shared/as-needed-frequency';
import { serverFieldErrors } from '../../shared/forms/server-field-errors';

@Component({
  selector: 'app-order-set-edit',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    EnumLabelPipe,
  ],
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
  /**
   * QA round 30 (user decision 2026-11-02): an as-needed (PRN) item states
   * the minimum interval between two doses; the order it creates needs it
   * (an item without one is not applied). The items that lack it.
   */
  protected readonly itemsMissingPrnInterval = signal<number[]>([]);
  /** QA D1491: the medication items with no frequency, marked on the press of Save. */
  protected readonly itemsMissingFrequency = signal<number[]>([]);
  protected readonly prnMaxIntervalHours = PRN_MAX_INTERVAL_HOURS;
  /**
   * QA D1396: the catalogue items an item's name answers to, by item row. A
   * name several items carry, one of them controlled, can only be applied once
   * the author chooses the exact one (`orderItems[i].medicationCatalogItemId`).
   */
  protected readonly catalogChoices = signal<Record<number, MedicationCatalogItemResponse[]>>({});

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
  private readonly pharmacy = inject(PharmacyService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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
    this.itemsMissingFrequency.set([]);
  }

  protected itemMissingName(index: number): boolean {
    return this.itemsMissingName().includes(index);
  }

  /**
   * QA round 30 / QA D1378 / D1382 (round 32): a medication item its author
   * TICKS as needed states its minimum interval. Never read from the
   * frequency: the server refuses a conditional frequency without the tick
   * (`orderItems[i].asNeeded`) and a ticked item without its interval, at
   * save, so an order set never applies a PRN item without one.
   */
  protected prnIntervalShown(it: OrderSetItem): boolean {
    return this.isMedicationLike(it.orderType) && it.asNeeded === true;
  }

  /** QA D1378: unticking "as needed" drops the item's interval. */
  protected onAsNeededChange(it: EditableItem, checked: boolean): void {
    it.asNeeded = checked;
    if (!checked) {
      it.prnMinIntervalHours = null;
    }
  }

  /**
   * QA D1378: picking the coded "As needed (PRN)" frequency preset ticks the
   * item as needed (a choice the author made, not a reading of free text);
   * it is never unticked by another pick.
   */
  protected onFrequencyChange(it: EditableItem, value: string): void {
    it.frequency = value;
    // QA D1419: a value chosen from the list is a schedule: it never asks, and the old answer goes.
    delete it.frequencyConfirmed;
    this.itemsAskedToConfirm.update((asked) => asked.filter((item) => item !== it));
    if (value === PRN_PRESET) {
      it.asNeeded = true;
    }
  }

  /**
   * QA D1419: an item whose wording the MAR cannot schedule (a text stored or
   * sent by another path; this list only offers schedules) shows the wording and
   * its confirm box: on the server's refusal, or when already confirmed.
   */
  protected readonly itemsAskedToConfirm = signal<EditableItem[]>([]);

  protected frequencyConfirmShown(it: EditableItem): boolean {
    return (
      this.isMedicationLike(it.orderType) &&
      it.asNeeded !== true &&
      (it.frequencyConfirmed === true || this.itemsAskedToConfirm().includes(it))
    );
  }

  /** A stored wording that is not one of the list's: shown as it is, so the author knows what is asked about. */
  protected frequencyOffTheList(it: OrderSetItem): boolean {
    return !!it.frequency && !this.frequencyPresets.some((f) => f.value === it.frequency);
  }

  /** `orderItems[2].frequencyConfirmed` names the item the server asks about (its field is marked once it shows). */
  private askToConfirm(err: unknown): void {
    const asked: EditableItem[] = [];
    for (const key of serverFieldErrors(err).keys()) {
      const m = /^orderItems\[(\d+)\]\.frequencyConfirmed$/.exec(key);
      const item = m ? this.items()[Number(m[1])] : undefined;
      if (item) asked.push(item);
    }
    if (asked.length > 0) this.itemsAskedToConfirm.update((all) => [...all, ...asked]);
  }

  protected itemMissingFrequency(index: number): boolean {
    return this.itemsMissingFrequency().includes(index);
  }

  protected itemMissingPrnInterval(index: number): boolean {
    return this.itemsMissingPrnInterval().includes(index);
  }

  private static validPrnInterval(value: unknown): boolean {
    const hours = Number(value);
    return Number.isInteger(hours) && hours >= 1 && hours <= PRN_MAX_INTERVAL_HOURS;
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
    const noInterval = itemsOut
      .map((it, i) =>
        this.prnIntervalShown(it) && !OrderSetEditComponent.validPrnInterval(it.prnMinIntervalHours)
          ? i
          : -1,
      )
      .filter((i) => i !== -1);
    const noFrequency = itemsOut
      .map((it, i) => (this.isMedicationLike(it.orderType) && !it.frequency?.trim() ? i : -1))
      .filter((i) => i !== -1);
    this.nameMissing.set(nameMissing);
    this.itemsMissingName.set(unnamed);
    this.itemsMissingPrnInterval.set(noInterval);
    this.itemsMissingFrequency.set(noFrequency);
    // QA D1256 class (round 27): every gap on the first press — the name, an
    // empty list, each unnamed item — instead of one per press.
    const gaps = joinReasons([
      nameMissing && this.translate.instant('ORDER_SETS.NAME_REQUIRED'),
      itemsOut.length === 0 && this.translate.instant('ORDER_SETS.ITEM_REQUIRED'),
      ...unnamed.map((i) =>
        this.translate.instant('ORDER_SETS.ITEM_NAME_MISSING', { position: i + 1 }),
      ),
      noInterval.length > 0 && this.translate.instant('PRESCRIPTIONS.PRN_MIN_INTERVAL_REQUIRED'),
      ...noFrequency.map((i) =>
        this.translate.instant('ORDER_SETS.ITEM_FREQUENCY_MISSING', { position: i + 1 }),
      ),
    ]);
    if (gaps) {
      // QA D1306 (round 28): focus the first marked field, not only a toast.
      const refused = [
        ...(nameMissing ? ['order-set-name'] : []),
        ...(itemsOut.length === 0 ? ['order-set-item-add'] : []),
        ...unnamed.map((i) => `order-set-item-name-${i}`),
        ...noInterval.map((i) => `order-set-item-prn-${i}`),
        ...noFrequency.map((i) => `order-set-item-frequency-${i}`),
      ];
      focusFirstInvalid(this.host.nativeElement, refused);
      this.toast.error(gaps);
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
      error: (err: unknown) => {
        this.saving.set(false);
        this.askToConfirm(err);
        this.toast.error(refusalReason(err) ?? this.translate.instant('ORDER_SETS.SAVE_FAILED'));
        // A set reopened untouched has no list yet, and the refusal may ask for a choice from it.
        this.items().forEach((_, i) => this.loadCatalogChoices(i));
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
    // QA D1223: a pick names one drug; a new name drops it.
    delete it.medicationCatalogItemId;
  }

  /** QA D1396: reads which catalogue items the name answers to, when the author leaves the field. */
  protected loadCatalogChoices(index: number): void {
    const item = this.items()[index];
    const name = item?.medicationName?.trim();
    if (!item || !this.isMedicationLike(item.orderType) || !name || name.length < 2) {
      this.catalogChoices.update((all) => ({ ...all, [index]: [] }));
      return;
    }
    // Every item the name answers to: the exact one may be past any first page.
    allRows<MedicationCatalogItemResponse>((page, size) =>
      this.pharmacy.searchMedications(name, page, size),
    ).subscribe({
      next: (rows) => this.catalogChoices.update((all) => ({ ...all, [index]: rows })),
      // The server still decides at save; the list only offers the choice.
      error: () => this.catalogChoices.update((all) => ({ ...all, [index]: [] })),
    });
  }

  protected catalogChoiceLabel(item: MedicationCatalogItemResponse): string {
    return [item.nameFr, item.strength, item.strengthUnit, item.form].filter((p) => !!p).join(' ');
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
    // QA D1396: a quantity left blank is not sent (0 and the like are the server's to refuse).
    const blankQuantity = out.quantity === undefined || out.quantity === null;
    if (blankQuantity || (out.quantity as unknown) === '') {
      delete out.quantity;
    }
    if (!out.quantityUnit?.trim()) {
      delete out.quantityUnit;
    }
    if (!out.medicationCatalogItemId) {
      delete out.medicationCatalogItemId;
    }
    // QA D1419: the answer belongs to an item whose wording was asked about.
    if (!out.frequencyConfirmed || out.asNeeded === true) {
      delete out.frequencyConfirmed;
    }
    // QA round 30 / QA D1378: the tick and the interval belong to an as-needed item only.
    if (this.prnIntervalShown(out)) {
      out.asNeeded = true;
      out.prnMinIntervalHours = Number(out.prnMinIntervalHours);
    } else {
      delete out.asNeeded;
      delete out.prnMinIntervalHours;
    }
    return out;
  }
}
