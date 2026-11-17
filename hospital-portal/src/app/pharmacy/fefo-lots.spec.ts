import { earlierLotWithStock, fefoAllocation, fefoFirstLot } from './fefo-lots';

/** QA D1029 (D964's class): first expired, first out, split across lots. */
describe('fefo-lots (QA D1029)', () => {
  const short = {
    id: 'L03b',
    expiryDate: '2027-03-31',
    remainingQuantity: 120,
    inventoryItemId: 'alu',
  };
  const long = {
    id: 'L03a',
    expiryDate: '2028-06-30',
    remainingQuantity: 240,
    inventoryItemId: 'alu',
  };
  const other = {
    id: 'X',
    expiryDate: '2026-12-31',
    remainingQuantity: 50,
    inventoryItemId: 'pcm',
  };

  it('takes the shortest-dated lot first, whatever the quantity (130 > 120 still starts with 03b)', () => {
    expect(fefoFirstLot([long, short])?.id).toBe('L03b');
  });

  it('splits a quantity larger than the first lot: 120 from 03b, then 10 from 03a', () => {
    expect(fefoAllocation([long, short], 130).map((p) => [p.lot.id, p.quantity])).toEqual([
      ['L03b', 120],
      ['L03a', 10],
    ]);
  });

  it('keeps one part when the first lot covers it, and none when the lots together cannot', () => {
    expect(fefoAllocation([long, short], 100).map((p) => p.lot.id)).toEqual(['L03b']);
    expect(fefoAllocation([long, short], 1000)).toEqual([]);
  });

  it('never splits onto another item, and leaves no floating residue on a decimal quantity', () => {
    expect(fefoAllocation([short, other], 130)).toEqual([]);
    const ml = [
      { id: 'a', expiryDate: '2027-01-01', remainingQuantity: 0.1 },
      { id: 'b', expiryDate: '2027-02-01', remainingQuantity: 5 },
    ];
    expect(fefoAllocation(ml, 0.3).map((p) => p.quantity)).toEqual([0.1, 0.2]);
  });

  it('a later lot needs a reason while an earlier one still holds ANY stock (it no longer has to cover the fill)', () => {
    expect(earlierLotWithStock([short, long], long)?.id).toBe('L03b');
    expect(earlierLotWithStock([short, long], short)).toBeUndefined();
    expect(earlierLotWithStock([{ ...short, remainingQuantity: 0 }, long], long)).toBeUndefined();
  });
});
