import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, PortalAppointment } from '../../services/patient-portal.service';

const CHECKIN_ELIGIBLE = new Set(['SCHEDULED', 'CONFIRMED', 'PENDING']);
const CHECKIN_WINDOW_DAYS = 1; // allow check-in up to 1 day after appointment date

@Component({
  selector: 'app-my-checkin',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-checkin.html',
  styleUrl: './my-checkin.scss',
})
export class MyCheckinComponent implements OnInit {
  private readonly svc = inject(PatientPortalService);

  readonly loading = signal(true);
  readonly error = signal(false);
  private readonly allAppointments = signal<PortalAppointment[]>([]);
  readonly checkedIn = signal<Set<string>>(new Set());
  readonly checkingIn = signal<Set<string>>(new Set());

  readonly eligible = computed(() => {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const windowMs = CHECKIN_WINDOW_DAYS * 24 * 60 * 60 * 1000;
    return this.allAppointments().filter((a) => {
      if (!CHECKIN_ELIGIBLE.has(a.status)) return false;
      if (this.checkedIn().has(a.id)) return true; // keep in list but show as done
      const apptDate = new Date(a.date);
      apptDate.setHours(0, 0, 0, 0);
      const diff = today.getTime() - apptDate.getTime();
      // Allow check-in from today up to CHECKIN_WINDOW_DAYS days after
      return diff <= windowMs && apptDate <= today;
    });
  });

  ngOnInit(): void {
    this.loadAppointments();
  }

  loadAppointments(): void {
    this.loading.set(true);
    this.error.set(false);
    this.svc.getMyAppointments().subscribe({
      next: (data) => {
        this.allAppointments.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  checkIn(appt: PortalAppointment): void {
    const busy = new Set(this.checkingIn());
    busy.add(appt.id);
    this.checkingIn.set(busy);

    this.svc.checkInAppointment(appt.id).subscribe({
      next: () => {
        const done = new Set(this.checkedIn());
        done.add(appt.id);
        this.checkedIn.set(done);
        const busy2 = new Set(this.checkingIn());
        busy2.delete(appt.id);
        this.checkingIn.set(busy2);
      },
      error: () => {
        const busy2 = new Set(this.checkingIn());
        busy2.delete(appt.id);
        this.checkingIn.set(busy2);
      },
    });
  }

  statusClass(status: string): string {
    const s = status.toLowerCase();
    if (s === 'confirmed') return 'status-confirmed';
    if (s === 'scheduled') return 'status-scheduled';
    if (s === 'checked_in') return 'status-checked-in';
    return 'status-pending';
  }
}
