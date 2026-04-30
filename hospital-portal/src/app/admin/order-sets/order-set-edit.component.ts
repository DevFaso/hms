import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import {
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
 * Admin form for authoring or editing a CPOE order-set template. The
 * orderItems JSONB is edited as raw JSON in v0; a structured per-item
 * editor is the next pairing with gap #19 (order catalog).
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
  template: `
    <section class="order-set-edit" data-testid="order-set-edit">
      <header class="order-set-edit__header">
        <a class="back-link" routerLink="/admin/order-sets">←</a>
        <h1>
          {{
            mode() === 'edit'
              ? ('ORDER_SETS.EDIT_TITLE' | translate)
              : ('ORDER_SETS.NEW' | translate)
          }}
        </h1>
      </header>

      <p *ngIf="loadError()" class="order-set-edit__error" data-testid="order-set-edit-error">
        {{ 'ORDER_SETS.ERROR' | translate }}
      </p>

      <form
        class="order-set-edit__form"
        (ngSubmit)="save()"
        *ngIf="!loadError()"
        data-testid="order-set-edit-form"
      >
        <label>
          {{ 'ORDER_SETS.FIELD_NAME' | translate }}
          <input type="text" [(ngModel)]="form.name" name="name" required />
        </label>

        <label>
          {{ 'ORDER_SETS.FIELD_DESCRIPTION' | translate }}
          <textarea [(ngModel)]="form.description" name="description" rows="2"></textarea>
        </label>

        <label>
          {{ 'ORDER_SETS.FIELD_ADMISSION_TYPE' | translate }}
          <select [(ngModel)]="form.admissionType" name="admissionType" required>
            <option *ngFor="let t of admissionTypes" [value]="t">{{ t }}</option>
          </select>
        </label>

        <label>
          {{ 'ORDER_SETS.FIELD_CLINICAL_GUIDELINES' | translate }}
          <input type="text" [(ngModel)]="form.clinicalGuidelines" name="clinicalGuidelines" />
        </label>

        <label>
          {{ 'ORDER_SETS.FIELD_ORDER_ITEMS_JSON' | translate }}
          <textarea
            [(ngModel)]="orderItemsJson"
            name="orderItemsJson"
            rows="14"
            data-testid="order-set-edit-items-json"
          ></textarea>
        </label>

        <p
          *ngIf="jsonError()"
          class="order-set-edit__error"
          data-testid="order-set-edit-json-error"
        >
          {{ jsonError() }}
        </p>

        <button
          type="submit"
          class="btn-primary"
          [disabled]="saving()"
          data-testid="order-set-edit-save"
        >
          {{ 'COMMON.SAVE' | translate }}
        </button>
      </form>

      <aside *ngIf="versionHistory().length > 0" class="order-set-edit__history">
        <h2>{{ 'ORDER_SETS.VERSION_HISTORY' | translate }}</h2>
        <ol>
          <li *ngFor="let v of versionHistory()">
            v{{ v.version }} — {{ v.updatedAt | date: 'short' }}
            <span *ngIf="!v.active" class="badge">retired</span>
          </li>
        </ol>
      </aside>
    </section>
  `,
  styles: [
    `
      .order-set-edit {
        padding: 1.5rem;
        display: grid;
        grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
        gap: 2rem;
      }
      .order-set-edit__header {
        grid-column: 1 / -1;
        display: flex;
        align-items: center;
        gap: 0.75rem;
      }
      .order-set-edit__form {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
      }
      .order-set-edit__form label {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        font-weight: 500;
      }
      .order-set-edit__form input,
      .order-set-edit__form textarea,
      .order-set-edit__form select {
        font-weight: normal;
        padding: 0.45rem;
      }
      .order-set-edit__error {
        color: var(--danger, #b00020);
      }
    `,
  ],
})
export class OrderSetEditComponent implements OnInit {
  protected readonly admissionTypes = ADMISSION_TYPES;
  protected readonly mode = signal<Mode>('new');
  protected readonly saving = signal(false);
  protected readonly jsonError = signal<string | null>(null);
  protected readonly loadError = signal(false);
  protected readonly versionHistory = signal<OrderSetSummary[]>([]);

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
  protected orderItemsJson = '[]';
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
        this.orderItemsJson = JSON.stringify(os.orderItems ?? [], null, 2);
      },
      error: () => this.loadError.set(true),
    });
    this.orderSetService.versions(id).subscribe({
      next: (rows) => this.versionHistory.set(rows),
      error: () => this.versionHistory.set([]),
    });
  }

  protected save(): void {
    let parsed: OrderSetItem[];
    try {
      parsed = JSON.parse(this.orderItemsJson) as OrderSetItem[];
      if (!Array.isArray(parsed)) throw new Error('orderItems must be a JSON array');
    } catch (err) {
      this.jsonError.set(err instanceof Error ? err.message : 'Invalid JSON');
      return;
    }
    this.jsonError.set(null);

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
      orderItems: parsed,
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
}
