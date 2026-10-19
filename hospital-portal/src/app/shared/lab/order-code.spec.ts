import { shortOrderCode } from './order-code';

describe('shortOrderCode (QA D291)', () => {
  it('shows the order number as it is', () => {
    expect(shortOrderCode('LAB-000123')).toBe('LAB-000123');
  });

  it('shortens an id when there is no number', () => {
    expect(shortOrderCode('5a58b1cc-1234-4abc-8def-0123456789ab')).toBe('#5A58B1CC');
  });

  it('shows a dash for nothing', () => {
    expect(shortOrderCode(null)).toBe('—');
    expect(shortOrderCode('  ')).toBe('—');
  });
});
