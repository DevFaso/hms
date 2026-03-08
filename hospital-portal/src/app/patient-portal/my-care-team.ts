import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PatientPortalService, CareTeamMember } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-care-team',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">groups</span>
          My Care Team
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading care team...</p>
        </div>
      } @else if (members().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">groups</span>
          <h3>No care team members</h3>
          <p>Your care team information will appear here.</p>
        </div>
      } @else {
        <section class="portal-section">
          <div class="care-team-list">
            @for (m of members(); track m.name) {
              <div class="ct-card" [class.ct-primary]="m.isPrimary">
                @if (m.isPrimary) {
                  <span class="ct-badge">Primary Care Provider</span>
                }
                <div class="ct-avatar" [style.background]="m.isPrimary ? '#dbeafe' : '#f1f5f9'">
                  <span
                    class="material-symbols-outlined"
                    [style.color]="m.isPrimary ? '#2563eb' : '#64748b'"
                  >
                    {{ m.isPrimary ? 'star' : 'person' }}
                  </span>
                </div>
                <div class="ct-info">
                  <span class="ct-name">{{ m.name }}</span>
                  <span class="ct-role">{{ m.specialty || m.role }}</span>
                  @if (m.phone) {
                    <span class="ct-contact">
                      <span class="material-symbols-outlined" style="font-size:14px">phone</span>
                      {{ m.phone }}
                    </span>
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
      .care-team-list {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
        gap: 16px;
      }
      .ct-card {
        position: relative;
        display: flex;
        align-items: flex-start;
        gap: 14px;
        padding: 20px;
        background: #fff;
        border: 1px solid #e2e8f0;
        border-radius: 14px;
        transition: box-shadow 0.2s;
      }
      .ct-card:hover {
        box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
      }
      .ct-card.ct-primary {
        border-color: #93c5fd;
        background: linear-gradient(135deg, #eff6ff 0%, #fff 100%);
      }
      .ct-badge {
        position: absolute;
        top: 10px;
        right: 12px;
        font-size: 10px;
        font-weight: 700;
        padding: 2px 8px;
        border-radius: 12px;
        background: #2563eb;
        color: #fff;
        text-transform: uppercase;
        letter-spacing: 0.3px;
      }
      .ct-avatar {
        width: 44px;
        height: 44px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }
      .ct-info {
        display: flex;
        flex-direction: column;
        gap: 3px;
      }
      .ct-name {
        font-size: 15px;
        font-weight: 700;
        color: #1e293b;
      }
      .ct-role {
        font-size: 13px;
        color: #64748b;
      }
      .ct-contact {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 12px;
        color: #94a3b8;
        margin-top: 2px;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
})
export class MyCareTeamComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  members = signal<CareTeamMember[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyCareTeam().subscribe({
      next: (team) => {
        this.members.set(team.members ?? []);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
