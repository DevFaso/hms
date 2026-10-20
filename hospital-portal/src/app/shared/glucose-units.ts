/**
 * QA D530: the laboratory reports glucose in mmol/L; the vitals modal asked
 * for mg/dL, so a nurse copying "5,6" from a lab slip recorded 5.6 mg/dL.
 * The bedside entry is in mmol/L like the lab; the API field stays mg/dL.
 */
export const GLUCOSE_MG_DL_PER_MMOL_L = 18.016;

/** mmol/L → whole mg/dL (the API's integer field). */
export function glucoseMmolToMgDl(mmol: number): number {
  return Math.round(mmol * GLUCOSE_MG_DL_PER_MMOL_L);
}

/** mg/dL → mmol/L, one decimal. */
export function glucoseMgDlToMmol(mgDl: number): number {
  return Math.round((mgDl / GLUCOSE_MG_DL_PER_MMOL_L) * 10) / 10;
}
