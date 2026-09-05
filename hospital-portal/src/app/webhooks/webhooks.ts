import {
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, Subscription, forkJoin, of, switchMap } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import {
  ApiKey,
  ApiKeyIssued,
  IntegrationKeysService,
  WebhookDelivery,
  WebhookEndpoint,
  WebhookEndpointRegistered,
  WebhookEventType,
} from '../services/integration-keys.service';
import { HospitalScopeChipComponent } from '../shared/hospital-scope-chip/hospital-scope-chip.component';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

type ConfirmKind = 'rotate-key' | 'revoke-key' | 'revoke-endpoint' | 'rotate-secret';

interface RevealedSecret {
  titleKey: string;
  value: string;
}

/**
 * API keys + outbound webhooks (Tier 2 item 45) — the third-party access
 * admin surface. Everything is hospital-pinned server-side, so the page
 * defers behind the scope chip (the #549 lesson) and both lists load
 * through one switchMap so a stale scope's response can never repopulate
 * a cleared table (the #551 lesson).
 *
 * <p>The raw API key and the webhook signing secret each exist in exactly
 * one response; the reveal dialog is the only place they ever render, and
 * closing it is final.
 */
@Component({
  selector: 'app-webhooks',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, HospitalScopeChipComponent],
  templateUrl: './webhooks.html',
  styleUrl: './webhooks.scss',
})
export class WebhooksComponent implements OnInit, OnDestroy {
  private readonly api = inject(IntegrationKeysService);
  private readonly roleCtx = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** PING is fired from the button, never subscribed to. */
  readonly subscribableEvents: WebhookEventType[] = [
    'APPOINTMENT_BOOKED',
    'APPOINTMENT_CANCELLED',
    'APPOINTMENT_RESCHEDULED',
  ];

  keys = signal<ApiKey[]>([]);
  endpoints = signal<WebhookEndpoint[]>([]);
  loading = signal(false);
  loadFailed = signal(false);
  saving = signal(false);

  /* ── issue-key modal ── */
  showIssue = signal(false);
  formKeyLabel = signal('');
  formKeyExpiresOn = signal('');

  /* ── endpoint modal (create or edit) ── */
  editingEndpoint = signal<WebhookEndpoint | null>(null);
  showEndpointForm = signal(false);
  formUrl = signal('');
  formDescription = signal('');
  formEvents = signal<Set<WebhookEventType>>(new Set());

  /* ── one-time secret reveal ── */
  revealed = signal<RevealedSecret | null>(null);

  /* ── confirm modal ── */
  confirming = signal<{ kind: ConfirmKind; id: string; label: string } | null>(null);

  /* ── per-endpoint delivery drilldown ── */
  deliveriesFor = signal<string | null>(null);
  deliveryRows = signal<WebhookDelivery[]>([]);
  deliveriesFailed = signal(false);

  /* ── dialog focus management (registries pattern) ── */
  private readonly issueDialog = viewChild<ElementRef<HTMLElement>>('issueDialog');
  private readonly endpointDialog = viewChild<ElementRef<HTMLElement>>('endpointDialog');
  private readonly revealDialog = viewChild<ElementRef<HTMLElement>>('revealDialog');
  private readonly confirmDialog = viewChild<ElementRef<HTMLElement>>('confirmDialog');
  private dialogOpener: HTMLElement | null = null;

  readonly scopeReady = computed(() => this.roleCtx.effectiveHospitalIdForRequest() != null);

  private readonly load$ = new Subject<void>();
  private loadSub?: Subscription;

