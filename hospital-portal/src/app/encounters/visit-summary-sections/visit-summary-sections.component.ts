import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import {
  AfterVisitSummary,
  AvsCurrentMedication,
  AvsTestResult,
} from '../../services/encounter.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { MeasurePipe } from '../../shared/pipes/measure.pipe';
import { LabRangePipe, LabValuePipe } from '../../shared/pipes/lab-value.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { LabCorrectionBadgeComponent } from '../../lab/shared/lab-correction-badge.component';
import { completedMonths, completedYearsAndMonths } from '../../shared/completed-age';

/** The content sections of a summary (user decision 2026-11-12), as the backend sends them. */
type ContentSections = Pick<
  AfterVisitSummary,
  | 'hospitalName'
  | 'departmentName'
  | 'hospitalPhone'
  | 'departmentPhone'
  | 'vitalSigns'
  | 'orderedTests'
  | 'allergies'
  | 'currentMedications'
  | 'clinicalAssessment'
  | 'clinicalPlan'
  | 'medicalHistory'
  | 'surgicalHistory'
  | 'familyHistory'
  | 'socialHistory'
>;

/** Whether any clinical content section has something to show (the contact block is not one). */
export function hasContentSections(s: Partial<ContentSections> | null | undefined): boolean {
  if (!s) return false;
  return !!(
    s.vitalSigns ||
    s.orderedTests?.length ||
    s.allergies?.length ||
    s.currentMedications?.length ||
    s.clinicalAssessment ||
    s.clinicalPlan ||
    s.medicalHistory?.length ||
    s.surgicalHistory?.length ||
    s.familyHistory?.length ||
    s.socialHistory
  );
}

/** Whether the whole document has any section at all, the contact band included. */
export function hasDocumentSections(s: Partial<AfterVisitSummary> | null | undefined): boolean {
  if (!s) return false;
  return !!(
    s.chiefComplaint ||
    s.dischargeDiagnoses?.length ||
    s.prescriptionSummary ||
    s.referralSummary ||
    s.followUpInstructions ||
    s.patientEducationMaterials ||
    s.followUpAppointmentDate ||
    s.hospitalPhone ||
    s.departmentPhone ||
    hasContentSections(s)
  );
}

/** Who reads the document: the duplicate-medicine warning is phrased for each. */
export type VisitSummaryAudience = 'staff' | 'patient';

/** One drug of "Traitements en cours", with every active order of it. */
export interface MedicationGroup {
  names: string[];
  lines: AvsCurrentMedication[];
}

const SEVERITY_KEYS: Record<string, string> = {
  MILD: 'VISIT_SUMMARY.SEVERITY_MILD',
  MODERATE: 'VISIT_SUMMARY.SEVERITY_MODERATE',
  SEVERE: 'VISIT_SUMMARY.SEVERITY_SEVERE',
  LIFE_THREATENING: 'VISIT_SUMMARY.SEVERITY_LIFE_THREATENING',
};

const FLAG_KEYS: Record<string, string> = {
  ABNORMAL: 'VISIT_SUMMARY.FLAG_ABNORMAL',
  ABNORMAL_LOW: 'VISIT_SUMMARY.FLAG_LOW',
  ABNORMAL_HIGH: 'VISIT_SUMMARY.FLAG_HIGH',
  CRITICAL: 'VISIT_SUMMARY.FLAG_CRITICAL',
};

/** The vital-sign rules' verdicts (VitalSignJudgement.valueFlags on the server). */
const VITAL_FLAG_KEYS: Record<string, string> = {
  LOW: 'VISIT_SUMMARY.FLAG_LOW',
  HIGH: 'VISIT_SUMMARY.FLAG_HIGH',
  ABNORMAL: 'VISIT_SUMMARY.FLAG_ABNORMAL',
};

const PROBLEM_STATUS_KEYS: Record<string, string> = {
  ACTIVE: 'VISIT_SUMMARY.PROBLEM_ACTIVE',
  RECURRENCE: 'VISIT_SUMMARY.PROBLEM_RECURRENCE',
  RESOLVED: 'VISIT_SUMMARY.PROBLEM_RESOLVED',
  INACTIVE: 'VISIT_SUMMARY.PROBLEM_INACTIVE',
};

const DAY_MS = 86_400_000;

/** "1980-05-02" or "1980-05-02T…" as the epoch day it names, with no time-zone shift. */
function calendarDay(value: string | null | undefined): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value ?? '');
  return match ? Date.UTC(+match[1], +match[2] - 1, +match[3]) / DAY_MS : null;
}

/** The reader's today, as an epoch day. */
function today(): number {
  const now = new Date();
  return Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()) / DAY_MS;
}

/** Text as a reader compares it: case, accents and spaces ignored. */
function folded(value: string): string {
  return value.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/\s+/g, '');
}

/**
 * The after-visit summary ("Résumé de visite") as one document, shared by the
 * staff dialog, the checkout preview and the patient portal (#1139/#1141).
 * Order (user review 2026-11-13): identity band, signature, motif, vital
 * signs, assessment, diagnosis(es), plan and advice, current medicines, tests
 * ordered, allergies, history, follow-up appointment, whom to contact. Each
 * section is hidden when it has nothing; the backend decides what may be
 * shown (the desk receives no clinical field).
 */
