import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
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
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { blankFields, refuseFields } from '../../shared/forms/invalid-fields';
import { PortalRights, minorNoticeKey } from '../minor-rights';

/** QA D1281: the grant form's required fields, in reading order. */
const GRANT_USERNAME_ID = 'family-proxy-username';
const GRANT_RELATIONSHIP_ID = 'family-relationship';
const GRANT_FIELDS = [GRANT_USERNAME_ID, GRANT_RELATIONSHIP_ID];
/** QA D1464: the grant's optional end date. */
const EXPIRES_FIELD = 'proxy-expires';
/** Policy 2026-11-20: the explicit grant scope that opens tuberculosis (restricted) records. */
const TUBERCULOSIS_SCOPE = 'VIEW_TUBERCULOSIS';

@Component({
  selector: 'app-my-family-access',
  standalone: true,
  imports: [
    ConfirmDialogComponent,
    SubmitGuardDirective,
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
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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
   * Policy 2026-11-20 (provisional): tuberculosis records are restricted. They
   * reach this person only when the patient ticks this; no other scope opens them.
   */
  readonly shareTuberculosis = signal(false);

  /** A grant's scopes without the tuberculosis one, which is labelled on its own. */
  baseScopes(permissions: string | null | undefined): string {
    return (permissions ?? '')
      .split(',')
      .map((scope) => scope.trim())
      .filter((scope) => scope && scope !== TUBERCULOSIS_SCOPE)
      .join(',');
  }

  sharesTuberculosis(permissions: string | null | undefined): boolean {
    return (permissions ?? '').split(',').some((scope) => scope.trim() === TUBERCULOSIS_SCOPE);
  }

  /**
   * Optional expiry, as the date input's yyyy-MM-dd. Kept separate from the
   * form because the backend wants a LocalDateTime; converted on submit to
   * end-of-day so "expires 2026-09-01" means the whole of that day, not
   * midnight the night before.
   */
  expiresOn = '';
  /** Earliest selectable expiry — a grant that expires in the past is a revoke. */
  readonly minExpiry = localDateString();

  /** QA D4269: what the account may do alone, by the patient's age; null until known (the server decides). */
  readonly rights = signal<PortalRights | null>(null);
  readonly canGrant = computed(() => this.rights()?.grantFamilyAccess !== false);
  readonly canRevoke = computed(() => this.rights()?.revokeFamilyAccess !== false);

  /**
   * QA D4269: « Retirer » on this row. A minor (15–17) withdraws an access alone except a verified parent's or
   * guardian's, which the server refuses; that row offers no button. While the rights are unknown the server decides.
   */
  canRevokeRow(proxy: ProxyResponse): boolean {
    if (!this.canRevoke()) return false;
    const band = this.rights()?.band;
    return !(proxy.verifiedParentOrGuardian === true && band !== undefined && band !== 'ADULT');
  }
  readonly minorNotice = computed(() => minorNoticeKey(this.rights(), 'family'));

  ngOnInit(): void {
    this.loadProxies();
    this.portalService.getMyProfile().subscribe({
      next: (profile) => {
        this.rights.set(profile.portalRights ?? null);
        if (!this.canGrant()) this.showGrantForm.set(false);
      },
      // Unknown rights hide nothing: the server refuses what the age does not allow, in its own words.
      error: () => void 0,
    });
  }

  loadProxies(): void {
    this.loading.set(true);
    this.portalService.getMyProxies().subscribe({
      next: (list) => {
        this.proxies.set(list);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('PORTAL.FAMILY.LOAD_FAILED'));
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
        error: (err: unknown) => {
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('PORTAL.FAMILY.LOAD_ACCESS_FAILED'),
          );
          this.loadingReceived.set(false);
        },
      });
    }
  }

  grantAccess(): void {
    // QA D1281: only the blank fields are named and marked (the static
    // "username and relationship are required" showed with the username typed).
    const blank = blankFields(
      {
        [GRANT_USERNAME_ID]: this.form.proxyUsername,
        [GRANT_RELATIONSHIP_ID]: this.form.relationship,
      },
      GRANT_FIELDS,
    );
    if (blank.length > 0) {
      refuseFields(
        this.host.nativeElement,
        blank,
        GRANT_FIELDS,
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      const labels = blank.map((id) =>
        this.translate.instant(
          id === GRANT_USERNAME_ID ? 'PORTAL.FAMILY.USERNAME' : 'PORTAL.FAMILY.RELATIONSHIP',
        ),
      );
      this.toast.error(
        this.translate.instant('COMMON.MISSING_REQUIRED_FIELDS', { fields: labels.join(', ') }),
      );
      return;
    }
    // QA D1464: `min` only limits the calendar; a typed past date reached the server,
    // which then answered "access granted" for a grant born expired.
    if (this.expiresOn && this.expiresOn < this.minExpiry) {
      const message = this.translate.instant('PORTAL.FAMILY.EXPIRY_IN_PAST');
      refuseFields(this.host.nativeElement, [EXPIRES_FIELD], [EXPIRES_FIELD], message);
      this.toast.error(message);
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
    // Policy 2026-11-20: the tuberculosis scope is explicit, added only when ticked.
    const request: ProxyGrantRequest = this.shareTuberculosis()
      ? { ...this.form, permissions: `${this.form.permissions},${TUBERCULOSIS_SCOPE}` }
      : this.form;
    this.portalService.grantProxy(request).subscribe({
      next: (proxy) => {
        this.proxies.update((list) => [proxy, ...list]);
        this.showGrantForm.set(false);
        this.shareTuberculosis.set(false);
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
