/**
 * QA D1003 (round 17): the Burkina Faso EPI (PEV) vaccines offered by name in
 * the immunisation form, and the CDC CVX code each one fills in.
 *
 * The CVX code is OPTIONAL (CVX is a US code system). Choosing a PEV vaccine
 * fills its code from this table; the field stays editable, and a vaccine
 * with no verified code is left empty.
 *
 * VERIFICATION. Every code below was checked on 2026-10-30 against the CDC
 * CVX table (https://www2a.cdc.gov/vaccines/iis/iisstandards/vaccines.asp?rpt=cvx,
 * downloaded and parsed that day). Each entry records the CDC short
 * description and status as published. The candidate list came from
 * C:/dev/hms-qa/reference-values-general-2026-10-30.md section 5; where the
 * CDC table showed a candidate to be wrong, the verified code replaced it
 * and the reason is written beside it. These are defaults: the hospital's
 * EPI focal point may change a code on a dose, never in this table without
 * re-checking the CDC table.
 */
export interface PevCvxEntry {
  /** CVX code exactly as the CDC table lists it. */
  readonly cvx: string;
  /** CDC short description, as published on the verification date. */
  readonly cdcShortDescription: string;
  /** CDC vaccine status on the verification date. */
  readonly cdcStatus: 'Active' | 'Inactive' | 'Non-US';
  /** Why this code, when it is not the obvious one. */
  readonly note?: string;
}

/** Date the codes below were checked against the CDC CVX table. */
export const PEV_CVX_VERIFIED_ON = '2026-10-30';

/** The PEV vaccines the form offers, in the order the PEV schedule gives them. */
export const PEV_VACCINES = [
  'BCG',
  'HEPB0',
  'VPO',
  'VPI',
  'PENTA',
  'PCV',
  'ROTA',
  'RR',
  'VAA',
  'MENA',
  'TD',
  'HPV',
  'MALARIA',
] as const;

export type PevVaccine = (typeof PEV_VACCINES)[number];

/** PEV vaccine → verified CVX code. */
export const PEV_CVX: Readonly<Partial<Record<PevVaccine, PevCvxEntry>>> = {
  BCG: { cvx: '19', cdcShortDescription: 'BCG', cdcStatus: 'Active' },
  HEPB0: {
    cvx: '08',
    cdcShortDescription: 'Hep B, adolescent or pediatric',
    cdcStatus: 'Active',
    note: 'Birth dose: the paediatric hepatitis B formulation.',
  },
  VPO: {
    cvx: '178',
    cdcShortDescription: 'OPV bivalent',
    cdcStatus: 'Non-US',
    note: 'bOPV (types 1 and 3), used by the PEV since the 2016 switch. A pre-2016 trivalent dose is CVX 02: edit the code on that dose.',
  },
  VPI: { cvx: '10', cdcShortDescription: 'IPV', cdcStatus: 'Active' },
  PENTA: {
    cvx: '198',
    cdcShortDescription: 'DTP-hepB-Hib Pentavalent Non-US',
    cdcStatus: 'Non-US',
    note: 'Candidate 102 rejected: the CDC table says that code is a non-US DTP-Hib-HepB product withdrawn in 2007. 198 is the non-US pentavalent the PEV gives.',
  },
  PCV: {
    cvx: '133',
    cdcShortDescription: 'Pneumococcal conjugate PCV 13',
    cdcStatus: 'Active',
    note: 'The PEV gives PCV13. CVX 152 (unspecified) is, per CDC, for historical records only. Edit the code if another valency was given.',
  },
  ROTA: {
    cvx: '122',
    cdcShortDescription: 'rotavirus, unspecified formulation',
    cdcStatus: 'Inactive',
    note: 'The form does not say which product (monovalent 119, pentavalent 116, or a non-US vaccine without its own code), so the unspecified code. Edit it when the product is known.',
  },
  RR: {
    cvx: '04',
    cdcShortDescription: 'M/R',
    cdcStatus: 'Inactive',
    note: 'Measles and rubella. Inactive in the US only because the US gives MMR (03).',
  },
  VAA: {
    cvx: '184',
    cdcShortDescription: 'Yellow fever, unspecified',
    cdcStatus: 'Inactive',
    note: 'Candidate 37 rejected: it is the US YF-VAX product (stabilised with sorbitol and gelatin), not the vaccine the PEV gives.',
  },
  TD: {
    cvx: '139',
    cdcShortDescription: 'Td(adult) unspecified formulation',
    cdcStatus: 'Inactive',
    note: 'CVX 138 is a non-adsorbed Td; the formulation given is not recorded, so the unspecified code.',
  },
  HPV: {
    cvx: '137',
    cdcShortDescription: 'HPV, unspecified formulation',
    cdcStatus: 'Inactive',
    note: 'The valency is not recorded (HPV9 is 165).',
  },
};

/**
 * PEV vaccines deliberately left without a code, and why. The guard in
 * pev-cvx.spec.ts fails when a PEV vaccine is in neither table.
 */
export const PEV_UNMAPPED: Readonly<Partial<Record<PevVaccine, string>>> = {
  MENA: 'No CVX entry for the MenA conjugate vaccine (MenAfriVac). CVX 191 is meningococcal A POLYSACCHARIDE (non-US), a different vaccine.',
  MALARIA:
    'CVX 67 "malaria vaccine" is listed with status "Never Active" (never licensed when it was added); it names neither RTS,S nor R21, so it is not used.',
};

/** The verified CVX code of a PEV vaccine, or undefined when it has none. */
export function pevCvx(vaccine: string | null | undefined): string | undefined {
  if (!vaccine) return undefined;
  return PEV_CVX[vaccine as PevVaccine]?.cvx;
}
