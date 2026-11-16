import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  PRICE_LIST_CATEGORIES,
  PRICE_LIST_LINK_TYPES,
  PriceListCategory,
  PriceListItem,
  PriceListLinkOptions,
  PriceListLinkType,
  PriceListService,
} from '../../services/price-list.service';
import { RoleContextService } from '../../core/role-context.service';
import { actionRoles } from '../../core/action-permissions';
import { ToastService } from '../../core/toast.service';
import { ReasonDialogComponent } from '../../shared/reason-dialog/reason-dialog.component';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DialogA11yDirective } from '../../shared/a11y/dialog-a11y.directive';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { localDateString } from '../../shared/date-utils';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';

/**
 * Today as YYYY-MM-DD in the reader's calendar: the hospital's business day
 * (Africa/Ouagadougou, UTC+0) at the desk. The server refuses a past date by
 * its own business day, whatever the browser says.
 */
export function businessToday(now: Date = new Date()): string {
  return localDateString(now);
}

/** The item dialog's control for each request field the server may name under `fieldErrors`. */
const ITEM_FIELD_IDS: Readonly<Record<string, string>> = {
  code: 'pl-code',
  label: 'pl-label',
  category: 'pl-category',
  linkType: 'pl-link-type',
  linkKey: 'pl-link-key',
  unitPrice: 'pl-unit-price',
  effectiveFrom: 'pl-effective-from',
  changeReason: 'pl-change-reason',
};

/** The new-price dialog's controls (`replaceScheduled` is asked for, not marked). */
const PRICE_FIELD_IDS: Readonly<Record<string, string>> = {
  unitPrice: 'pp-unit-price',
  effectiveFrom: 'pp-effective-from',
  changeReason: 'pp-change-reason',
};

interface ItemForm {
  code: string;
  label: string;
  category: PriceListCategory | null;
  linkType: PriceListLinkType | null;
  /** The key for every link type but IMAGING_STUDY, where it is the study type. */
  linkKey: string;
  modality: string;
  unitPrice: number | null;
  effectiveFrom: string;
  /** False until the user picks a date: an untouched date is sent as null, so the server's business day applies. */
  effectiveFromChanged: boolean;
  changeReason: string;
}

interface PriceForm {
  unitPrice: number | null;
  effectiveFrom: string;
  /** False until the user picks a date: an untouched date is sent as null, so the server's business day applies. */
  effectiveFromChanged: boolean;
  changeReason: string;
  replaceScheduled: boolean;
}

/**
 * Billing-money plan PR 2 (scorecard M1): the hospital's price list
 * ("Grille tarifaire"). Everyone who bills or quotes reads it; only the
 * hospital admin and the accountant change it (owner decision D-1), so the
 * write buttons follow the API's own role lists (action-permissions.ts).
 * A price never changes in place: "Nouveau prix" adds a dated version, and a
 * version already applied is never replaced. Labels are the hospital's own
 * words and are shown as typed.
 */
