import { Component, computed, inject } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';

import { EmergencyBroadcastService } from '../services/emergency-broadcast.service';

@Component({
  selector: 'app-emergency-broadcast-banner',
  standalone: true,
  imports: [CommonModule, TranslateModule, DatePipe],
  templateUrl: './emergency-broadcast-banner.html',
  styleUrl: './emergency-broadcast-banner.scss',
})
export class EmergencyBroadcastBannerComponent {
  private readonly broadcast = inject(EmergencyBroadcastService);

  readonly frame = computed(() => this.broadcast.latest());
  readonly visible = computed(() => this.frame() !== null);

  readonly severityClass = computed(() => {
    const sev = (this.frame()?.severity ?? 'INFO').toUpperCase();
    if (sev === 'CRITICAL') return 'severity-critical';
    if (sev === 'WARN') return 'severity-warn';
    return 'severity-info';
  });

  dismiss(): void {
    this.broadcast.dismiss();
  }
}
