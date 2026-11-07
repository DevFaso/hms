import { moreCount } from './more-count';

describe('moreCount (QA D1362: "+ N more" is the server total less the rows shown)', () => {
  it('counts from the server total, not from the rows loaded', () => {
    // A2: 125 current results, 4 on the card.
    expect(moreCount(125, 4)).toBe(121);
  });

  it('is 0 when everything is shown or the total is unknown', () => {
    expect(moreCount(3, 3)).toBe(0);
    expect(moreCount(2, 4)).toBe(0);
    expect(moreCount(null, 4)).toBe(0);
    expect(moreCount(undefined, 4)).toBe(0);
    expect(moreCount(Number.NaN, 4)).toBe(0);
  });
});
