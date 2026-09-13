import { Component, OnInit, inject, signal } from '@angular/core';

import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, CareTeamMember } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-care-team',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './my-care-team.component.html',
  styleUrls: ['./my-care-team.component.scss', '../patient-portal-pages.scss'],
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
