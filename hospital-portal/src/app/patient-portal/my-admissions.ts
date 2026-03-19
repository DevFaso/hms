import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, AdmissionDTO } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-admissions',
  standalone: true,
  imports: [CommonModule, DatePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">local_hospital</span>
          My Admissions
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading admission records...</p>
        </div>
      } @else {
        <!-- Current admission banner -->
        @if (current()) {
          <div class="current-banner">
            <div class="banner-icon">
              <span class="material-symbols-outlined">emergency_home</span>
            </div>
            <div class="banner-body">
              <h3>Currently Admitted</h3>
              <p>
                {{ current()!.departmentName || current()!.hospitalName }}
                {{ current()!.roomBed ? '· Room/Bed: ' + current()!.roomBed : '' }}
              </p>
              <p class="banner-since">
                Since {{ current()!.admissionDateTime | date: 'MMMM d, yyyy h:mm a' }}
              </p>
              @if (current()!.attendingPhysicianName) {
                <p class="banner-provider">Attending: {{ current()!.attendingPhysicianName }}</p>
              }
            </div>
            <div class="status-pill active">{{ current()!.status | titlecase }}</div>
          </div>
        }

        @if (admissions().length === 0) {
          <div class="portal-empty">
            <span class="material-symbols-outlined">local_hospital</span>
            <h3>No admission history</h3>
            <p>You have no inpatient admission records on file.</p>
          </div>
        } @else {
          <section class="portal-section">
            <p class="count-label">{{ admissions().length }} admission(s) on record</p>
            <div class="admissions-list">
              @for (a of admissions(); track a.id) {
                <div class="admission-card" [class.active-card]="isActive(a.status)">
                  <div class="admission-header">
                    <div class="header-left">
                      <h4 class="adm-title">
                        {{ a.admissionType | titlecase }} Admission
                        <span class="hosp-name">· {{ a.hospitalName }}</span>
                      </h4>
                      @if (a.departmentName) {
                        <p class="dept-name">
                          {{ a.departmentName }}{{ a.roomBed ? ' · ' + a.roomBed : '' }}
                        </p>
                      }
                    </div>
                    <div class="status-pill" [ngClass]="statusClass(a.status)">
                      {{ a.status | titlecase }}
                    </div>
                  </div>
                  <div class="adm-meta">
                    <span class="meta-chip">
                      <span class="material-symbols-outlined icon-sm">login</span>
                      Admitted {{ a.admissionDateTime | date: 'MMM d, yyyy h:mm a' }}
                    </span>
                    @if (a.actualDischargeDateTime) {
                      <span class="meta-chip discharged">
                        <span class="material-symbols-outlined icon-sm">logout</span>
                        Discharged {{ a.actualDischargeDateTime | date: 'MMM d, yyyy' }}
                      </span>
                    } @else if (a.expectedDischargeDateTime) {
                      <span class="meta-chip expected">
                        <span class="material-symbols-outlined icon-sm">hourglass_bottom</span>
                        Expected discharge {{ a.expectedDischargeDateTime | date: 'MMM d, yyyy' }}
                      </span>
                    }
                    @if (a.lengthOfStayDays !== null) {
                      <span class="meta-chip los">{{ a.lengthOfStayDays }} day(s)</span>
                    }
                    @if (a.acuityLevel) {
                      <span class="meta-chip acuity">{{ a.acuityLevel | titlecase }}</span>
                    }
                  </div>
                  @if (a.chiefComplaint) {
                    <p class="complaint">Chief complaint: {{ a.chiefComplaint }}</p>
                  }
                  @if (a.primaryDiagnosisDescription) {
                    <p class="diagnosis">
                      <span class="material-symbols-outlined icon-sm">diagnosis</span>
                      {{ a.primaryDiagnosisDescription }}
                      {{ a.primaryDiagnosisCode ? '(' + a.primaryDiagnosisCode + ')' : '' }}
                    </p>
                  }
                  @if (a.attendingPhysicianName) {
                    <p class="provider-info">Attending: {{ a.attendingPhysicianName }}</p>
                  }
                  @if (a.dischargeInstructions) {
                    <div class="discharge-box">
                      <p class="discharge-label">Discharge Instructions</p>
                      <p class="discharge-text">{{ a.dischargeInstructions }}</p>
                    </div>
                  }
                </div>
              }
            </div>
          </section>
        }
      }
    </div>
  `,
  styles: [
    `
      .current-banner {
        display: flex;
        align-items: flex-start;
        gap: 16px;
        padding: 20px;
        background: linear-gradient(135deg, #dbeafe 0%, #ede9fe 100%);
        border-radius: 16px;
        border: 1.5px solid #93c5fd;
        margin-bottom: 24px;
      }
      .banner-icon {
        width: 48px;
        height: 48px;
        background: #2563eb;
        color: #fff;
        border-radius: 14px;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }
      .banner-body {
        flex: 1;
      }
      .banner-body h3 {
        font-size: 18px;
        font-weight: 700;
        color: #1e293b;
        margin: 0 0 4px;
      }
      .banner-body p {
        font-size: 14px;
        color: #475569;
        margin: 2px 0;
      }
      .banner-since {
        font-weight: 500;
      }
      .banner-provider {
        font-style: italic;
      }
      .count-label {
        font-size: 14px;
        color: #64748b;
        margin-bottom: 16px;
      }
      .admissions-list {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .admission-card {
        padding: 18px;
        background: #fff;
        border-radius: 14px;
        border: 1px solid #e2e8f0;
        transition: box-shadow 0.15s;
      }
      .admission-card:hover {
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
      }
      .admission-card.active-card {
        border-color: #93c5fd;
        background: #f0f9ff;
      }
      .admission-header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 12px;
        margin-bottom: 10px;
      }
      .adm-title {
        font-size: 16px;
        font-weight: 600;
        color: #1e293b;
        margin: 0 0 4px;
      }
      .hosp-name {
        font-weight: 400;
        color: #64748b;
      }
      .dept-name {
        font-size: 13px;
        color: #64748b;
        margin: 0;
      }
      .status-pill {
        font-size: 12px;
        font-weight: 700;
        padding: 4px 12px;
        border-radius: 20px;
        white-space: nowrap;
        background: #f1f5f9;
        color: #475569;
        flex-shrink: 0;
      }
      .status-pill.active,
      .status-pill.pending-on-leave {
        background: #dbeafe;
        color: #1d4ed8;
      }
      .status-pill.discharged {
        background: #dcfce7;
        color: #15803d;
      }
      .status-pill.cancelled {
        background: #fee2e2;
        color: #dc2626;
      }
      .adm-meta {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
        margin-bottom: 10px;
      }
      .meta-chip {
        font-size: 12px;
        font-weight: 500;
        padding: 3px 10px;
        border-radius: 20px;
        background: #f8fafc;
        color: #64748b;
        display: flex;
        align-items: center;
        gap: 4px;
      }
      .meta-chip.discharged {
        background: #dcfce7;
        color: #15803d;
      }
      .meta-chip.expected {
        background: #fff7ed;
        color: #c2410c;
      }
      .meta-chip.los {
        background: #f0f9ff;
        color: #0284c7;
        font-weight: 600;
      }
      .meta-chip.acuity {
        background: #fdf4ff;
        color: #9333ea;
      }
      .icon-sm {
        font-size: 14px;
      }
      .complaint {
        font-size: 13px;
        color: #475569;
        margin: 4px 0;
        font-style: italic;
      }
      .diagnosis {
        font-size: 13px;
        color: #334155;
        margin: 4px 0;
        display: flex;
        align-items: center;
        gap: 4px;
      }
      .provider-info {
        font-size: 13px;
        color: #64748b;
        margin: 4px 0;
      }
      .discharge-box {
        margin-top: 12px;
        padding: 12px;
        background: #f8fafc;
        border-radius: 10px;
        border-left: 3px solid #6ee7b7;
      }
      .discharge-label {
        font-size: 12px;
        font-weight: 700;
        color: #059669;
        text-transform: uppercase;
        letter-spacing: 0.5px;
        margin: 0 0 6px;
      }
      .discharge-text {
        font-size: 13px;
        color: #334155;
        margin: 0;
        line-height: 1.5;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
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
