import {
  MOLAR_MASS_G_PER_MOL,
  convertValue,
  knownAnalyte,
  unitConversion,
} from './lab-unit-conversion';

describe('lab unit conversion', () => {
  const convert = (value: number, from: string, to: string, analyte = knownAnalyte('Glucose')) => {
    const conversion = unitConversion(from, to, analyte);
    return conversion ? convertValue(value, conversion) : null;
  };

  it('converts mass concentrations by exact powers of ten, whatever the analyte', () => {
    expect(convert(0.99, 'g/L', 'mg/dL', null)).toBe(99);
    expect(convert(510, 'mg/dL', 'g/L', null)).toBe(5.1);
    expect(convert(1.2, 'g/L', 'mg/L', null)).toBe(1200);
    expect(convert(250, 'mg/L', 'mg/dL', null)).toBe(25);
    expect(convert(3, 'mmol/L', 'µmol/L', null)).toBe(3000);
    expect(unitConversion('g/L', 'mg/dL')?.exact).toBeTrue();
  });

  it('converts glucose between mmol/L and mg/dL with its molar mass, 180.16 g/mol', () => {
    expect(MOLAR_MASS_G_PER_MOL.GLUCOSE).toBeCloseTo(180.16, 10);
    expect(convert(5.5, 'mmol/L', 'mg/dL')).toBe(99.09);
    expect(convert(99.09, 'mg/dL', 'mmol/L')).toBe(5.5);
    expect(convert(1, 'g/L', 'mmol/L')).toBe(5.551);
    expect(unitConversion('mmol/L', 'g/L', 'GLUCOSE')?.exact).toBeFalse();
  });

  it('never converts mass to molar without a listed molar mass', () => {
    expect(unitConversion('mmol/L', 'mg/dL', null)).toBeNull();
    expect(knownAnalyte('Créatinine', 'CREA')).toBeNull();
    expect(knownAnalyte('Cholestérol total', 'CHOL')).toBeNull();
  });

  it('never parses a unit that is not a mass or molar concentration', () => {
    for (const unit of ['G/L', '10^9/L', '%', 'UI/L', 'mEq/L', '', 'g']) {
      expect(unitConversion(unit, 'g/L', 'GLUCOSE'))
        .withContext(unit)
        .toBeNull();
      expect(unitConversion('g/L', unit, 'GLUCOSE'))
        .withContext(unit)
        .toBeNull();
    }
  });

  it('recognises glucose by name (EN, FR, ES) or code, and not glycated haemoglobin', () => {
    expect(knownAnalyte('Glycémie à jeun')).toBe('GLUCOSE');
    expect(knownAnalyte('Fasting glucose')).toBe('GLUCOSE');
    expect(knownAnalyte('Glucemia')).toBe('GLUCOSE');
    expect(knownAnalyte('', 'GLU')).toBe('GLUCOSE');
    expect(knownAnalyte('Hémoglobine glyquée (HbA1c)')).toBeNull();
    expect(knownAnalyte('HbA1c', 'GLU-A1C')).toBeNull();
    expect(knownAnalyte(null, null)).toBeNull();
  });
});
