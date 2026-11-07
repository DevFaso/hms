import { of, throwError } from 'rxjs';

import { SlotInventoryService } from '../services/slot-inventory.service';
import { isOffSlot, refusesForOverbookReason } from './published-slot';

describe('published-slot (QA D1429)', () => {
  const slots = (starts: string[]): SlotInventoryService =>
    ({
      searchAllOpen: () =>
        of({ rows: starts.map((startAt) => ({ startAt })), total: starts.length }),
    }) as unknown as SlotInventoryService;

  const offSlot = (service: SlotInventoryService, time: string): boolean => {
    let out = true;
    isOffSlot(service, 's-1', '2026-11-19', time).subscribe((off) => (out = off));
    return out;
  };

  it('a time an open slot starts at is on the slots, as HH:mm or HH:mm:ss', () => {
    const service = slots(['2026-11-19T09:00:00']);
    expect(offSlot(service, '09:00')).toBeFalse();
    expect(offSlot(service, '09:00:00')).toBeFalse();
  });

  it('any other minute, and a day with no slot, is off the slots', () => {
    expect(offSlot(slots(['2026-11-19T09:00:00']), '09:07')).toBeTrue();
    expect(offSlot(slots([]), '09:00')).toBeTrue();
  });

  it('with the clinician, the date or the time missing it asks nothing', () => {
    let out = true;
    isOffSlot(slots([]), null, '2026-11-19', '09:00').subscribe((off) => (out = off));
    expect(out).toBeFalse();
  });

  it('a failed read asks nothing: the server decides, and its refusal opens the field', () => {
    const failing = {
      searchAllOpen: () => throwError(() => new Error('403')),
    } as unknown as SlotInventoryService;
    expect(offSlot(failing, '09:00')).toBeFalse();
  });

  it('knows the refusal that asks why', () => {
    expect(refusesForOverbookReason({ error: { field: 'overbookReason' } })).toBeTrue();
    expect(refusesForOverbookReason({ error: { field: 'startTime' } })).toBeFalse();
    expect(refusesForOverbookReason(null)).toBeFalse();
  });
});
