import { isRecordedMale } from '../patient-measures';

/**
 * QA r81 D4116: the maternity pickers (labour, postpartum, prenatal, birth plan, obstetric
 * ultrasound, maternal history) offered « Bébé TestTravail » as a labour patient. A patient
 * recorded male, or born less than a year ago, is not offered there. Display only: the server
 * still decides; a patient whose sex or birth date is not recorded stays offered.
 */
export function isMaternalCandidate(
  patient: { gender?: string | null; dateOfBirth?: string | null },
  today: Date = new Date(),
): boolean {
  if (isRecordedMale(patient.gender)) return false;
  const dob = parseIsoDate(patient.dateOfBirth);
  if (!dob) return true;
  const firstBirthday = new Date(dob.getFullYear() + 1, dob.getMonth(), dob.getDate());
  return firstBirthday.getTime() <= startOfDay(today).getTime();
}

function parseIsoDate(value: string | null | undefined): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value ?? '');
  if (!match) return null;
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
}

function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}
