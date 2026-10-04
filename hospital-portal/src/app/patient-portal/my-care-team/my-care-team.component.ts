import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import {
  CareTeamDTO,
  PatientPortalService,
  PrimaryCareEntry,
} from '../../services/patient-portal.service';

/**
 * The patient's care team, as GET /me/patient/care-team defines it: the
 * current primary care provider and the earlier ones, with dates. The page
 * used to read a `members` array the endpoint never sends, so it always
 * rendered its empty state.
 */
@Component({
  selector: 'app-my-care-team',
  standalone: true,
  imports: [DatePipe, TranslateModule],
  templateUrl: './my-care-team.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./my-care-team.component.scss', '../patient-portal-pages.scss'],
})
export class MyCareTeamComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly team = signal<CareTeamDTO | null>(null);
  loading = signal(true);
  failed = signal(false);

  readonly current = computed(() => this.team()?.primaryCare ?? null);
  /** Earlier links only: the history also carries the current one. */
  readonly previous = computed<PrimaryCareEntry[]>(() => {
    const current = this.current();
    return (this.team()?.primaryCareHistory ?? []).filter(
      (e) => !e.current && e.id !== current?.id,
    );
  });
  readonly empty = computed(() => !this.current() && this.previous().length === 0);

  ngOnInit(): void {
    this.portal.getMyCareTeam().subscribe({
      next: (team) => {
        this.team.set(team);
        this.loading.set(false);
      },
      error: () => {
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }
}
