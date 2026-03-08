import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import {
  PatientPortalService,
  PatientConsent,
  AccessLogEntry,
} from '../services/patient-portal.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-my-sharing',
  standalone: true,
  imports: [CommonModule, DatePipe],
  styleUrl: './patient-portal-pages.scss',
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">share</span>
          Record Sharing & Privacy
        </h1>
      </div>

      <!-- Tabs -->
      <div class="rec-tabs">
        <button
          class="rec-tab"
          [class.active]="activeTab() === 'consents'"
          (click)="activeTab.set('consents')"
        >
          <span class="material-symbols-outlined">handshake</span> Sharing Consents
        </button>
        <button
          class="rec-tab"
          [class.active]="activeTab() === 'access-log'"
          (click)="switchToAccessLog()"
        >
          <span class="material-symbols-outlined">visibility</span> Who Viewed My Records
        </button>
      </div>

      <!-- CONSENTS TAB -->
      @if (activeTab() === 'consents') {
        @if (loadingConsents()) {
          <div class="portal-loading">
            <div class="portal-spinner"></div>
            <span>Loading consents…</span>
          </div>
        } @else if (!consents().length) {
          <div class="portal-empty">
            <span class="material-symbols-outlined">verified_user</span>
            <h3>No Active Consents</h3>
            <p>You haven't shared your records with any other hospital yet.</p>
            <p class="rec-muted" style="margin-top: 8px;">
              When a referral requires sharing your records between hospitals, you can manage those
              consents here.
            </p>
          </div>
        } @else {
          <div class="portal-list" style="margin-top: 16px;">
            @for (c of consents(); track c.id) {
              <div class="portal-list-item sharing-consent-item">
                <div class="pli-icon" style="background: #d1fae5;">
                  <span class="material-symbols-outlined" style="color: #059669;"
                    >verified_user</span
                  >
                </div>
                <div class="pli-body">
                  <span class="pli-title">{{ c.fromHospitalName }} → {{ c.toHospitalName }}</span>
                  <span class="pli-sub">Purpose: {{ c.purpose || 'Treatment' }}</span>
                  <span class="pli-meta">
                    Granted {{ c.grantedAt | date: 'mediumDate' }}
                    @if (c.expiresAt) {
                      · Expires {{ c.expiresAt | date: 'mediumDate' }}
                    }
                  </span>
                </div>
                <div class="consent-actions">
                  <span class="portal-status-chip" [attr.data-status]="c.status">{{
                    c.status
                  }}</span>
                  @if (c.status === 'ACTIVE') {
                    <button class="revoke-btn" (click)="revokeConsent(c)" [disabled]="revoking()">
                      Revoke
                    </button>
                  }
                </div>
              </div>
            }
          </div>
        }
      }

      <!-- ACCESS LOG TAB -->
      @if (activeTab() === 'access-log') {
        @if (loadingLog()) {
          <div class="portal-loading">
            <div class="portal-spinner"></div>
            <span>Loading access log…</span>
          </div>
        } @else if (!accessLog().length) {
          <div class="portal-empty">
            <span class="material-symbols-outlined">shield</span>
            <h3>No Access Records</h3>
            <p>Nobody has accessed your records yet, or access logging has just started.</p>
          </div>
        } @else {
          <div class="portal-list" style="margin-top: 16px;">
            @for (entry of accessLog(); track entry.id) {
              <div class="portal-list-item">
                <div class="pli-icon" style="background: #f1f5f9;">
                  <span class="material-symbols-outlined" style="color: #64748b;">visibility</span>
                </div>
                <div class="pli-body">
                  <span class="pli-title">{{ entry.accessedBy }}</span>
                  <span class="pli-sub">{{ entry.accessedByRole }} · {{ entry.accessType }}</span>
                  <span class="pli-meta">
                    {{ entry.resourceAccessed }} · {{ entry.accessedAt | date: 'medium' }}
                  </span>
                </div>
              </div>
            }
          </div>
        }
      }
    </div>
  `,
})
export class MySharingComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);

  activeTab = signal<'consents' | 'access-log'>('consents');
  loadingConsents = signal(true);
  loadingLog = signal(false);
  consents = signal<PatientConsent[]>([]);
  accessLog = signal<AccessLogEntry[]>([]);
  revoking = signal(false);
  accessLogLoaded = false;

  ngOnInit(): void {
    this.portal.getMyConsents().subscribe({
      next: (c) => {
        this.consents.set(c);
        this.loadingConsents.set(false);
      },
      error: () => this.loadingConsents.set(false),
    });
  }

  switchToAccessLog(): void {
    this.activeTab.set('access-log');
    if (!this.accessLogLoaded) {
      this.loadingLog.set(true);
      this.portal.getMyAccessLog().subscribe({
        next: (log) => {
          this.accessLog.set(log);
          this.loadingLog.set(false);
          this.accessLogLoaded = true;
        },
        error: () => this.loadingLog.set(false),
      });
    }
  }

  revokeConsent(c: PatientConsent): void {
    this.revoking.set(true);
    this.portal.revokeConsent(c.fromHospitalId, c.toHospitalId).subscribe({
      next: () => {
        this.consents.update((list) =>
          list.map((item) => (item.id === c.id ? { ...item, status: 'REVOKED' } : item)),
        );
        this.toast.success('Consent revoked successfully');
        this.revoking.set(false);
      },
      error: () => {
        this.toast.error('Failed to revoke consent');
        this.revoking.set(false);
      },
    });
  }
}
