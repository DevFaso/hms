import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { RecordSharingService, PatientRecord } from '../../services/record-sharing.service';

@Component({
  selector: 'app-shared-records-viewer',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './shared-records-viewer.component.html',
  styleUrl: './shared-records-viewer.component.scss',
})
export class SharedRecordsViewerComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly sharingService = inject(RecordSharingService);

  record = signal<PatientRecord | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);
  toHospitalId = signal<string>('');

  patientName = computed(() => {
    const r = this.record();
    if (!r) return '';
    return [r.firstName, r.middleName, r.lastName].filter(Boolean).join(' ');
  });

  patientInitials = computed(() => {
    const r = this.record();
    if (!r) return '?';
    return ((r.firstName?.[0] ?? '') + (r.lastName?.[0] ?? '')).toUpperCase() || '?';
  });

  partitioned = computed(() => {
    const r = this.record();
    if (!r) return null;
    const toId = this.toHospitalId();
    const toName = r.toHospitalName ?? '';
    return {
      encounters: this.split(r.encounters, toId, toName),
      treatments: this.split(r.treatments, toId, toName),
      prescriptions: this.split(r.prescriptions, toId, toName),
      labOrders: this.split(r.labOrders, toId, toName),
      labResults: this.split(r.labResults, toId, toName),
      allergies: this.split(r.allergiesDetailed, toId, toName),
      problems: this.split(r.problems, toId, toName),
      surgicalHistory: this.split(r.surgicalHistory, toId, toName),
      advanceDirectives: this.split(r.advanceDirectives, toId, toName),
      vitalSigns: this.split(r.vitalSigns, toId, toName),
      immunizations: this.split(r.immunizations, toId, toName),
      insurances: this.split(r.insurances, toId, toName),
      encounterHistory: this.split(r.encounterHistory, toId, toName),
    };
  });

  ngOnInit(): void {
    const patientId = this.route.snapshot.queryParamMap.get('patientId');
    const toHospitalId = this.route.snapshot.queryParamMap.get('toHospitalId');

    if (!patientId || !toHospitalId) {
      this.error.set('Missing required parameters');
      this.loading.set(false);
      return;
    }

    this.toHospitalId.set(toHospitalId);

    this.sharingService.getAggregatedRecord(patientId, toHospitalId).subscribe({
      next: (data) => {
        this.record.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('SHARED_RECORDS.ERRORS.LOAD_FAILED');
        this.loading.set(false);
      },
    });
  }

  goBack(): void {
    this.router.navigate(['/consent-management']);
  }

  severityClass(severity: string | undefined): string {
    if (!severity) return '';
    switch (severity.toUpperCase()) {
      case 'SEVERE':
      case 'HIGH':
      case 'CRITICAL':
        return 'severity-high';
      case 'MODERATE':
      case 'MEDIUM':
        return 'severity-medium';
      case 'MILD':
      case 'LOW':
        return 'severity-low';
      default:
        return '';
    }
  }

  statusClass(status: string | undefined): string {
    if (!status) return '';
    switch (status.toUpperCase()) {
      case 'COMPLETED':
      case 'ACTIVE':
      case 'RELEASED':
      case 'VERIFIED':
        return 'status-success';
      case 'IN_PROGRESS':
      case 'PENDING':
      case 'ORDERED':
        return 'status-pending';
      case 'CANCELLED':
      case 'RESOLVED':
      case 'INACTIVE':
        return 'status-neutral';
      default:
        return '';
    }
  }

  private split<T extends { hospitalId?: string; hospitalName?: string }>(
    items: T[] | undefined,
    toId: string,
    toName: string,
  ): { to: T[]; from: T[] } {
    const to: T[] = [];
    const from: T[] = [];
    for (const item of items ?? []) {
      if (item.hospitalId === toId || (!item.hospitalId && item.hospitalName === toName)) {
        to.push(item);
      } else {
        from.push(item);
      }
    }
    return { to, from };
  }
}
