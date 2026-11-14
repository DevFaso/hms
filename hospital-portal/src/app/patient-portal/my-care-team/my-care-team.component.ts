import { refusalReason } from '../../shared/refusal-reason';
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
  VisitClinicianEntry,
} from '../../services/patient-portal.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

/**
 * The patient's care team, as GET /me/patient/care-team defines it: the
 * current primary care providers (one per hospital) and the earlier ones, with dates. The page
 * used to read a `members` array the endpoint never sends, so it always
 * rendered its empty state.
 */
@Component({
  selector: 'app-my-care-team',
  standalone: true,
  imports: [ServerTimePipe, DatePipe, TranslateModule],
  templateUrl: './my-care-team.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./my-care-team.component.scss', '../patient-portal-pages.scss'],
})
export class MyCareTeamComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly team = signal<CareTeamDTO | null>(null);
  loading = signal(true);
  failed = signal(false);
  /** QA D1212: the server's reason the read failed (a 4xx), when it gave one. */
  failureReason = signal<string | null>(null);

  /** Every current primary-care provider, one per hospital (QA D1850). */
  readonly currents = computed<PrimaryCareEntry[]>(() => {
    const team = this.team();
    // An older server sends only `primaryCare`.
    return team?.primaryCares ?? (team?.primaryCare ? [team.primaryCare] : []);
  });
  /** Earlier links only: the history also carries the current ones. */
  readonly previous = computed<PrimaryCareEntry[]>(() => {
    const currentIds = new Set(this.currents().map((c) => c.id));
    return (this.team()?.primaryCareHistory ?? []).filter(
      (e) => !e.current && !currentIds.has(e.id),
    );
  });
  /** The clinicians of the patient's visits still open (QA D115). */
  readonly visitClinicians = computed<VisitClinicianEntry[]>(
    () => this.team()?.visitClinicians ?? [],
  );
  readonly empty = computed(
    () =>
      this.currents().length === 0 &&
      this.previous().length === 0 &&
      this.visitClinicians().length === 0,
  );

  ngOnInit(): void {
    this.portal.getMyCareTeam().subscribe({
      next: (team) => {
        this.team.set(team);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.failureReason.set(refusalReason(err));
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }
}
