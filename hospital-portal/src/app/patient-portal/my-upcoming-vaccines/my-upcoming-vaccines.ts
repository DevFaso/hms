import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PatientPortalService, ImmunizationDTO } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-upcoming-vaccines',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule],
  templateUrl: './my-upcoming-vaccines.html',
  styleUrl: './my-upcoming-vaccines.scss',
})
export class MyUpcomingVaccinesComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  vaccinations = signal<ImmunizationDTO[]>([]);
  loading = signal(true);
  selectedMonths = 6;
  downloadingCert = signal(false);

  ngOnInit() {
    this.loadVaccinations();
  }

  loadVaccinations() {
    this.loading.set(true);
    this.portal.getUpcomingVaccinations(this.selectedMonths).subscribe({
      next: (v) => {
        this.vaccinations.set(v);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  downloadCertificate(): void {
    this.downloadingCert.set(true);
    this.portal.downloadImmunizationCertificate().subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'immunization-certificate.pdf';
        a.click();
        URL.revokeObjectURL(url);
        this.downloadingCert.set(false);
      },
      error: () => this.downloadingCert.set(false),
    });
  }
}
