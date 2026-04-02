import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  PatientConsent,
  AccessLogEntry,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-sharing',
  standalone: true,
  imports: [CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-sharing.component.html',
  styleUrls: ['./my-sharing.component.scss', '../patient-portal-pages.scss'],
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
