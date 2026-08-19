import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  CredentialHealth,
  SuperAdminGovernanceService,
} from '../services/super-admin-governance.service';

/**
 * Credential health overview (SUPER_ADMIN). Read-only in v1 — the MFA and
 * recovery-contact upsert PUTs (full-collection replace) are deferred.
 */
@Component({
  selector: 'app-gov-credentials-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './credentials-tab.html',
  styleUrl: './admin-governance.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CredentialsTabComponent implements OnInit {
  private readonly service = inject(SuperAdminGovernanceService);

  rows = signal<CredentialHealth[]>([]);
  loading = signal(false);
  loadError = signal(false);
  search = signal('');
  onlyGaps = signal(false);
  selected = signal<CredentialHealth | null>(null);

  filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    let list = this.rows();
    if (term) {
      list = list.filter(
        (r) =>
          (r.username ?? '').toLowerCase().includes(term) ||
          (r.email ?? '').toLowerCase().includes(term),
      );
    }
    if (this.onlyGaps()) {
      list = list.filter((r) => !r.hasPrimaryMfa || !r.hasPrimaryRecoveryContact);
    }
    return list;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.service.credentialHealth().subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }

  openDetail(row: CredentialHealth): void {
    this.selected.set(row);
  }

  closeDetail(): void {
    this.selected.set(null);
  }
}
