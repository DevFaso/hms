import { labStockStates } from './lab-inventory';

describe('labStockStates (QA D4077)', () => {
  const today = new Date(2026, 10, 27);
  const item = (quantity: number, lowStock: boolean, expirationDate?: string, expired = false) => ({
    quantity,
    lowStock,
    expired,
    expirationDate,
  });

  it('nothing left is out of stock, not low stock', () => {
    expect(labStockStates(item(0, true), today)).toEqual(['OUT']);
    expect(labStockStates(item(3, true), today)).toEqual(['LOW']);
    expect(labStockStates(item(30, false), today)).toEqual([]);
  });

  it('a lot that expires within 14 days is announced; later it is not; past it is expired', () => {
    expect(labStockStates(item(30, false, '2026-12-10'), today)).toEqual(['EXPIRING']);
    expect(labStockStates(item(30, false, '2026-12-11'), today)).toEqual(['EXPIRING']);
    expect(labStockStates(item(30, false, '2026-12-12'), today)).toEqual([]);
    expect(labStockStates(item(30, false, '2026-11-20'), today)).toEqual(['EXPIRED']);
    expect(labStockStates(item(30, false, '2026-12-30', true), today)).toEqual(['EXPIRED']);
  });

  it('a line can be in two situations at once', () => {
    expect(labStockStates(item(0, true, '2026-12-01'), today)).toEqual(['OUT', 'EXPIRING']);
  });
});
