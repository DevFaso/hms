import {
  Component,
  inject,
  OnDestroy,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { Subscription } from 'rxjs';

import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  OrganizationLifecycleState,
  OrganizationService,
  OrganizationResponse,
  OrganizationCreateRequest,
} from '../services/organization.service';
import { stateColor as lifecycleStateColor } from './organization-detail';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { EnumLabelService } from '../core/enum-label.service';

@Component({
  selector: 'app-organization-list',
  standalone: true,
  imports: [FormsModule, RouterLink, TranslateModule, EnumLabelPipe],
  templateUrl: './organization-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './organization-list.scss',
})
export class OrganizationListComponent implements OnInit, OnDestroy {
  private readonly enumLabel = inject(EnumLabelService);
  private readonly orgService = inject(OrganizationService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

  /**
   * Only super admins can open /organizations/:id (gated by RoleGuard).
   * Render the row name as a clickable link only for them; ROLE_ADMIN sees
   * a non-clickable row instead of being routed to /error/403 on click.
   */
  readonly isSuperAdmin = this.roleContext.isSuperAdmin;

  organizations = signal<OrganizationResponse[]>([]);
  filtered = signal<OrganizationResponse[]>([]);
  loading = signal(true);
  searchTerm = '';

  showCreate = signal(false);
  saving = signal(false);
  editing = signal<OrganizationResponse | null>(null);
  createForm: OrganizationCreateRequest = { name: '', code: '', timezone: '', contactEmail: '' };

  // Delete
  showDeleteConfirm = signal(false);
  deletingOrg = signal<OrganizationResponse | null>(null);
  deleting = signal(false);

  /** Valid organization type enum values loaded from backend */
  orgTypes = signal<string[]>([]);

  /** Common IANA timezones for the dropdown */
  readonly timezones: string[] = [
    'Africa/Ouagadougou',
    'Africa/Abidjan',
    'Africa/Accra',
    'Africa/Bamako',
    'Africa/Dakar',
    'Africa/Lagos',
    'Africa/Nairobi',
    'Africa/Johannesburg',
    'Africa/Cairo',
    'Africa/Casablanca',
    'America/New_York',
    'America/Chicago',
    'America/Denver',
    'America/Los_Angeles',
    'America/Toronto',
    'America/Sao_Paulo',
    'America/Mexico_City',
    'America/Bogota',
    'America/Lima',
    'America/Buenos_Aires',
    'Asia/Dubai',
    'Asia/Kolkata',
    'Asia/Shanghai',
    'Asia/Tokyo',
    'Asia/Singapore',
    'Asia/Seoul',
    'Asia/Bangkok',
    'Asia/Riyadh',
    'Australia/Sydney',
    'Australia/Melbourne',
    'Europe/London',
    'Europe/Paris',
    'Europe/Berlin',
    'Europe/Madrid',
    'Europe/Rome',
    'Europe/Amsterdam',
    'Europe/Brussels',
    'Europe/Moscow',
    'Pacific/Auckland',
    'Pacific/Honolulu',
    'UTC',
  ];

  private langSub?: Subscription;

  currentPage = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);

  ngOnInit(): void {
    this.loadOrganizations();
    this.orgService.getTypes().subscribe({
      next: (types) => this.orgTypes.set(types),
    });
    // The filter matches the Type column's TRANSLATED label, so the rows that
    // match change with the language. Without this, a row matched under French
    // labels stays listed once the cell reads English, and one that would now
    // match stays hidden until the next keystroke.
    this.langSub = this.translate.onLangChange.subscribe(() => this.applyFilter());
  }

  ngOnDestroy(): void {
    this.langSub?.unsubscribe();
  }

  loadOrganizations(page = 0): void {
    this.loading.set(true);
    this.orgService.list(page, 20).subscribe({
      next: (res) => {
        this.organizations.set(res.content);
        this.currentPage.set(res.number);
        this.totalPages.set(res.totalPages);
        this.totalElements.set(res.totalElements);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('ORGANIZATIONS.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.organizations());
      return;
    }
    this.filtered.set(
      this.organizations().filter(
        (o) =>
          o.name.toLowerCase().includes(term) ||
          o.code.toLowerCase().includes(term) ||
          (o.type?.toLowerCase().includes(term) ?? false) ||
          // The Type column renders a translated label; searching has to
          // match what is on screen, not only the wire token behind it.
          this.typeLabel(o.type).toLowerCase().includes(term),
      ),
    );
  }

  openCreate(): void {
    this.createForm = { name: '', code: '', timezone: '', contactEmail: '' };
    this.editing.set(null);
    this.showCreate.set(true);
  }

  openEdit(org: OrganizationResponse): void {
    this.editing.set(org);
    this.createForm = {
      name: org.name,
      code: org.code,
      timezone: org.defaultTimezone ?? '',
      contactEmail: org.primaryContactEmail ?? '',
      contactPhone: org.primaryContactPhone ?? '',
      notes: org.onboardingNotes ?? '',
      type: org.type ?? '',
    };
    this.showCreate.set(true);
  }

  closeCreate(): void {
    this.showCreate.set(false);
    this.editing.set(null);
  }

  submitCreate(): void {
    if (
      !this.createForm.name ||
      !this.createForm.code ||
      !this.createForm.contactEmail ||
      !this.createForm.timezone
    ) {
      this.toast.error(this.translate.instant('ORGANIZATIONS.REQUIRED_FIELDS'));
      return;
    }
    this.saving.set(true);
    const existing = this.editing();
    const op = existing
      ? this.orgService.update(existing.id, this.createForm)
      : this.orgService.create(this.createForm);

    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(existing ? 'ORGANIZATIONS.UPDATED' : 'ORGANIZATIONS.CREATED'),
        );
        this.showCreate.set(false);
        this.saving.set(false);
        this.editing.set(null);
        this.loadOrganizations();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ORGANIZATIONS.OPERATION_FAILED'),
        );
        this.saving.set(false);
      },
    });
  }

  confirmDelete(org: OrganizationResponse): void {
    this.deletingOrg.set(org);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingOrg.set(null);
  }

  executeDelete(): void {
    const org = this.deletingOrg();
    if (!org) return;
    this.deleting.set(true);
    this.orgService.delete(org.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ORGANIZATIONS.DELETED'));
        this.showDeleteConfirm.set(false);
        this.deleting.set(false);
        this.deletingOrg.set(null);
        this.loadOrganizations();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ORGANIZATIONS.DELETE_FAILED'),
        );
        this.deleting.set(false);
      },
    });
  }

  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages()) {
      this.loadOrganizations(page);
    }
  }

  /** The label the Type column shows, so the filter can match it. */
  private typeLabel(value: string | undefined): string {
    return value ? this.enumLabel.transform(value, 'organizationType') : '';
  }

  lifecycleColor(state: OrganizationLifecycleState | undefined): string {
    return lifecycleStateColor(state);
  }
}
