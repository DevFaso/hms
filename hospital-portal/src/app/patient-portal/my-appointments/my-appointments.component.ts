import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, PortalAppointment } from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-appointments',
  standalone: true,
  imports: [CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-appointments.component.html',
  styleUrls: ['./my-appointments.component.scss', '../patient-portal-pages.scss'],
})
export class MyAppointmentsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  appointments = signal<PortalAppointment[]>([]);
  loading = signal(true);
  expandedId = signal<string | null>(null);

  upcoming = signal<PortalAppointment[]>([]);
  past = signal<PortalAppointment[]>([]);

  ngOnInit() {
    this.portal.getMyAppointments().subscribe({
      next: (appts) => {
        this.appointments.set(appts);
        const now = new Date();
        this.upcoming.set(appts.filter((a) => a.status !== 'CANCELLED' && new Date(a.date) >= now));
        this.past.set(appts.filter((a) => a.status === 'COMPLETED' || new Date(a.date) < now));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  toggleExpand(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }
}
