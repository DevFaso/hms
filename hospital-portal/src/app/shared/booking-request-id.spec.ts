import { createBookingRequestId } from './booking-request-id';

describe('booking request identity', () => {
  it('uses the browser UUID implementation when present', () => {
    const source = { randomUUID: () => '12345678-1234-4234-8234-123456789012' } as unknown as Crypto;
    expect(createBookingRequestId(source)).toBe('12345678-1234-4234-8234-123456789012');
  });

  it('makes a version 4 UUID from secure random bytes when randomUUID is absent', () => {
    const source = {
      getRandomValues: (bytes: Uint8Array) => bytes.fill(255),
    } as unknown as Crypto;
    expect(createBookingRequestId(source)).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff');
  });

  it('keeps the optional legacy API usable if secure random generation is unavailable', () => {
    expect(createBookingRequestId({} as Crypto)).toBeUndefined();
  });
});