  ngOnInit(): void {
    this.loadSub = this.load$
      .pipe(
        // One switchMap for both lists: only the LATEST scope may update
        // the view, and a push while unpinned cancels any in-flight
        // response (the #551 lesson).
        switchMap(() => {
          if (!this.scopeReady()) {
            return of({ keys: [] as ApiKey[], endpoints: [] as WebhookEndpoint[], failed: false });
          }
          this.loading.set(true);
          this.loadFailed.set(false);
          return forkJoin({ keys: this.api.listKeys(), endpoints: this.api.listEndpoints() }).pipe(
            map((r) => ({ ...r, failed: false })),
            // Unavailable, never "no keys": an outage must not read as an
            // empty credential inventory (house stance).
            catchError(() =>
              of({ keys: [] as ApiKey[], endpoints: [] as WebhookEndpoint[], failed: true }),
            ),
          );
        }),
      )
      .subscribe((state) => {
        this.loading.set(false);
        this.keys.set(state.keys);
        this.endpoints.set(state.endpoints);
        this.loadFailed.set(state.failed);
      });
    this.reloadForScope();
  }

  ngOnDestroy(): void {
    this.loadSub?.unsubscribe();
  }

  onScopeChange(_hospitalId: string | null): void {
    this.reloadForScope();
  }

  private reloadForScope(): void {
    this.keys.set([]);
    this.endpoints.set([]);
    this.loadFailed.set(false);
    this.deliveriesFor.set(null);
    this.load$.next();
  }

  load(): void {
    this.load$.next();
  }

  /* ── API keys ── */

  openIssue(event?: Event): void {
    this.dialogOpener = (event?.currentTarget as HTMLElement) ?? null;
    this.formKeyLabel.set('');
    this.formKeyExpiresOn.set('');
    this.showIssue.set(true);
    this.focusDialogSoon(() => this.issueDialog()?.nativeElement);
  }

  closeIssue(): void {
    this.showIssue.set(false);
    this.restoreOpenerFocus();
  }

