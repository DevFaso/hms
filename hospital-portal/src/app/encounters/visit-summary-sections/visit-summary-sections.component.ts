import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { AfterVisitSummary, AvsTestResult } from '../../services/encounter.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { MeasurePipe } from '../../shared/pipes/measure.pipe';
import { LabRangePipe, LabValuePipe } from '../../shared/pipes/lab-value.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { LabCorrectionBadgeComponent } from '../../lab/shared/lab-correction-badge.component';

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

const PROBLEM_STATUS_KEYS: Record<string, string> = {
  ACTIVE: 'VISIT_SUMMARY.PROBLEM_ACTIVE',
  RECURRENCE: 'VISIT_SUMMARY.PROBLEM_RECURRENCE',
  RESOLVED: 'VISIT_SUMMARY.PROBLEM_RESOLVED',
  INACTIVE: 'VISIT_SUMMARY.PROBLEM_INACTIVE',
};

/**
 * User decision 2026-11-12: the after-visit summary's content sections —
 * whom to call, the visit's vital signs, the tests ordered (a result only
 * once released), the allergies, the medicines in force, the clinician's
 * assessment and plan, and the patient's recorded history. Each section is
 * hidden when it has nothing; the backend decides what may be shown (the desk
 * receives none of it). Shared by the staff dialog and the patient portal.
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
  readonly summary = input.required<Partial<ContentSections>>();

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

  /** A measure was recorded (zero included). */
  present(value: number | null | undefined): boolean {
    return value !== null && value !== undefined;
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
