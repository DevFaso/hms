import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

import { ProviderAuditPage, isNotAvailable } from '../../services/provider.model';
import { ProviderPortalService } from '../../services/provider-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { RoleLabelPipe } from '../../shared/pipes/role-label.pipe';

const PAGE_SIZE = 20;

/**
 * The facility's own audit trail (provider plan §3.1), for its
 * PROVIDER_ADMIN: what its staff did here, newest first. The server returns
 * ids and codes only and never a row about a patient.
 */
@Component({
  selector: 'app-provider-audit',
  standalone: true,
  imports: [DatePipe, TranslateModule, EnumLabelPipe, RoleLabelPipe],
  templateUrl: './provider-audit.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: '../provider-pages.scss',
})
export class ProviderAuditComponent implements OnInit {
  private readonly portal = inject(ProviderPortalService);

  readonly page = signal<ProviderAuditPage | null>(null);
  readonly pageIndex = signal(0);
  readonly loading = signal(true);
  readonly notAvailable = signal(false);
  readonly loadFailed = signal(false);

  readonly entries = computed(() => this.page()?.entries ?? []);
  readonly hasPrevious = computed(() => this.pageIndex() > 0);
  readonly hasNext = computed(() => this.page()?.hasMore ?? false);

  ngOnInit(): void {
    this.load();
  }

  /** What kind of record a row acted on, in words: the entity type is a code, never shown raw. */
  recordKey(entityType: string | null): string {
    switch (entityType) {
      case 'PROVIDER_FACILITY':
        return 'PROVIDER.AUDIT.RECORD_FACILITY';
      case 'PROVIDER_STAFF':
        return 'PROVIDER.AUDIT.RECORD_STAFF';
      case 'USER':
        return 'PROVIDER.AUDIT.RECORD_ACCOUNT';
      case 'USER_ROLE_HOSPITAL_ASSIGNMENT':
      case 'ASSIGNMENT':
        return 'PROVIDER.AUDIT.RECORD_ASSIGNMENT';
      default:
        return 'PROVIDER.AUDIT.RECORD_OTHER';
    }
  }

  load(): void {
    this.loading.set(true);
    this.notAvailable.set(false);
    this.loadFailed.set(false);
    this.portal.audit(this.pageIndex(), PAGE_SIZE).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (isNotAvailable(err)) {
          this.notAvailable.set(true);
        } else {
          this.loadFailed.set(true);
        }
        this.loading.set(false);
      },
    });
  }

  previousPage(): void {
    if (!this.hasPrevious()) return;
    this.pageIndex.update((i) => i - 1);
    this.load();
  }

  nextPage(): void {
    if (!this.hasNext()) return;
    this.pageIndex.update((i) => i + 1);
    this.load();
  }
}
