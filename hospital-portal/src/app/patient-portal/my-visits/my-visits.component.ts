import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, PortalEncounter } from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-visits',
  standalone: true,
  imports: [CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-visits.component.html',
  styleUrls: ['./my-visits.component.scss', '../patient-portal-pages.scss'],
})
export class MyVisitsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  encounters = signal<PortalEncounter[]>([]);
  loading = signal(true);
  expandedId = signal<string | null>(null);

  ngOnInit() {
    this.portal.getMyEncounters().subscribe({
      next: (enc) => {
        this.encounters.set(enc);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  toggleExpand(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }
}
