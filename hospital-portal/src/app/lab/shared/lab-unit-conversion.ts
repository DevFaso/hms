import { GLUCOSE_MG_DL_PER_MMOL_L } from '../../shared/glucose-units';

/**
 * The conversions the result evolution chart may make between two units of one
 * test, mirroring the server's LabUnitConversion (QA D1092) and inventing nothing:
 *
 * - mass to mass (g/L, mg/dL, mg/L, …) and molar to molar (mmol/L, µmol/L, …)
 *   by an exact power of ten, whatever the analyte;
 * - mass to molar only for an analyte whose molar mass is in {@link MOLAR_MASS_G_PER_MOL}.
 *
 * Anything else (mEq/L, UI/L, %, 10^9/L, G/L, an analyte without a listed
 * molar mass) returns null and stays plotted on its own, in its own unit.
 * Case matters as on the server: G/L is giga per litre (a cell count), not grams.
 */

type Dimension = 'MASS' | 'MOLAR';
interface Concentration {
  dimension: Dimension;
  /** Power of ten against g/L or mol/L. */
  exponent: number;
}

const CONCENTRATION = /^([munpµμ]?)(g|mol)\s*\/\s*([dcmµμ]?)[lL]$/;
const NUMERATOR_PREFIX: Record<string, number> = {
  '': 0,
  m: -3,
  µ: -6,
  μ: -6,
  u: -6,
  n: -9,
  p: -12,
};
const VOLUME_PREFIX: Record<string, number> = { '': 0, d: -1, c: -2, m: -3, µ: -6, μ: -6 };

/**
 * Molar masses (g/mol), one entry per analyte, each with its source. Only an
 * analyte listed here is ever converted between mass and molar units.
 *
 * - GLUCOSE: C6H12O6, 180.16 g/mol (IUPAC/CIAAW standard atomic weights; the
 *   same constant as GLUCOSE_MG_DL_PER_MMOL_L = 18.016 used by the vitals forms).
 */
export const MOLAR_MASS_G_PER_MOL = {
  GLUCOSE: GLUCOSE_MG_DL_PER_MMOL_L * 10,
} as const;

export type KnownAnalyte = keyof typeof MOLAR_MASS_G_PER_MOL;

/** Name words (EN, FR, ES, accents folded) that name blood or fluid glucose. */
const GLUCOSE_WORDS = new Set([
  'GLUCOSE',
  'GLUCOSA',
  'GLYCEMIE',
  'GLYCEMIA',
  'GLICEMIA',
  'GLUCEMIA',
]);
/** Whole test codes for glucose. */
const GLUCOSE_CODES = new Set(['GLU', 'GLUC', 'GLC', 'GLUCOSE']);
/** Words that rule a test out: glycated haemoglobin is a %, not a glucose concentration. */
const NOT_GLUCOSE_WORDS = new Set([
  'HBA1C',
  'A1C',
  'GLYQUEE',
  'GLYCATED',
  'GLICADA',
  'GLICOSILADA',
  'GLYCOSYLATED',
  'HEMOGLOBINE',
  'HEMOGLOBIN',
  'HEMOGLOBINA',
  'FRUCTOSAMINE',
  'FRUCTOSAMINA',
]);

function fold(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toUpperCase().trim();
}

/** The analyte a test measures, when it is one with a listed molar mass; else null. */
export function knownAnalyte(
  testName?: string | null,
  testCode?: string | null,
): KnownAnalyte | null {
  const words = [testName, testCode]
    .filter((t): t is string => !!t?.trim())
    .flatMap((t) => fold(t).split(/[^A-Z0-9]+/))
    .filter(Boolean);
  if (words.some((w) => NOT_GLUCOSE_WORDS.has(w))) return null;
  const code = testCode ? fold(testCode) : '';
  return GLUCOSE_CODES.has(code) || words.some((w) => GLUCOSE_WORDS.has(w)) ? 'GLUCOSE' : null;
}

function parse(unit?: string | null): Concentration | null {
  const m = CONCENTRATION.exec((unit ?? '').trim());
  if (!m) return null;
  return {
    dimension: m[2] === 'g' ? 'MASS' : 'MOLAR',
    exponent: NUMERATOR_PREFIX[m[1]] - VOLUME_PREFIX[m[3]],
  };
}

export interface UnitConversion {
  /** Multiply a value in the source unit by this to get the target unit. */
  factor: number;
  /** True for a power of ten (exact); false when a molar mass was used. */
  exact: boolean;
}

/**
 * How to express a value in `from` as `to`, or null when that needs a constant
 * this table does not hold. Two spellings of one unit give factor 1.
 */
export function unitConversion(
  from?: string | null,
  to?: string | null,
  analyte: KnownAnalyte | null = null,
): UnitConversion | null {
  const source = parse(from);
  const target = parse(to);
  if (!source || !target) return null;
  const power = Math.pow(10, source.exponent - target.exponent);
  if (source.dimension === target.dimension) return { factor: power, exact: true };
  if (!analyte) return null;
  const molarMass = MOLAR_MASS_G_PER_MOL[analyte];
  // g/L ÷ g/mol = mol/L; mol/L × g/mol = g/L.
  return {
    factor: source.dimension === 'MASS' ? power / molarMass : power * molarMass,
    exact: false,
  };
}

/** `value` in the target unit, without float noise; molar-mass results keep 4 significant digits. */
export function convertValue(value: number, conversion: UnitConversion): number {
  return roundSignificant(value * conversion.factor, conversion.exact ? 12 : 4);
}

/** A number (not text: the screens format it per locale) rounded to `digits` significant digits. */
function roundSignificant(value: number, digits: number): number {
  if (!Number.isFinite(value) || value === 0) return value;
  const power = digits - 1 - Math.floor(Math.log10(Math.abs(value)));
  // Divide by a whole power of ten rather than multiply by a fraction: 10^-1 is not exact.
  return power >= 0
    ? Math.round(value * 10 ** power) / 10 ** power
    : Math.round(value / 10 ** -power) * 10 ** -power;
}
