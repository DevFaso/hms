import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import {
  OrganizationLifecycleState,
  OrganizationResponse,
  OrganizationService,
  TenantLifecycleResponse,
} from '../services/organization.service';
import { ToastService } from '../core/toast.service';

type LifecycleAction = 'suspend' | 'restore' | 'archive' | 'schedule-purge' | 'cancel-purge';

interface ActionConfig {
  action: LifecycleAction;
  titleKey: string;
  descKey: string;
  reasonRequired: boolean;
  destructive: boolean;
  needsTypedConfirm: boolean;
  iconKey: string;
}

const ACTION_CONFIG: Record<LifecycleAction, ActionConfig> = {
  suspend: {
    action: 'suspend',
    titleKey: 'TENANT_LIFECYCLE.MODAL.SUSPEND_TITLE',
    descKey: 'TENANT_LIFECYCLE.MODAL.SUSPEND_DESC',
    reasonRequired: true,
    destructive: true,
    needsTypedConfirm: true,
    iconKey: 'block',
  },
  restore: {
    action: 'restore',
    titleKey: 'TENANT_LIFECYCLE.MODAL.RESTORE_TITLE',
    descKey: 'TENANT_LIFECYCLE.MODAL.RESTORE_DESC',
    reasonRequired: false,
    destructive: false,
    needsTypedConfirm: false,
    iconKey: 'restart_alt',
  },
  archive: {
    action: 'archive',
    titleKey: 'TENANT_LIFECYCLE.MODAL.ARCHIVE_TITLE',
    descKey: 'TENANT_LIFECYCLE.MODAL.ARCHIVE_DESC',
    reasonRequired: true,
    destructive: true,
    needsTypedConfirm: true,
    iconKey: 'archive',
  },
  'schedule-purge': {
    action: 'schedule-purge',
    titleKey: 'TENANT_LIFECYCLE.MODAL.SCHEDULE_PURGE_TITLE',
    descKey: 'TENANT_LIFECYCLE.MODAL.SCHEDULE_PURGE_DESC',
    reasonRequired: true,
    destructive: true,
    needsTypedConfirm: true,
    iconKey: 'delete_forever',
  },
  'cancel-purge': {
    action: 'cancel-purge',
    titleKey: 'TENANT_LIFECYCLE.MODAL.CANCEL_PURGE_TITLE',
    descKey: 'TENANT_LIFECYCLE.MODAL.CANCEL_PURGE_DESC',
    reasonRequired: false,
    destructive: false,
    needsTypedConfirm: false,
    iconKey: 'undo',
  },
};

@Component({
  selector: 'app-organization-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule, DatePipe],
  templateUrl: './organization-detail.html',
  styleUrl: './organization-detail.scss',
})
export class OrganizationDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly orgService = inject(OrganizationService);
  private readonly toast = inject(ToastService);

  readonly organization = signal<OrganizationResponse | null>(null);
  readonly lifecycle = signal<TenantLifecycleResponse | null>(null);
  readonly loading = signal(true);

  readonly activeAction = signal<ActionConfig | null>(null);
  readonly modalReason = signal('');
  readonly modalConfirmText = signal('');
  readonly modalPurgeAt = signal('');
  readonly submitting = signal(false);

  readonly stateLabelKey = computed(() => {
    const state = this.lifecycle()?.lifecycleState;
    return state ? `TENANT_LIFECYCLE.STATE.${state}` : '';
  });

  readonly stateColor = computed(() => {
    return stateColor(this.lifecycle()?.lifecycleState);
  });

  readonly canConfirm = computed(() => {
    const a = this.activeAction();
    if (!a) return false;
    if (a.reasonRequired && this.modalReason().trim().length === 0) return false;
    if (a.needsTypedConfirm && this.modalConfirmText() !== this.organization()?.code) return false;
    return true;
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.toast.error('Missing organization id');
      void this.router.navigate(['/organizations']);
      return;
    }
    this.loadAll(id);
  }

  loadAll(id: string): void {
    this.loading.set(true);
    this.orgService.getById(id).subscribe({
      next: (org) => this.organization.set(org),
      error: () => this.toast.error('Failed to load organization'),
    });
    this.orgService.getLifecycle(id).subscribe({
      next: (lc) => {
        this.lifecycle.set(lc);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load lifecycle');
        this.loading.set(false);
      },
    });
  }

  openAction(action: LifecycleAction): void {
    this.modalReason.set('');
    this.modalConfirmText.set('');
    this.modalPurgeAt.set('');
    this.activeAction.set(ACTION_CONFIG[action]);
  }

  closeModal(): void {
    if (this.submitting()) return;
    this.activeAction.set(null);
  }

  confirm(): void {
    const cfg = this.activeAction();
    const org = this.organization();
    if (!cfg || !org || !this.canConfirm()) return;

    const body = {
      reason: this.modalReason().trim() || undefined,
      purgeScheduledFor:
        cfg.action === 'schedule-purge' && this.modalPurgeAt()
          ? new Date(this.modalPurgeAt()).toISOString()
          : undefined,
    };

    this.submitting.set(true);
    const op = this.dispatch(cfg.action, org.id, body);

    op.subscribe({
      next: (lc) => {
        this.lifecycle.set(lc);
        this.submitting.set(false);
        this.activeAction.set(null);
        this.toast.success('Lifecycle action applied');
      },
      error: (err) => {
        this.submitting.set(false);
        this.toast.error(err?.error?.message ?? 'Action failed');
      },
    });
  }

  private dispatch(
    action: LifecycleAction,
    id: string,
    body: { reason?: string; purgeScheduledFor?: string },
  ) {
    switch (action) {
      case 'suspend':
        return this.orgService.suspend(id, body);
      case 'restore':
        return this.orgService.restoreLifecycle(id, body);
      case 'archive':
        return this.orgService.archive(id, body);
      case 'schedule-purge':
        return this.orgService.schedulePurge(id, body);
      case 'cancel-purge':
        return this.orgService.cancelPurge(id, body);
    }
  }
}

export function stateColor(state: OrganizationLifecycleState | undefined): string {
  switch (state) {
    case 'ACTIVE':
      return '#10b981';
    case 'SUSPENDED':
      return '#f59e0b';
    case 'ARCHIVED':
      return '#64748b';
    case 'PENDING_PURGE':
      return '#ef4444';
    case 'PURGED':
      return '#1e293b';
    default:
      return '#94a3b8';
  }
}
