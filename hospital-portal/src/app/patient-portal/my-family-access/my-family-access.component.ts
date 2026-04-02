import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  ProxyResponse,
  ProxyGrantRequest,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-family-access',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule, EnumLabelPipe, TranslateModule],
  templateUrl: './my-family-access.component.html',
  styleUrls: ['./my-family-access.component.scss', '../patient-portal-pages.scss'],
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