  submitIssue(): void {
    if (!this.formKeyLabel().trim() || this.saving()) return;
    this.saving.set(true);
    this.api.issueKey(this.formKeyLabel().trim(), this.formKeyExpiresOn() || undefined).subscribe({
      next: (issued: ApiKeyIssued) => {
        this.saving.set(false);
        this.showIssue.set(false);
        this.reveal('WEBHOOKS.KEY_REVEAL_TITLE', issued.rawKey);
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('WEBHOOKS.ACTION_FAILED'));
      },
    });
  }

  /* ── endpoints ── */

  openEndpointForm(endpoint: WebhookEndpoint | null, event?: Event): void {
    this.dialogOpener = (event?.currentTarget as HTMLElement) ?? null;
    this.editingEndpoint.set(endpoint);
    this.formUrl.set(endpoint?.url ?? '');
    this.formDescription.set(endpoint?.description ?? '');
    this.formEvents.set(new Set(endpoint?.events ?? []));
    this.showEndpointForm.set(true);
    this.focusDialogSoon(() => this.endpointDialog()?.nativeElement);
  }

  closeEndpointForm(): void {
    this.showEndpointForm.set(false);
    this.restoreOpenerFocus();
  }

  toggleEvent(eventType: WebhookEventType): void {
    const next = new Set(this.formEvents());
    if (!next.delete(eventType)) {
      next.add(eventType);
    }
    this.formEvents.set(next);
  }

  endpointFormValid(): boolean {
    return !!this.formUrl().trim() && this.formEvents().size > 0;
  }

  submitEndpointForm(): void {
    if (!this.endpointFormValid() || this.saving()) return;
    this.saving.set(true);
    const req = {
      url: this.formUrl().trim(),
      description: this.formDescription().trim() || undefined,
      events: [...this.formEvents()],
    };
    const editing = this.editingEndpoint();
    if (editing) {
      this.api.updateEndpoint(editing.id, req).subscribe({
        next: () => {
          this.saving.set(false);
          this.closeEndpointForm();
          this.toast.success(this.translate.instant('WEBHOOKS.ENDPOINT_SAVED'));
          this.load();
        },
        error: (err: HttpErrorResponse) => this.actionFailed(err),
      });
    } else {
      this.api.registerEndpoint(req).subscribe({
        next: (registered: WebhookEndpointRegistered) => {
          this.saving.set(false);
          this.showEndpointForm.set(false);
          this.reveal('WEBHOOKS.SECRET_REVEAL_TITLE', registered.secret);
          this.load();
        },
        error: (err: HttpErrorResponse) => this.actionFailed(err),
      });
    }
  }

  togglePause(endpoint: WebhookEndpoint): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.api.setEndpointActive(endpoint.id, endpoint.status !== 'ACTIVE').subscribe({
      next: () => {
        this.saving.set(false);
        this.load();
      },
      error: (err: HttpErrorResponse) => this.actionFailed(err),
    });
  }

  ping(endpoint: WebhookEndpoint): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.api.pingEndpoint(endpoint.id).subscribe({
      next: () => {
        this.saving.set(false);
        this.toast.success(this.translate.instant('WEBHOOKS.PING_QUEUED'));
        if (this.deliveriesFor() === endpoint.id) {
          this.loadDeliveries(endpoint.id);
        }
      },
      error: (err: HttpErrorResponse) => this.actionFailed(err),
    });
  }

  /* ── confirmed actions ── */

  openConfirm(kind: ConfirmKind, id: string, label: string, event?: Event): void {
    this.dialogOpener = (event?.currentTarget as HTMLElement) ?? null;
    this.confirming.set({ kind, id, label });
    this.focusDialogSoon(() => this.confirmDialog()?.nativeElement);
  }

  closeConfirm(): void {
    this.confirming.set(null);
    this.restoreOpenerFocus();
  }

  confirmMessageKey(kind: ConfirmKind): string {
    return WebhooksComponent.CONFIRM_KEYS[kind];
  }

  submitConfirm(): void {
    const c = this.confirming();
    if (!c || this.saving()) return;
    this.saving.set(true);
    switch (c.kind) {
      case 'rotate-key':
        this.api.rotateKey(c.id).subscribe({
          next: (issued) => {
            this.saving.set(false);
            this.confirming.set(null);
            this.reveal('WEBHOOKS.KEY_REVEAL_TITLE', issued.rawKey);
            this.load();
          },
          error: (err: HttpErrorResponse) => this.actionFailed(err),
        });
        break;
      case 'revoke-key':
        this.api.revokeKey(c.id).subscribe({
          next: () => this.confirmedDone('WEBHOOKS.KEY_REVOKED'),
          error: (err: HttpErrorResponse) => this.actionFailed(err),
        });
        break;
      case 'revoke-endpoint':
        this.api.revokeEndpoint(c.id).subscribe({
          next: () => this.confirmedDone('WEBHOOKS.ENDPOINT_REVOKED'),
          error: (err: HttpErrorResponse) => this.actionFailed(err),
        });
        break;
      case 'rotate-secret':
        this.api.rotateEndpointSecret(c.id).subscribe({
          next: (registered) => {
            this.saving.set(false);
            this.confirming.set(null);
            this.reveal('WEBHOOKS.SECRET_REVEAL_TITLE', registered.secret);
            this.load();
          },
          error: (err: HttpErrorResponse) => this.actionFailed(err),
        });
        break;
    }
  }

  private confirmedDone(toastKey: string): void {
    this.saving.set(false);
    this.closeConfirm();
    this.toast.success(this.translate.instant(toastKey));
    this.load();
  }

  /* ── secret reveal ── */

  private reveal(titleKey: string, value: string): void {
    this.revealed.set({ titleKey, value });
    this.focusDialogSoon(() => this.revealDialog()?.nativeElement);
  }

  closeReveal(): void {
    this.revealed.set(null);
    this.restoreOpenerFocus();
  }

  copyRevealed(): void {
    const value = this.revealed()?.value;
    if (!value) return;
    navigator.clipboard?.writeText(value).then(
      () => this.toast.success(this.translate.instant('WEBHOOKS.COPIED')),
      () => this.toast.error(this.translate.instant('WEBHOOKS.COPY_FAILED')),
    );
  }

  /* ── deliveries drilldown ── */

  toggleDeliveries(endpoint: WebhookEndpoint): void {
    if (this.deliveriesFor() === endpoint.id) {
      this.deliveriesFor.set(null);
      return;
    }
    this.deliveriesFor.set(endpoint.id);
    this.loadDeliveries(endpoint.id);
  }

  private loadDeliveries(endpointId: string): void {
    this.deliveryRows.set([]);
    this.deliveriesFailed.set(false);
    this.api.deliveries(endpointId).subscribe({
      next: (page) => {
        // A slow response for a drilldown the user already left is stale.
        if (this.deliveriesFor() === endpointId) {
          this.deliveryRows.set(page.content ?? []);
        }
      },
      error: () => {
        if (this.deliveriesFor() === endpointId) {
          this.deliveriesFailed.set(true);
        }
      },
    });
  }

  /* ── display helpers (static maps - the i18n gate scans literals) ── */

  private static readonly CONFIRM_KEYS: Record<ConfirmKind, string> = {
    'rotate-key': 'WEBHOOKS.CONFIRM_ROTATE_KEY',
    'revoke-key': 'WEBHOOKS.CONFIRM_REVOKE_KEY',
    'revoke-endpoint': 'WEBHOOKS.CONFIRM_REVOKE_ENDPOINT',
    'rotate-secret': 'WEBHOOKS.CONFIRM_ROTATE_SECRET',
  };

  keyStatusKey(status: ApiKey['status']): string {
    return status === 'ACTIVE' ? 'WEBHOOKS.STATUS_ACTIVE' : 'WEBHOOKS.STATUS_REVOKED';
  }

  endpointStatusKey(status: WebhookEndpoint['status']): string {
    switch (status) {
      case 'ACTIVE':
        return 'WEBHOOKS.STATUS_ACTIVE';
      case 'PAUSED':
        return 'WEBHOOKS.STATUS_PAUSED';
      case 'DISABLED_FAILURES':
        return 'WEBHOOKS.STATUS_DISABLED';
      default:
        return 'WEBHOOKS.STATUS_REVOKED';
    }
  }

  deliveryStatusKey(status: WebhookDelivery['status']): string {
    switch (status) {
      case 'SENT':
        return 'WEBHOOKS.DELIVERY_SENT';
      case 'ERROR':
        return 'WEBHOOKS.DELIVERY_ERROR';
      default:
        return 'WEBHOOKS.DELIVERY_PENDING';
    }
  }

  eventKey(eventType: WebhookEventType): string {
    return WebhooksComponent.EVENT_KEYS[eventType];
  }

  private static readonly EVENT_KEYS: Record<WebhookEventType, string> = {
    PING: 'WEBHOOKS.EVENT_PING',
    APPOINTMENT_BOOKED: 'WEBHOOKS.EVENT_APPOINTMENT_BOOKED',
    APPOINTMENT_CANCELLED: 'WEBHOOKS.EVENT_APPOINTMENT_CANCELLED',
    APPOINTMENT_RESCHEDULED: 'WEBHOOKS.EVENT_APPOINTMENT_RESCHEDULED',
  };

  private actionFailed(err: HttpErrorResponse): void {
    this.saving.set(false);
    this.toast.error(err.error?.message ?? this.translate.instant('WEBHOOKS.ACTION_FAILED'));
  }

  /* ── Dialog focus: move in on open, cycle on Tab, restore on close ── */

  trapTab(event: KeyboardEvent, dialog: HTMLElement): void {
    const focusables = Array.from(
      dialog.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((el) => !el.hasAttribute('disabled'));
    if (focusables.length === 0) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusDialogSoon(resolve: () => HTMLElement | undefined): void {
    setTimeout(() => resolve()?.focus(), 0);
  }

  private restoreOpenerFocus(): void {
    this.dialogOpener?.focus();
    this.dialogOpener = null;
  }
}
