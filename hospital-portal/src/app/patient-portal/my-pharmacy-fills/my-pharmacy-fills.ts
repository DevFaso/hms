import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, PharmacyFillDTO } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-pharmacy-fills',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-pharmacy-fills.html',
  styleUrl: './my-pharmacy-fills.scss',
})
export class MyPharmacyFillsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  fills = signal<PharmacyFillDTO[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyPharmacyFills().subscribe({
      next: (data) => {
        this.fills.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
