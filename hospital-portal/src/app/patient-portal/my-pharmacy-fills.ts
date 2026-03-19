import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, PharmacyFillDTO } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-pharmacy-fills',
  standalone: true,
  imports: [CommonModule, DatePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">local_pharmacy</span>
          Pharmacy Fill History
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading pharmacy fill history...</p>
        </div>
      } @else if (fills().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">local_pharmacy</span>
          <h3>No pharmacy fills found</h3>
          <p>You have no medication dispense records on file.</p>
        </div>
      } @else {
        <section class="portal-section">
          <p class="count-label">{{ fills().length }} fill(s) on record</p>
          <div class="fills-list">
            @for (f of fills(); track f.id) {
              <div class="fill-card" [class.controlled]="f.controlledSubstance">
                <div class="fill-icon" [class.controlled-icon]="f.controlledSubstance">
                  <span class="material-symbols-outlined">local_pharmacy</span>
                </div>
                <div class="fill-body">
                  <div class="fill-header-row">
                    <h4 class="fill-name">{{ f.medicationName }}</h4>
                    @if (f.controlledSubstance) {
                      <span class="controlled-badge">Controlled</span>
                    }
                  </div>
                  @if (f.strength || f.dosageForm) {
                    <p class="fill-sub">{{ f.strength }}{{ f.strength && f.dosageForm ? ' · ' : '' }}{{ f.dosageForm }}</p>
                  }
                  <div class="meta-row">
                    <span class="date-chip">
                      <span class="material-symbols-outlined icon-sm">event</span>
                      Filled {{ f.fillDate | date: 'MMMM d, yyyy' }}
                    </span>
                    @if (f.daysSupply) {
                      <span class="supply-chip">{{ f.daysSupply }}-day supply</span>
                    }
                    @if (f.refillNumber !== null && f.refillNumber !== undefined) {
                      <span class="refill-chip">Refill #{{ f.refillNumber }}</span>
                    }
                    @if (f.quantityDispensed) {
                      <span class="qty-chip">Qty: {{ f.quantityDispensed }}{{ f.quantityUnit ? ' ' + f.quantityUnit : '' }}</span>
                    }
                  </div>
                  @if (f.directions) {
                    <p class="directions">{{ f.directions }}</p>
                  }
                  <div class="fill-footer">
                    @if (f.pharmacyName) {
                      <span class="pharmacy-info">
                        <span class="material-symbols-outlined icon-sm">store</span>
                        {{ f.pharmacyName }}{{ f.pharmacyPhone ? ' · ' + f.pharmacyPhone : '' }}
                      </span>
                    }
                    @if (f.prescriberName) {
                      <span class="prescriber-info">
                        <span class="material-symbols-outlined icon-sm">person</span>
                        {{ f.prescriberName }}
                      </span>
                    }
                    @if (f.expectedDepletionDate) {
                      <span class="depletion-chip">
                        <span class="material-symbols-outlined icon-sm">hourglass_bottom</span>
                        Runs out {{ f.expectedDepletionDate | date: 'MMM d' }}
                      </span>
                    }
                  </div>
                  @if (f.notes) {
                    <p class="notes-text">{{ f.notes }}</p>
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
      .fills-list {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .fill-card {
        display: flex;
        align-items: flex-start;
        gap: 14px;
        padding: 18px;
        background: #fff;
        border-radius: 14px;
        border: 1px solid #e2e8f0;
        transition: box-shadow 0.15s;
      }
      .fill-card:hover {
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
      }
      .fill-card.controlled {
        border-color: #fca5a5;
      }
      .fill-icon {
        width: 44px;
        height: 44px;
        background: #dbeafe;
        color: #2563eb;
        border-radius: 12px;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }
      .fill-icon.controlled-icon {
        background: #fee2e2;
        color: #dc2626;
      }
      .fill-body {
        flex: 1;
      }
      .fill-header-row {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 4px;
      }
      .fill-name {
        font-size: 16px;
        font-weight: 600;
        color: #1e293b;
        margin: 0;
      }
      .controlled-badge {
        font-size: 11px;
        font-weight: 700;
        padding: 2px 8px;
        border-radius: 20px;
        background: #fee2e2;
        color: #dc2626;
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }
      .fill-sub {
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
      .date-chip,
      .supply-chip,
      .refill-chip,
      .qty-chip,
      .depletion-chip {
        font-size: 12px;
        font-weight: 600;
        padding: 3px 10px;
        border-radius: 20px;
      }
      .date-chip {
        display: flex;
        align-items: center;
        gap: 4px;
        background: #f0fdf4;
        color: #15803d;
      }
      .supply-chip {
        background: #f0f9ff;
        color: #0284c7;
      }
      .refill-chip {
        background: #f8fafc;
        color: #475569;
      }
      .qty-chip {
        background: #faf5ff;
        color: #7c3aed;
      }
      .icon-sm {
        font-size: 14px;
      }
      .directions {
        font-size: 13px;
        color: #334155;
        margin: 4px 0 8px;
        font-style: italic;
      }
      .fill-footer {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 12px;
        margin-top: 6px;
      }
      .pharmacy-info,
      .prescriber-info,
      .depletion-chip {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 13px;
        color: #64748b;
      }
      .depletion-chip {
        background: #fff7ed;
        color: #c2410c;
        padding: 3px 10px;
        border-radius: 20px;
        font-weight: 600;
      }
      .notes-text {
        font-size: 13px;
        color: #64748b;
        margin: 6px 0 0;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
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
