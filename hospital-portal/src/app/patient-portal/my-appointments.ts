import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, TitleCasePipe } from '@angular/common';
import { PatientPortalService, PortalAppointment } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-appointments',
  standalone: true,
  imports: [CommonModule, DatePipe, TitleCasePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">calendar_month</span>
          My Appointments
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading appointments...</p>
        </div>
      } @else if (appointments().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">event_available</span>
          <h3>No appointments found</h3>
          <p>You don't have any appointments scheduled.</p>
        </div>
      } @else {
        <!-- Upcoming -->
        @if (upcoming().length > 0) {
          <section class="portal-section">
            <h2 class="portal-section-title">Upcoming</h2>
            @for (appt of upcoming(); track appt.id) {
              <div class="portal-appt-card">
                <div class="pac-date">
                  <span class="pac-month">{{ appt.date | date: 'MMM' }}</span>
                  <span class="pac-day">{{ appt.date | date: 'd' }}</span>
                </div>
                <div class="pac-body">
                  <span class="pac-provider">{{ appt.providerName || 'Provider' }}</span>
                  <span class="pac-detail">{{
                    appt.reason || appt.department || 'General Visit'
                  }}</span>
                  <span class="pac-time">
                    <span class="material-symbols-outlined" style="font-size:14px">schedule</span>
                    {{ appt.startTime }} {{ appt.location ? '· ' + appt.location : '' }}
                  </span>
                </div>
                <span class="pac-status" [attr.data-status]="appt.status">{{
                  appt.status | titlecase
                }}</span>
              </div>
            }
          </section>
        }

        <!-- Past -->
        @if (past().length > 0) {
          <section class="portal-section">
            <h2 class="portal-section-title">Past Appointments</h2>
            @for (appt of past(); track appt.id) {
              <div class="portal-appt-card past">
                <div class="pac-date">
                  <span class="pac-month">{{ appt.date | date: 'MMM' }}</span>
                  <span class="pac-day">{{ appt.date | date: 'd' }}</span>
                </div>
                <div class="pac-body">
                  <span class="pac-provider">{{ appt.providerName || 'Provider' }}</span>
                  <span class="pac-detail">{{
                    appt.reason || appt.department || 'General Visit'
                  }}</span>
                  <span class="pac-time">{{ appt.date | date: 'MMM d, yyyy' }}</span>
                </div>
                <span class="pac-status" [attr.data-status]="appt.status">{{
                  appt.status | titlecase
                }}</span>
              </div>
            }
          </section>
        }
      }
    </div>
  `,
  styleUrl: './patient-portal-pages.scss',
})
export class MyAppointmentsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  appointments = signal<PortalAppointment[]>([]);
  loading = signal(true);

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
}
