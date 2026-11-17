import { MedicationSummary } from '../services/patient-portal.service';

/**
 * QA D316 / D720: "current" medications are the courses still running; a
 * completed or discontinued one is not. Shared by the dashboard card and the
 * "Active medications" section of the medications page (D720: the page
 * listed every course under that heading, the D316 fix covered the card only).
 */
export function currentMedications(meds: MedicationSummary[]): MedicationSummary[] {
  return meds.filter((m) => m.status === 'ACTIVE');
}
