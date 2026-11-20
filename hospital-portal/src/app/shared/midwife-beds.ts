import { BedResponse } from '../services/bed.service';

/**
 * Policy rule 2 (owner 2026-11-20, PROVISIONAL until a named sage-femme or obstetrician signs it
 * off): a midwife assigns the beds and cots of maternity wards, and a neonatal (NICU) cot for a
 * newborn stay, where a physician is then asked to review. The server enforces it
 * (`MidwifeNewbornCare`); a bed picker offers her those beds only, so she is not offered a bed
 * the server would refuse.
 */
export const MIDWIFE_ONLY_ROLES: readonly string[] = ['ROLE_MIDWIFE'];
/** Holding one of these as well lifts the narrowing: a nurse or a physician assigns any ward's bed. */
export const WARD_WIDE_BED_ROLES: readonly string[] = [
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_SUPER_ADMIN',
];

/** The beds a caller who is only a midwife may assign for this stay; a bed with no ward type is kept. */
export function bedsAMidwifeAssigns(
  beds: readonly BedResponse[],
  newbornStay: boolean,
): BedResponse[] {
  return beds.filter(
    (bed) =>
      !bed.wardType || bed.wardType === 'MATERNITY' || (newbornStay && bed.wardType === 'NICU'),
  );
}

/** A neonatal cot: assigning it as a midwife files a review task for the hospital's physicians. */
export function isNeonatalCot(bed: BedResponse | null | undefined): boolean {
  return bed?.wardType === 'NICU';
}
