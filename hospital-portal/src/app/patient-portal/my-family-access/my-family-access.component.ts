import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientPortalService,
  ProxyResponse,
  ProxyGrantRequest,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { localDateString, toServerDateTime } from '../../shared/date-utils';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog/confirm-dialog.component';
import { refusalReason } from '../../shared/refusal-reason';

@Component({
  selector: 'app-my-family-access',
  standalone: true,
  imports: [
    ConfirmDialogComponent,
    ServerTimePipe,
    CommonModule,
    DatePipe,
    FormsModule,
    RouterLink,
    EnumLabelPipe,
    TranslateModule,
  ],
  templateUrl: './my-family-access.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./my-family-access.component.scss', '../patient-portal-pages.scss'],
})
export class MyFamilyAccessComponent implements OnInit {
  private readonly portalService = inject(PatientPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  proxies = signal<ProxyResponse[]>([]);
  proxyAccess = signal<ProxyResponse[]>([]);
  loading = signal(true);
  loadingReceived = signal(false);
  granting = signal(false);
  showGrantForm = signal(false);
  activeTab = signal<'granted' | 'received'>('granted');

  form: ProxyGrantRequest = {
    proxyUsername: '',
    relationship: '',
    permissions: 'ALL',
  };

  /**
   * Optional expiry, as the date input's yyyy-MM-dd. Kept separate from the
   * form because the backend wants a LocalDateTime; converted on submit to
   * end-of-day so "expires 2026-09-01" means the whole of that day, not
   * midnight the night before.
   */
  expiresOn = '';
  /** Earliest selectable expiry — a grant that expires in the past is a revoke. */
  readonly minExpiry = localDateString();

  ngOnInit(): void {
    this.loadProxies();
  }

  loadProxies(): void {
    this.loading.set(true);
    this.portalService.getMyProxies().subscribe({
      next: (list) => {
        this.proxies.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PORTAL.FAMILY.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  switchToReceived(): void {
    this.activeTab.set('received');
    if (!this.proxyAccess().length) {
      this.loadingReceived.set(true);
      this.portalService.getMyProxyAccess().subscribe({
        next: (list) => {
          this.proxyAccess.set(list);
          this.loadingReceived.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('PORTAL.FAMILY.LOAD_ACCESS_FAILED'));
          this.loadingReceived.set(false);
        },
      });
    }
  }

  grantAccess(): void {
    if (!this.form.proxyUsername || !this.form.relationship) {
      this.toast.error(this.translate.instant('PORTAL.FAMILY.REQUIRED_FIELDS'));
      return;
    }
    // #948 review: the end of the reader's day, on the server's UTC clock
    // that the expiry is checked against (and that the list reads back
    // through serverTime). Sent as the wall clock, the grant lasted hours too
    // long or too short and was listed under the next day east of UTC.
    this.form.expiresAt = this.expiresOn
      ? toServerDateTime(`${this.expiresOn}T23:59:59`)
      : undefined;
    this.granting.set(true);
    this.portalService.grantProxy(this.form).subscribe({
      next: (proxy) => {
        this.proxies.update((list) => [proxy, ...list]);
        this.showGrantForm.set(false);
        this.form = { proxyUsername: '', relationship: '', permissions: 'ALL' };
        this.expiresOn = '';
        this.granting.set(false);
        this.toast.success(this.translate.instant('PORTAL.FAMILY.ACCESS_GRANTED'));
      },
      error: (err: unknown) => {
        this.granting.set(false);
        this.toast.error(this.grantFailureMessage(err));
      },
    });
  }

  /**
   * QA D893: a refused grant says what the patient can fix. The server's own
   * message (already in the reader's language: unknown username, yourself,
   * already granted) for a 4xx; an unknown username without one still names
   * the problem; anything else stays the generic failure.
   */
  private grantFailureMessage(err: unknown): string {
    const status = (err as { status?: number } | null)?.status ?? 0;
    const message = (err as { error?: { message?: unknown } } | null)?.error?.message;
    if (status >= 400 && status < 500 && typeof message === 'string' && message.trim()) {
      return message;
    }
    return this.translate.instant(
      status === 404 ? 'PORTAL.FAMILY.USER_NOT_FOUND' : 'PORTAL.FAMILY.GRANT_FAILED',
    );
  }

  /** QA D1197: the access being revoked, awaiting the confirmation. */
  revoking = signal<ProxyResponse | null>(null);
  revokeBusy = signal(false);

  askRevoke(proxy: ProxyResponse): void {
    this.revoking.set(proxy);
  }

  revokeAccess(proxyId: string): void {
    if (this.revokeBusy()) return;
    this.revokeBusy.set(true);
    this.portalService.revokeProxy(proxyId).subscribe({
      next: () => {
        this.revokeBusy.set(false);
        this.revoking.set(null);
        this.proxies.update((list) => list.filter((p) => p.id !== proxyId));
        this.toast.success(this.translate.instant('PORTAL.FAMILY.ACCESS_REVOKED'));
      },
      error: (err: unknown) => {
        this.revokeBusy.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PORTAL.FAMILY.REVOKE_FAILED'),
        );
      },
    });
  }
}
