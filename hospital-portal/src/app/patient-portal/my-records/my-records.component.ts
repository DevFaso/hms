import {
  Component,
  OnInit,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  HealthSummaryDTO,
  PortalEncounter,
  LabResultSummary,
  MedicationSummary,
  ImmunizationSummary,
  PatientHospitalRecord,
} from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { moreCount } from '../../shared/more-count';

/** How many rows each card previews. */
export const RECORD_CARD_ROWS = 4;
/** The active-conditions card previews one more. */
export const CONDITION_CARD_ROWS = 5;

@Component({
  selector: 'app-my-records',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, DatePipe, RouterLink, EnumLabelPipe, TranslateModule],
  templateUrl: './my-records.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./my-records.component.scss', '../patient-portal-pages.scss'],
})
export class MyRecordsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  loading = signal(true);
  summary = signal<HealthSummaryDTO | null>(null);
  encounters = signal<PortalEncounter[]>([]);
  labs = signal<LabResultSummary[]>([]);
  medications = signal<MedicationSummary[]>([]);
  immunizations = signal<ImmunizationSummary[]>([]);

  /**
   * QA D841: GET /me/patient/health-summary sends every registration with its
   * MRN and no page showed one. A registration without a number is left out.
   */
  hospitalRecords = computed<PatientHospitalRecord[]>(() =>
    (this.summary()?.profile?.hospitalRecords ?? []).filter((r) => !!r.mrn),
  );

  readonly cardRows = RECORD_CARD_ROWS;
  readonly conditionRows = CONDITION_CARD_ROWS;

  /**
   * QA D1362 (round 31): each card's "+ N more" is the server's total less
   * the rows on the card - never the rows this page happened to load (the lab
   * card said "+ 46" from 50 loaded when 121 more existed). Lab results and
   * medications are counted by the server (health summary totals);
   * immunizations and conditions come back whole, so their length is the total.
   */
  moreMedications = computed(() =>
    moreCount(this.summary()?.medicationTotal, this.shown(this.medications().length)),
  );
  moreLabs = computed(() =>
    moreCount(this.summary()?.labResultTotal, this.shown(this.labs().length)),
  );
  moreConditions = computed(() => {
    const all = this.summary()?.activeDiagnoses?.length ?? 0;
    return moreCount(all, Math.min(all, CONDITION_CARD_ROWS));
  });
  moreImmunizations = computed(() =>
    moreCount(this.immunizations().length, this.shown(this.immunizations().length)),
  );

  private shown(loaded: number): number {
    return Math.min(loaded, RECORD_CARD_ROWS);
  }

  ngOnInit(): void {
    this.portal.getHealthSummary().subscribe({
      next: (s) => this.summary.set(s),
      error: () => void 0,
    });
    this.portal.getMyEncounters().subscribe({
      next: (e) => this.encounters.set(e),
      error: () => void 0,
    });
    // The card previews the newest few; how many there are in all is the
    // health summary's labResultTotal (QA D1362), so only those are read.
    this.portal.getMyLabResults(RECORD_CARD_ROWS).subscribe({
      next: (l) => this.labs.set(l),
      error: () => void 0,
    });
    this.portal.getMyMedications().subscribe({
      next: (m) => this.medications.set(m),
      error: () => void 0,
    });
    this.portal.getMyImmunizations().subscribe({
      next: (im) => {
        this.immunizations.set(im);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  printRecords(): void {
    window.print();
  }
}