@Component({
  selector: 'app-visit-summary-sections',
  standalone: true,
  imports: [
    CommonModule,
    TranslateModule,
    EnumLabelPipe,
    MeasurePipe,
    LabValuePipe,
    LabRangePipe,
    ServerTimePipe,
    DateTimePipe,
    LabCorrectionBadgeComponent,
  ],
  templateUrl: './visit-summary-sections.component.html',
  styleUrl: './visit-summary-sections.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VisitSummarySectionsComponent {
  readonly summary = input.required<Partial<AfterVisitSummary>>();
  /** Staff or patient: only the duplicate-medicine warning's wording differs. */
  readonly audience = input<VisitSummaryAudience>('staff');

  readonly hasContact = computed(() => {
    const s = this.summary();
    return !!(s.hospitalPhone || s.departmentPhone);
  });

  readonly labTests = computed(() =>
    (this.summary().orderedTests ?? []).filter((t) => t.kind === 'LAB'),
  );
  readonly imagingTests = computed(() =>
    (this.summary().orderedTests ?? []).filter((t) => t.kind === 'IMAGING'),
  );

  readonly hasHistory = computed(() => {
    const s = this.summary();
    return !!(
      s.medicalHistory?.length ||
      s.surgicalHistory?.length ||
      s.familyHistory?.length ||
      s.socialHistory
    );
  });

  /** The identity band: one compact line of who, when and where (the name shown once). */
  readonly hasIdentity = computed(() => {
    const s = this.summary();
    return !!(
      s.patientName ||
      s.patientDateOfBirth ||
      s.patientMrn ||
      s.visitDate ||
      s.providerName ||
      s.departmentName ||
      s.hospitalName
    );
  });

  /** The signature line: a signed note, and no note of the visit still waiting for one. */
  readonly signed = computed(() => {
    const s = this.summary();
    return !s.pendingSignature && !!s.signedAt;
  });

  /** One "as entered" caption for the clinician's text, on the first of A and P shown. */
  readonly asEnteredOn = computed<'assessment' | 'plan' | null>(() => {
    const s = this.summary();
    if (s.clinicalAssessment) return 'assessment';
    return s.clinicalPlan ? 'plan' : null;
  });

  readonly hasAdvice = computed(() => {
    const s = this.summary();
    return !!(
      s.clinicalPlan ||
      s.followUpInstructions ||
      s.patientEducationMaterials ||
      s.referralSummary
    );
  });

  /**
   * User decision 2026-11-13 (option a): the active lines of one drug (the
   * server's drugKey: the catalogue item, else the normalised name) are shown
   * once, each order under it. A line with no key is a drug of its own.
   */
  readonly medicationGroups = computed<MedicationGroup[]>(() => {
    const groups = new Map<string, MedicationGroup>();
    (this.summary().currentMedications ?? []).forEach((m, i) => {
      const key = m.drugKey || `line-${i}`;
      const group = groups.get(key) ?? { names: [], lines: [] };
      if (!group.names.includes(m.name)) group.names.push(m.name);
      group.lines.push(m);
      groups.set(key, group);
    });
    return [...groups.values()];
  });

  readonly duplicateWarningKey = computed(() =>
    this.audience() === 'patient'
      ? 'VISIT_SUMMARY.DUPLICATE_MEDICINE_PATIENT'
      : 'VISIT_SUMMARY.DUPLICATE_MEDICINE_STAFF',
  );

  /** Age at the visit (else today), in completed units (QA D1087): years from 2, else months, else days. */
  readonly age = computed<{ key: string; n: number } | null>(() => {
    const s = this.summary();
    const born = calendarDay(s.patientDateOfBirth);
    if (born === null) return null;
    const at = calendarDay(s.visitDate) ?? today();
    if (at < born) return null;
    const days = at - born;
    const { years } = completedYearsAndMonths(days);
    if (years >= 2) return { key: 'VISIT_SUMMARY.AGE_YEARS', n: years };
    const months = completedMonths(days);
    if (months >= 1) return { key: 'VISIT_SUMMARY.AGE_MONTHS', n: months };
    return { key: 'VISIT_SUMMARY.AGE_DAYS', n: days };
  });

  /** A measure was recorded (zero included). */
  present(value: number | null | undefined): boolean {
    return value !== null && value !== undefined;
  }

  /** The rules' verdict on one vital value; null when no rule judged it. */
  vitalFlagKey(field: string): string | null {
    const flag = this.summary().vitalSigns?.valueFlags?.[field];
    return flag ? (VITAL_FLAG_KEYS[flag] ?? null) : null;
  }

  /** The pressure is flagged when either of its numbers is. */
  pressureFlagKey(): string | null {
    return this.vitalFlagKey('systolicBpMmHg') ?? this.vitalFlagKey('diastolicBpMmHg');
  }

  /** The dose, unless the drug's name already carries it ("Paracétamol 500 mg" + "500 mg"). */
  doseOf(m: AvsCurrentMedication): string | null {
    const dose = m.dosage?.trim();
    if (!dose) return null;
    return folded(m.name).includes(folded(dose)) ? null : dose;
  }

  severityKey(severity: string | null | undefined): string | null {
    return severity ? (SEVERITY_KEYS[severity] ?? null) : null;
  }

  flagKey(result: AvsTestResult): string | null {
    return result.abnormalFlag ? (FLAG_KEYS[result.abnormalFlag] ?? null) : null;
  }

  problemStatusKey(status: string | null | undefined): string | null {
    return status ? (PROBLEM_STATUS_KEYS[status] ?? null) : null;
  }

  yesNoKey(value: boolean | null | undefined): string | null {
    if (value === true) return 'VISIT_SUMMARY.YES';
    if (value === false) return 'VISIT_SUMMARY.NO';
    return null;
  }
}
