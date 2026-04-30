import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, Subscription, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';

import { OrderSetService, OrderSetSummary } from '../../services/order-set.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { AuthService } from '../../auth/auth.service';

/**
 * Admin list view for CPOE order-set templates. Hospital-scoped via
 * RoleContextService; supports name-search typeahead, edit / deactivate
 * row actions, and links to {@code /admin/order-sets/new} for authoring.
 */
@Component({
  selector: 'app-order-set-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="order-set-admin" data-testid="order-set-admin">
      <header class="order-set-admin__header">
        <h1>{{ 'ORDER_SETS.ADMIN_TITLE' | translate }}</h1>
        <a class="btn-primary" routerLink="/admin/order-sets/new" data-testid="order-set-new">
          {{ 'ORDER_SETS.NEW' | translate }}
        </a>
      </header>

      <input
        type="search"
        class="order-set-admin__search"
        data-testid="order-set-admin-search"
        [placeholder]="'ORDER_SETS.SEARCH_PLACEHOLDER' | translate"
        [ngModel]="searchTerm()"
        (ngModelChange)="onSearch($event)"
      />

      <p *ngIf="loading()" data-testid="order-set-admin-loading">
        {{ 'ORDER_SETS.SEARCHING' | translate }}
      </p>

      <p *ngIf="error()" class="order-set-admin__error" data-testid="order-set-admin-error">
        {{ 'ORDER_SETS.ERROR' | translate }}
      </p>

      <table
        class="data-table"
        *ngIf="!loading() && !error() && rows().length > 0"
        data-testid="order-set-admin-table"
      >
        <thead>
          <tr>
            <th>{{ 'ORDER_SETS.COL_NAME' | translate }}</th>
            <th>{{ 'ORDER_SETS.COL_VERSION' | translate }}</th>
            <th>{{ 'ORDER_SETS.COL_ITEM_COUNT' | translate }}</th>
            <th>{{ 'ORDER_SETS.COL_TYPE' | translate }}</th>
            <th>{{ 'ORDER_SETS.COL_LAST_MODIFIED' | translate }}</th>
            <th>{{ 'COMMON.ACTIONS' | translate }}</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let os of rows(); trackBy: trackById" [attr.data-os-id]="os.id">
            <td>{{ os.name }}</td>
            <td>v{{ os.version }}</td>
            <td>{{ os.orderCount }}</td>
            <td>{{ os.admissionType }}</td>
            <td>{{ os.updatedAt | date: 'short' }}</td>
            <td class="actions">
              <a
                class="action-link"
                [routerLink]="['/admin/order-sets', os.id]"
                data-testid="order-set-admin-edit"
              >
                {{ 'COMMON.EDIT' | translate }}
              </a>
              <button
                type="button"
                class="action-link delete-link"
                (click)="deactivate(os)"
                data-testid="order-set-admin-deactivate"
              >
                {{ 'ORDER_SETS.DEACTIVATE' | translate }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <p
        *ngIf="!loading() && !error() && rows().length === 0"
        class="order-set-admin__empty"
        data-testid="order-set-admin-empty"
      >
        {{ 'ORDER_SETS.NO_RESULTS' | translate }}
      </p>
    </section>
  `,
  styles: [
    `
      .order-set-admin {
        padding: 1.5rem;
      }
      .order-set-admin__header {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
      .order-set-admin__search {
        margin: 0.75rem 0;
        padding: 0.5rem;
        width: 100%;
        max-width: 480px;
      }
      .order-set-admin__error {
        color: var(--danger, #b00020);
      }
      .actions {
        display: flex;
        gap: 0.5rem;
      }
    `,
  ],
})
export class OrderSetListComponent implements OnInit {
  protected readonly rows = signal<OrderSetSummary[]>([]);
  protected readonly searchTerm = signal('');
  protected readonly loading = signal(false);
  protected readonly error = signal(false);

  private readonly orderSetService = inject(OrderSetService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly searchSubject = new Subject<string>();
  private searchSub?: Subscription;

  ngOnInit(): void {
    this.searchSub = this.searchSubject
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((term) => {
          this.loading.set(true);
          this.error.set(false);
          const hid = this.roleContext.activeHospitalId ?? '';
          return this.orderSetService.list(hid, term);
        }),
      )
      .subscribe({
        next: (page) => {
          this.rows.set(page.content ?? []);
          this.loading.set(false);
        },
        error: () => {
          this.rows.set([]);
          this.loading.set(false);
          this.error.set(true);
        },
      });
    this.searchSubject.next('');
  }

  protected onSearch(value: string): void {
    this.searchTerm.set(value);
    this.searchSubject.next(value);
  }

  protected trackById(_index: number, os: OrderSetSummary): string {
    return os.id;
  }

  protected deactivate(os: OrderSetSummary): void {
    const reason = globalThis.prompt('Deactivation reason:');
    if (!reason?.trim()) return;
    const actor = this.auth.getUserProfile()?.staffId ?? '';
    if (!actor) {
      this.toast.error('No active staff context');
      return;
    }
    this.orderSetService.deactivate(os.id, reason.trim(), actor).subscribe({
      next: () => {
        this.toast.success('Order set deactivated');
        // Refresh list.
        this.searchSubject.next(this.searchTerm());
      },
      error: () => this.toast.error('Could not deactivate order set'),
    });
  }
}
