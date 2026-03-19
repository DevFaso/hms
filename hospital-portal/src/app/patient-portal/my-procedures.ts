import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, ProcedureOrderDTO } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-procedures',
  standalone: true,
  imports: [CommonModule, DatePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">surgical</span>
          My Procedures
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading procedure orders...</p>
        </div>
      } @else if (orders().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">surgical</span>
          <h3>No procedure orders found</h3>
          <p>You have no surgical or procedural orders on record.</p>
        </div>
      } @else {
        <section class="portal-section">
          <p class="count-label">{{ orders().length }} procedure order(s) on record</p>
          <div class="orders-list">
            @for (o of orders(); track o.id) {
              <div class="order-card">
                <div class="order-icon" [ngClass]="urgencyColorClass(o.urgency)">
                  <span class="material-symbols-outlined">surgical</span>
                </div>
                <div class="order-body">
                  <h4 class="order-name">{{ o.procedureName }}</h4>
                  <p class="order-sub">
                    {{ o.procedureCode ? o.procedureCode + ' · ' : ''
                    }}{{ o.procedureCategory || '' }}
                    {{ o.procedureCategory || o.procedureCode ? '· ' : '' }}{{ o.hospitalName }}
                  </p>
                  <div class="meta-row">
                    <span class="status-badge" [ngClass]="statusColorClass(o.status)">
                      {{ o.status | titlecase }}
                    </span>
                    <span class="urgency-chip" [ngClass]="urgencyColorClass(o.urgency)">
                      {{ o.urgency | titlecase }}
                    </span>
                    <span class="date-chip">
                      <span class="material-symbols-outlined icon-sm">schedule</span>
                      Ordered {{ o.orderedAt | date: 'MMM d, yyyy' }}
                    </span>
                    @if (o.scheduledDatetime) {
                      <span class="scheduled-chip">
                        <span class="material-symbols-outlined icon-sm">event</span>
                        Scheduled {{ o.scheduledDatetime | date: 'MMM d, yyyy' }}
                      </span>
                    }
                  </div>
                  @if (o.requiresAnesthesia || o.requiresSedation) {
                    <div class="flags-row">
                      @if (o.requiresAnesthesia) {
                        <span class="flag-chip anesthesia">
                          Anesthesia{{ o.anesthesiaType ? ': ' + o.anesthesiaType : '' }}
                        </span>
                      }
                      @if (o.requiresSedation) {
                        <span class="flag-chip sedation">Sedation required</span>
                      }
                    </div>
                  }
                  @if (o.indication) {
                    <p class="indication">Indication: {{ o.indication }}</p>
                  }
                  @if (o.preProcedureInstructions) {
                    <p class="instructions">
                      <span class="material-symbols-outlined icon-sm">info</span>
                      {{ o.preProcedureInstructions }}
                    </p>
                  }
                  @if (o.orderingProviderName) {
                    <p class="provider-info">Ordered by: {{ o.orderingProviderName }}</p>
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
      .order-icon.red {
        background: #fee2e2;
        color: #dc2626;
      }
      .order-icon.amber {
        background: #fef9c3;
        color: #ca8a04;
      }
      .order-icon.green {
        background: #dcfce7;
        color: #16a34a;
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
      .urgency-chip,
      .date-chip,
      .scheduled-chip,
      .flag-chip {
        font-size: 12px;
        font-weight: 600;
        padding: 3px 10px;
        border-radius: 20px;
      }
      .status-badge {
        background: #dbeafe;
        color: #1d4ed8;
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
      .urgency-chip {
        background: #f1f5f9;
        color: #475569;
      }
      .urgency-chip.red {
        background: #fee2e2;
        color: #dc2626;
      }
      .urgency-chip.amber {
        background: #fef9c3;
        color: #a16207;
      }
      .date-chip,
      .scheduled-chip {
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
      .flags-row {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        margin-bottom: 8px;
      }
      .flag-chip.anesthesia {
        background: #fdf4ff;
        color: #9333ea;
      }
      .flag-chip.sedation {
        background: #fff7ed;
        color: #c2410c;
      }
      .indication,
      .instructions,
      .provider-info {
        font-size: 13px;
        color: #475569;
        margin: 4px 0 0;
        display: flex;
        align-items: flex-start;
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
export class MyProceduresComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  orders = signal<ProcedureOrderDTO[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyProcedureOrders().subscribe({
      next: (data) => {
        this.orders.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusColorClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'green';
    if (s === 'CANCELLED') return 'red';
    if (s === 'IN_PROGRESS' || s === 'READY_FOR_PROCEDURE' || s === 'SCHEDULED') return 'amber';
    return '';
  }

  urgencyColorClass(urgency: string): string {
    const u = urgency?.toUpperCase();
    if (u === 'STAT' || u === 'EMERGENT') return 'red';
    if (u === 'URGENT') return 'amber';
    return '';
  }
}
