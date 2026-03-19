import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PatientPortalService,
  ProxyResponse,
  ProxyGrantRequest,
} from '../services/patient-portal.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-my-family-access',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule],
  styleUrl: './patient-portal-pages.scss',
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">family_restroom</span>
          Family & Proxy Access
        </h1>
        <button class="btn-primary" (click)="showGrantForm.set(!showGrantForm())">
          <span class="material-symbols-outlined">person_add</span>
          {{ showGrantForm() ? 'Cancel' : 'Grant Access' }}
        </button>
      </div>

      <!-- Grant Form -->
      @if (showGrantForm()) {
        <div class="portal-card grant-form">
          <h3>Grant Proxy Access</h3>
          <div class="form-grid">
            <label>
              Username
              <input
                type="text"
                [(ngModel)]="form.proxyUsername"
                placeholder="Enter proxy user's username"
              />
            </label>
            <label>
              Relationship
              <select [(ngModel)]="form.relationship">
                <option value="">Select…</option>
                <option value="PARENT">Parent</option>
                <option value="SPOUSE">Spouse</option>
                <option value="CHILD">Child</option>
                <option value="CAREGIVER">Caregiver</option>
                <option value="LEGAL_GUARDIAN">Legal Guardian</option>
                <option value="SIBLING">Sibling</option>
                <option value="OTHER">Other</option>
              </select>
            </label>
            <label>
              Permissions
              <select [(ngModel)]="form.permissions">
                <option value="ALL">All Access</option>
                <option value="APPOINTMENTS">Appointments Only</option>
                <option value="LAB_RESULTS">Lab Results Only</option>
                <option value="MEDICATIONS">Medications Only</option>
                <option value="VITALS">Vitals Only</option>
                <option value="BILLING">Billing Only</option>
                <option value="APPOINTMENTS,LAB_RESULTS,MEDICATIONS">
                  Appointments + Lab + Medications
                </option>
              </select>
            </label>
            <label>
              Notes (optional)
              <input type="text" [(ngModel)]="form.notes" placeholder="e.g. My mother" />
            </label>
          </div>
          <button class="btn-primary" (click)="grantAccess()" [disabled]="granting()">
            {{ granting() ? 'Granting…' : 'Grant Access' }}
          </button>
        </div>
      }

      <!-- Tabs -->
      <div class="rec-tabs">
        <button
          class="rec-tab"
          [class.active]="activeTab() === 'granted'"
          (click)="activeTab.set('granted')"
        >
          <span class="material-symbols-outlined">person_add</span> Granted by Me
        </button>
        <button
          class="rec-tab"
          [class.active]="activeTab() === 'received'"
          (click)="switchToReceived()"
        >
          <span class="material-symbols-outlined">group</span> Access I Have
        </button>
      </div>

      <!-- GRANTED BY ME -->
      @if (activeTab() === 'granted') {
        @if (loading()) {
          <div class="portal-loading">
            <div class="portal-spinner"></div>
            <span>Loading proxy grants…</span>
          </div>
        } @else if (!proxies().length) {
          <div class="portal-empty">
            <span class="material-symbols-outlined empty-icon">person_off</span>
            <p>You haven't granted proxy access to anyone yet.</p>
          </div>
        } @else {
          <div class="portal-table-wrapper">
            <table class="portal-table">
              <thead>
                <tr>
                  <th>Person</th>
                  <th>Relationship</th>
                  <th>Permissions</th>
                  <th>Granted</th>
                  <th>Expires</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (p of proxies(); track p.id) {
                  <tr>
                    <td>
                      <strong>{{ p.proxyDisplayName }}</strong>
                      <br />
                      <small class="text-muted">{{ p.proxyUsername }}</small>
                    </td>
                    <td>
                      <span class="badge">{{ p.relationship }}</span>
                    </td>
                    <td>{{ p.permissions }}</td>
                    <td>{{ p.createdAt | date: 'mediumDate' }}</td>
                    <td>{{ p.expiresAt ? (p.expiresAt | date: 'mediumDate') : 'Never' }}</td>
                    <td>
                      <button class="btn-danger-sm" (click)="revokeAccess(p.id)">
                        <span class="material-symbols-outlined">block</span> Revoke
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }

      <!-- ACCESS I HAVE (as proxy) -->
      @if (activeTab() === 'received') {
        @if (loadingReceived()) {
          <div class="portal-loading">
            <div class="portal-spinner"></div>
            <span>Loading…</span>
          </div>
        } @else if (!proxyAccess().length) {
          <div class="portal-empty">
            <span class="material-symbols-outlined empty-icon">person_off</span>
            <p>No one has granted you proxy access yet.</p>
          </div>
        } @else {
          <div class="portal-table-wrapper">
            <table class="portal-table">
              <thead>
                <tr>
                  <th>Patient</th>
                  <th>Relationship</th>
                  <th>Permissions</th>
                  <th>Since</th>
                  <th>Expires</th>
                </tr>
              </thead>
              <tbody>
                @for (p of proxyAccess(); track p.id) {
                  <tr>
                    <td>
                      <strong>{{ p.grantorName }}</strong>
                    </td>
                    <td>
                      <span class="badge">{{ p.relationship }}</span>
                    </td>
                    <td>{{ p.permissions }}</td>
                    <td>{{ p.createdAt | date: 'mediumDate' }}</td>
                    <td>{{ p.expiresAt ? (p.expiresAt | date: 'mediumDate') : 'Never' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    </div>
  `,
})
export class MyFamilyAccessComponent implements OnInit {
  private readonly portalService = inject(PatientPortalService);
  private readonly toast = inject(ToastService);

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
        this.toast.error('Failed to load proxy grants');
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
          this.toast.error('Failed to load proxy access');
          this.loadingReceived.set(false);
        },
      });
    }
  }

  grantAccess(): void {
    if (!this.form.proxyUsername || !this.form.relationship) {
      this.toast.error('Username and relationship are required');
      return;
    }
    this.granting.set(true);
    this.portalService.grantProxy(this.form).subscribe({
      next: (proxy) => {
        this.proxies.update((list) => [proxy, ...list]);
        this.showGrantForm.set(false);
        this.form = { proxyUsername: '', relationship: '', permissions: 'ALL' };
        this.granting.set(false);
        this.toast.success('Proxy access granted');
      },
      error: () => {
        this.granting.set(false);
        this.toast.error('Failed to grant proxy access');
      },
    });
  }

  revokeAccess(proxyId: string): void {
    this.portalService.revokeProxy(proxyId).subscribe({
      next: () => {
        this.proxies.update((list) => list.filter((p) => p.id !== proxyId));
        this.toast.success('Proxy access revoked');
      },
      error: () => this.toast.error('Failed to revoke proxy access'),
    });
  }
}
