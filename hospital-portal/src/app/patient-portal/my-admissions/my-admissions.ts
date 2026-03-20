import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, AdmissionDTO } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-admissions',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-admissions.html',
  styleUrl: './my-admissions.scss',
})
export class MyAdmissionsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  admissions = signal<AdmissionDTO[]>([]);
  current = signal<AdmissionDTO | null>(null);
  loading = signal(true);

  ngOnInit() {
    let loaded = 0;
    const done = () => {
      loaded++;
      if (loaded === 2) this.loading.set(false);
    };

    this.portal.getMyAdmissions().subscribe({
      next: (data) => {
        this.admissions.set(data);
        done();
      },
      error: () => done(),
    });

    this.portal.getMyCurrentAdmission().subscribe({
      next: (data) => {
        this.current.set(data);
        done();
      },
      error: () => done(),
    });
  }

  isActive(status: string): boolean {
    const s = status?.toUpperCase();
    return s === 'ACTIVE' || s === 'PENDING' || s === 'ON_LEAVE';
  }

  statusClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'ACTIVE' || s === 'PENDING' || s === 'ON_LEAVE') return 'active pending-on-leave';
    if (s === 'DISCHARGED') return 'discharged';
    if (s === 'CANCELLED') return 'cancelled';
    return '';
  }
}