@Component({
  selector: 'app-price-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    CfaPipe,
    EnumLabelPipe,
    ServerTimePipe,
    DialogA11yDirective,
    SubmitGuardDirective,
    ReasonDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './price-list.component.html',
  styleUrl: './price-list.component.scss',
})
export class PriceListComponent implements OnInit {
  private readonly api = inject(PriceListService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly categories = PRICE_LIST_CATEGORIES;
  readonly linkTypes = PRICE_LIST_LINK_TYPES;

  readonly canCreate = this.roleContext.hasAnyActiveRole(actionRoles('priceListCreate'));
  readonly canUpdate = this.roleContext.hasAnyActiveRole(actionRoles('priceListUpdate'));
  readonly canAddPrice = this.roleContext.hasAnyActiveRole(actionRoles('priceListAddPrice'));
  readonly canDeactivate = this.roleContext.hasAnyActiveRole(actionRoles('priceListDeactivate'));

  readonly items = signal<PriceListItem[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly category = signal<PriceListCategory | null>(null);
  readonly includeInactive = signal(false);
  readonly search = signal('');
  readonly visibleItems = computed(() => {
    const q = this.search().trim().toLowerCase();
    if (!q) return this.items();
    return this.items().filter(
      (i) => i.code.toLowerCase().includes(q) || i.label.toLowerCase().includes(q),
    );
  });

  readonly selected = signal<PriceListItem | null>(null);
  readonly linkOptions = signal<PriceListLinkOptions | null>(null);

  /** The item dialog: null when closed. */
  readonly itemDialog = signal<'create' | 'edit' | null>(null);
  readonly itemSaving = signal(false);
  itemForm: ItemForm = PriceListComponent.blankItem();

  /** The new-price dialog: null when closed. */
  readonly priceDialog = signal(false);
  readonly priceSaving = signal(false);
  /** The server's question when a planned price would be replaced. */
  readonly replaceQuestion = signal<string | null>(null);
  priceForm: PriceForm = PriceListComponent.blankPrice();

  /** Read on each change detection, never cached: a page left open past midnight moves to the new day. */
  get today(): string {
    return businessToday();
  }

  ngOnInit(): void {
    this.load();
  }

  private static blankItem(): ItemForm {
    return {
      code: '',
      label: '',
      category: null,
      linkType: null,
      linkKey: '',
      modality: '',
      unitPrice: null,
      effectiveFrom: businessToday(),
      effectiveFromChanged: false,
      changeReason: '',
    };
  }

  private static blankPrice(): PriceForm {
    return {
      unitPrice: null,
      effectiveFrom: businessToday(),
      effectiveFromChanged: false,
      changeReason: '',
      replaceScheduled: false,
    };
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.api
      .list(this.category(), this.includeInactive())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.items.set(rows ?? []);
          this.loading.set(false);
        },
        error: () => {
          this.items.set([]);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }

  pickCategory(value: PriceListCategory | null): void {
    this.category.set(value || null);
    this.load();
  }

  toggleInactive(value: boolean): void {
    this.includeInactive.set(value);
    this.load();
  }

  open(item: PriceListItem): void {
    this.api
      .get(item.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (detail) => this.selected.set(detail),
        error: () => this.toast.error(this.translate.instant('BILLING.PRICE_LIST.LOAD_FAILED')),
      });
  }

  closeDetail(): void {
    this.selected.set(null);
  }

  // ── item dialog ──────────────────────────────────────────────────────

  openCreate(): void {
    this.itemForm = PriceListComponent.blankItem();
    this.loadLinkOptions();
    this.itemDialog.set('create');
  }

  openEdit(item: PriceListItem): void {
    const imaging = item.linkType === 'IMAGING_STUDY' && item.linkKey ? item.linkKey : null;
    const colon = imaging ? imaging.indexOf(':') : -1;
    this.itemForm = {
      ...PriceListComponent.blankItem(),
      code: item.code,
      label: item.label,
      category: item.category,
      linkType: item.linkType ?? null,
      linkKey: imaging && colon > 0 ? imaging.substring(colon + 1) : (item.linkKey ?? ''),
      modality: imaging && colon > 0 ? imaging.substring(0, colon) : '',
    };
    this.loadLinkOptions();
    this.itemDialog.set('edit');
  }

  closeItemDialog(): void {
    this.itemDialog.set(null);
  }

  pickLinkType(value: PriceListLinkType | null): void {
    this.itemForm = { ...this.itemForm, linkType: value || null, linkKey: '', modality: '' };
  }

  private loadLinkOptions(): void {
    if (this.linkOptions()) return;
    this.api
      .linkOptions()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (options) => this.linkOptions.set(options),
        error: () => this.linkOptions.set(null),
      });
  }

  /** The link key the server expects: `MODALITY:study type` for an imaging study. */
  private linkKeyOf(form: ItemForm): string | null {
    if (!form.linkType) return null;
    const key = form.linkKey.trim();
    if (form.linkType === 'IMAGING_STUDY') {
      return form.modality && key ? `${form.modality}:${key}` : key || null;
    }
    return key || null;
  }

  saveItem(): void {
    const mode = this.itemDialog();
    if (!mode || this.itemSaving()) return;
    const form = this.itemForm;
    this.itemSaving.set(true);
    const request =
      mode === 'create'
        ? this.api.create({
            code: form.code.trim(),
            label: form.label.trim(),
            category: form.category,
            linkType: form.linkType,
            linkKey: this.linkKeyOf(form),
            unitPrice: form.unitPrice,
            effectiveFrom: (form.effectiveFromChanged && form.effectiveFrom) || null,
            changeReason: form.changeReason.trim() || null,
          })
        : this.api.update(this.selected()?.id ?? '', {
            label: form.label.trim(),
            category: form.category,
            linkType: form.linkType,
            linkKey: this.linkKeyOf(form),
          });
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        this.itemSaving.set(false);
        this.itemDialog.set(null);
        this.selected.set(saved);
        this.toast.success(this.translate.instant('BILLING.PRICE_LIST.SAVED'));
        this.load();
      },
      error: (err) => {
        this.itemSaving.set(false);
        this.markRefused(err, ITEM_FIELD_IDS);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('BILLING.PRICE_LIST.SAVE_FAILED'),
        );
      },
    });
  }

  // ── new price dialog ─────────────────────────────────────────────────

  openPrice(): void {
    this.priceForm = PriceListComponent.blankPrice();
    this.replaceQuestion.set(null);
    this.priceDialog.set(true);
  }

  /** The item dialog's date was picked by the user: it is sent as chosen. */
  itemDateChanged(value: string): void {
    this.itemForm.effectiveFrom = value;
    this.itemForm.effectiveFromChanged = true;
  }

  /**
   * The new price's date was picked: it is sent as chosen, and a replacement question asked for another date
   * no longer applies (the confirmation is reset and asked again if the new date needs it).
   */
  priceDateChanged(value: string): void {
    this.priceForm.effectiveFrom = value;
    this.priceForm.effectiveFromChanged = true;
    this.priceForm.replaceScheduled = false;
    this.replaceQuestion.set(null);
  }

  closePriceDialog(): void {
    this.priceDialog.set(false);
    this.replaceQuestion.set(null);
  }

  savePrice(): void {
    const item = this.selected();
    if (!item || this.priceSaving()) return;
    const form = this.priceForm;
    this.priceSaving.set(true);
    this.api
      .addPrice(item.id, {
        unitPrice: form.unitPrice,
        effectiveFrom: (form.effectiveFromChanged && form.effectiveFrom) || null,
        changeReason: form.changeReason.trim(),
        replaceScheduled: form.replaceScheduled,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (saved) => {
          this.priceSaving.set(false);
          this.priceDialog.set(false);
          this.replaceQuestion.set(null);
          this.selected.set(saved);
          this.toast.success(this.translate.instant('BILLING.PRICE_LIST.PRICE_SAVED'));
          this.load();
        },
        error: (err) => {
          this.priceSaving.set(false);
          const ask = (err?.error?.fieldErrors as { replaceScheduled?: unknown } | undefined)
            ?.replaceScheduled;
          if (typeof ask === 'string' && ask.trim() !== '') {
            // A planned price exists: the reader confirms its replacement, then saves again.
            this.replaceQuestion.set(ask);
            return;
          }
          this.markRefused(err, PRICE_FIELD_IDS);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('BILLING.PRICE_LIST.SAVE_FAILED'),
          );
        },
      });
  }

  // ── deactivate ───────────────────────────────────────────────────────

  /** The item whose deactivation is being asked for (the reason dialog is open). */
  readonly deactivating = signal<PriceListItem | null>(null);
  readonly deactivateBusy = signal(false);

  /** "Désactiver" asks why first (the shared reason dialog); nothing is sent before. */
  askDeactivate(item: PriceListItem): void {
    this.deactivating.set(item);
  }

  /** The reader confirmed with a reason: the item stops pricing anything new, its history stays. */
  deactivate(item: PriceListItem, reason: string): void {
    if (this.deactivateBusy()) return;
    this.deactivateBusy.set(true);
    this.api
      .deactivate(item.id, reason)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (saved) => {
          this.deactivateBusy.set(false);
          this.deactivating.set(null);
          this.selected.set(saved);
          this.toast.success(this.translate.instant('BILLING.PRICE_LIST.DEACTIVATED'));
          this.load();
        },
        error: (err) => {
          this.deactivateBusy.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('BILLING.PRICE_LIST.SAVE_FAILED'),
          );
        },
      });
  }

  /** Marks each field the server named, with its own reason. */
  private markRefused(err: unknown, idByField: Readonly<Record<string, string>>): void {
    const refused = refusedFieldReasons(err, idByField);
    if (refused.length > 0) {
      refuseFields(
        this.host.nativeElement,
        refused.map(([id, message]) => ({ id, message })),
        Object.values(idByField),
      );
    }
  }
}
