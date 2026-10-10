import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import {
  PROVIDER_FACILITY_TYPES,
  PROVIDER_VERIFICATION_STATUSES,
  ProviderFacilityType,
  ProviderPage,
  ProviderVerificationStatus,
} from '../../../services/provider.model';
import { SuperAdminProviderService } from '../../../services/super-admin-provider.service';
import { EnumLabelPipe } from '../../../shared/pipes/enum-label.pipe';
import { verificationBadgeClass } from '../provider-status';

const PAGE_SIZE = 20;

/**
 * The super-admin's provider facilities (provider plan P1-T12, AC-1 to AC-3):
 * every private pharmacy and laboratory with its current verification,
 * filtered by type and verification status.
 */
@Component({
  selector: 'app-provider-list',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, TranslateModule, EnumLabelPipe],
  templateUrl: './provider-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './provider-list.scss',
})
export class ProviderListComponent implements OnInit {
  private readonly service = inject(SuperAdminProviderService);

  readonly facilityTypes = PROVIDER_FACILITY_TYPES;
  readonly statuses = PROVIDER_VERIFICATION_STATUSES;
  readonly badgeClass = verificationBadgeClass;

  readonly typeFilter = signal<ProviderFacilityType | ''>('');
  readonly statusFilter = signal<ProviderVerificationStatus | ''>('');
  readonly pageIndex = signal(0);

  readonly loading = signal(true);
  readonly errored = signal(false);
  readonly page = signal<ProviderPage | null>(null);

  readonly providers = computed(() => this.page()?.content ?? []);
  readonly totalPages = computed(() => this.page()?.totalPages ?? 0);
  readonly hasPrevious = computed(() => this.pageIndex() > 0);
  readonly hasNext = computed(() => this.pageIndex() + 1 < this.totalPages());

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.errored.set(false);
    this.service
      .list({ type: this.typeFilter(), status: this.statusFilter() }, this.pageIndex(), PAGE_SIZE)
      .subscribe({
        next: (page) => {
          this.page.set(page);
          this.loading.set(false);
        },
        error: () => {
          this.page.set(null);
          this.errored.set(true);
          this.loading.set(false);
        },
      });
  }

  setType(type: ProviderFacilityType | ''): void {
    this.typeFilter.set(type);
    this.pageIndex.set(0);
    this.refresh();
  }

  setStatus(status: ProviderVerificationStatus | ''): void {
    this.statusFilter.set(status);
    this.pageIndex.set(0);
    this.refresh();
  }

  previousPage(): void {
    if (!this.hasPrevious()) return;
    this.pageIndex.update((i) => i - 1);
    this.refresh();
  }

  nextPage(): void {
    if (!this.hasNext()) return;
    this.pageIndex.update((i) => i + 1);
    this.refresh();
  }
}
