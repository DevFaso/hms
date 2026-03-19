import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, LabOrderDTO } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-lab-orders',
  standalone: true,
  imports: [CommonModule, DatePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">biotech</span>
          My Lab Orders
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading lab orders...</p>
        </div>
      } @else if (orders().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">biotech</span>
          <h3>No lab orders found</h3>
          <p>You have no laboratory orders on record.</p>
        </div>
      } @else {
        <section class="portal-section">
          <p class="count-label">{{ orders().length }} order(s) on record</p>
          <div class="orders-list">
            @for (o of orders(); track o.id) {
              <div class="order-card">
                <div class="order-icon" [ngClass]="statusColorClass(o.status)">
                  <span class="material-symbols-outlined">biotech</span>
                </div>
                <div class="order-body">
                  <h4 class="order-name">{{ o.labTestName }}</h4>
                  <p class="order-sub">{{ o.labTestCode }} · {{ o.hospitalName }}</p>
                  <div class="meta-row">
                    <span class="status-badge" [ngClass]="statusColorClass(o.status)">
                      {{ o.status | titlecase }}
                    </span>
                    <span class="priority-chip" [ngClass]="priorityColorClass(o.priority)">
                      {{ o.priority | titlecase }}
                    </span>
                    <span class="date-chip">
                      <span class="material-symbols-outlined icon-sm">schedule</span>
                      {{ o.orderDatetime | date: 'MMM d, yyyy h:mm a' }}
                    </span>
                    @if (o.standingOrder) {
                      <span class="standing-chip">Standing Order</span>
                    }
                  </div>
                  @if (o.clinicalIndication) {
                    <p class="indication">Indication: {{ o.clinicalIndication }}</p>
                  }
                  @if (o.notes) {
                    <p class="notes-text">{{ o.notes }}</p>
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
      }
      .status-badge,
      .priority-chip,
      .date-chip,
      .standing-chip {
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
      .standing-chip {
        background: #ede9fe;
        color: #7c3aed;
      }
      .indication {
        font-size: 13px;
        color: #475569;
        margin: 8px 0 0;
      }
      .notes-text {
        font-size: 13px;
        color: #64748b;
        margin: 6px 0 0;
        font-style: italic;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
})
export class MyLabOrdersComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  orders = signal<LabOrderDTO[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyLabOrders().subscribe({
      next: (data) => {
        this.orders.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusColorClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED' || s === 'RESULTED') return 'green';
    if (s === 'CANCELLED' || s === 'REJECTED') return 'red';
    if (s === 'IN_PROGRESS' || s === 'SPECIMEN_COLLECTED') return 'amber';
    return '';
  }

  priorityColorClass(priority: string): string {
    const p = priority?.toUpperCase();
    if (p === 'STAT' || p === 'URGENT') return 'red';
    if (p === 'ROUTINE') return 'green';
    return '';
  }
}
