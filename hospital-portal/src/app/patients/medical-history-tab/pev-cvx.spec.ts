import { PEV_CVX, PEV_UNMAPPED, PEV_VACCINES, pevCvx } from './pev-cvx';

/**
 * QA D1003 (round 17) guard: every PEV vaccine the form offers either has a
 * CVX code checked against the CDC table (with its CDC description and
 * status recorded) or is listed as deliberately unmapped with a reason.
 * A vaccine added to the list without a decision fails here.
 */
describe('PEV to CVX table (QA D1003)', () => {
  it('every PEV vaccine is mapped or explicitly unmapped, never both', () => {
    for (const v of PEV_VACCINES) {
      const mapped = v in PEV_CVX;
      const unmapped = v in PEV_UNMAPPED;
      expect(mapped !== unmapped).withContext(v).toBeTrue();
    }
  });

  it('no table names a vaccine the form does not offer', () => {
    const offered = new Set<string>(PEV_VACCINES);
    for (const key of [...Object.keys(PEV_CVX), ...Object.keys(PEV_UNMAPPED)]) {
      expect(offered.has(key)).withContext(key).toBeTrue();
    }
  });

  it('every code carries its CDC verification record', () => {
    for (const [v, entry] of Object.entries(PEV_CVX)) {
      expect(entry!.cvx).withContext(v).toMatch(/^\d{2,3}$/);
      expect(entry!.cdcShortDescription.trim().length).withContext(v).toBeGreaterThan(0);
      expect(['Active', 'Inactive', 'Non-US']).withContext(v).toContain(entry!.cdcStatus);
    }
    for (const [v, why] of Object.entries(PEV_UNMAPPED)) {
      expect((why ?? '').trim().length).withContext(v).toBeGreaterThan(20);
    }
  });

  it('pins the verified codes, and the candidates the CDC table rejected', () => {
    expect(pevCvx('BCG')).toBe('19');
    expect(pevCvx('PENTA')).toBe('198'); // not 102: a product withdrawn in 2007
    expect(pevCvx('VAA')).toBe('184'); // not 37: the US YF-VAX product
    expect(pevCvx('MENA')).toBeUndefined();
    expect(pevCvx('MALARIA')).toBeUndefined();
    expect(pevCvx('unknown')).toBeUndefined();
    expect(pevCvx(null)).toBeUndefined();
  });
});
