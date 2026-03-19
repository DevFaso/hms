import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, ImagingOrderDTO } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-imaging-orders',
  standalone: true,
  imports: [CommonModule, DatePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">radiology</span>
          My Imaging Orders
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading imaging orders...</p>
        </div>
      } @else if (orders().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">radiology</span>
          <h3>No imaging orders found</h3>
          <p>You have no imaging or radiology orders on record.</p>
        </div>
      } @else {
        <section class="portal-section">
          <p class="count-label">{{ orders().length }} order(s) on record</p>
          <div class="orders-list">
            @for (o of orders(); track o.id) {
              <div class="order-card">
                <div class="order-icon" [ngClass]="statusColorClass(o.status)">
                  <span class="material-symbols-outlined">radiology</span>
                </div>
                <div class="order-body">
                  <h4 class="order-name">{{ o.modality }}{{ o.studyType ? ' — ' + o.studyType : '' }}</h4>
                  <p class="order-sub">{{ o.bodyRegion ? o.bodyRegion + ' · ' : '' }}{{ o.hospitalName }}</p>
                  <div class="meta-row">
                    <span class="status-badge" [ngClass]="statusColorClass(o.status)">
                      {{ o.status | titlecase }}
                    </span>
                    <span class="priority-chip" [ngClass]="priorityColorClass(o.priority)">
                      {{ o.priority | titlecase }}
                    </span>
                    @if (o.contrastRequired) {
                      <span class="contrast-chip">Contrast Required</span>
                    }
                    <span class="date-chip">
                      <span class="material-symbols-outlined icon-sm">schedule</span>
                      Ordered {{ o.orderedAt | date: 'MMM d, yyyy' }}
                    </span>
                  </div>
                  @if (o.scheduledDate) {
                    <p class="scheduled-info">
                      <span class="material-symbols-outlined icon-sm">event</span>
                      Scheduled: {{ o.scheduledDate | date: 'MMMM d, yyyy' }}
                      {{ o.scheduledTime ? 'at ' + o.scheduledTime : '' }}
                      {{ o.appointmentLocation ? '· ' + o.appointmentLocation : '' }}
                    </p>
                  }
                  @if (o.orderingProviderName) {
                    <p class="provider-info">Ordered by: {{ o.orderingProviderName }}</p>
                  }
                  @if (o.clinicalQuestion) {
                    <p class="clinical-q">Clinical question: {{ o.clinicalQuestion }}</p>
                  }
                  @if (o.cancelledAt) {
                    <p class="cancelled-info">
                      Cancelled {{ o.cancelledAt | date: 'MMM d, yyyy' }}
                      {{ o.cancellationReason ? '— ' + o.cancellationReason : '' }}
                    </p>
                  }
                </div>
              </div>
            }
          </div>
        </section>
      }
    </div>
  `,
  styles: [
    `
      .count-label {
        font-size: 14px;
        color: #64748b;
        margin-bottom: 16px;
      }
      .orders-list {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .order-card {
        display: flex;
        align-items: flex-start;
        gap: 14px;
        padding: 18px;
        background: #fff;
        border-radius: 14px;
        border: 1px solid #e2e8f0;
        transition: box-shadow 0.15s;
      }
      .order-card:hover {
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
      }
      .order-icon {
        width: 44px;
        height: 44px;
        border-radius: 12px;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
        background: #dbeafe;
        color: #2563eb;
      }
      .order-icon.green {
        background: #dcfce7;
        color: #16a34a;
      }
      .order-icon.amber {
        background: #fef9c3;
        color: #ca8a04;
      }
      .order-icon.red {
        background: #fee2e2;
        color: #dc2626;
      }
      .order-body {
        flex: 1;
      }
      .order-name {
        font-size: 16px;
        font-weight: 600;
        color: #1e293b;
        margin: 0 0 4px;
      }
      .order-sub {
        font-size: 13px;
        color: #64748b;
        margin: 0 0 10px;
      }
      .meta-row {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
        margin-bottom: 8px;
      }
      .status-badge,
      .priority-chip,
      .date-chip,
      .contrast-chip {
        font-size: 12px;
        font-weight: 600;
        padding: 3px 10px;
        border-radius: 20px;
      }
      .status-badge.green {
        background: #dcfce7;
        color: #15803d;
      }
      .status-badge.amber {
        background: #fef9c3;
        color: #a16207;
      }
      .status-badge.red {
        background: #fee2e2;
        color: #dc2626;
      }
      .status-badge {
        background: #dbeafe;
        color: #1d4ed8;
      }
      .priority-chip {
        background: #f1f5f9;
        color: #475569;
      }
      .priority-chip.red {
        background: #fee2e2;
        color: #dc2626;
      }
      .date-chip {
        display: flex;
        align-items: center;
        gap: 4px;
        background: #f8fafc;
        color: #64748b;
        font-weight: 500;
      }
      .icon-sm {
        font-size: 14px;
      }
      .contrast-chip {
        background: #fdf4ff;
        color: #9333ea;
      }
      .scheduled-info,
      .provider-info,
      .clinical-q {
        font-size: 13px;
        color: #475569;
        margin: 4px 0 0;
        display: flex;
        align-items: center;
        gap: 4px;
      }
      .cancelled-info {
        font-size: 13px;
        color: #dc2626;
        margin: 6px 0 0;
        font-style: italic;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
})
export class MyImagingOrdersComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  orders = signal<ImagingOrderDTO[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyImagingOrders().subscribe({
      next: (data) => {
        this.orders.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusColorClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED' || s === 'REPORTED') return 'green';
    if (s === 'CANCELLED') return 'red';
    if (s === 'SCHEDULED' || s === 'IN_PROGRESS') return 'amber';
    return '';
  }

  priorityColorClass(priority: string): string {
    const p = priority?.toUpperCase();
    if (p === 'STAT' || p === 'URGENT') return 'red';
    return '';
  }
}
