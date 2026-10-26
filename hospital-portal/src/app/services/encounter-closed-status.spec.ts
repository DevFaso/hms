import { CLOSED_ENCOUNTER_STATUSES, isClosedEncounterStatus } from './encounter.service';

/**
 * Round 13: "left without being seen" is its own closed encounter status. The
 * open-visit filters (nurse station triage/intake lookup, the prescription
 * form's encounter picker) read this one list, so a walked-out visit is never
 * offered as the open visit a new order, triage or intake attaches to.
 */
describe('closed encounter statuses', () => {
  it('lists every closed status the server has, LEFT_WITHOUT_BEING_SEEN included', () => {
    expect([...CLOSED_ENCOUNTER_STATUSES].sort()).toEqual(
      ['CANCELLED', 'COMPLETED', 'LEFT_WITHOUT_BEING_SEEN'].sort(),
    );
  });

  it('treats a visit left without being seen as closed, and an open one as open', () => {
    expect(isClosedEncounterStatus('LEFT_WITHOUT_BEING_SEEN')).toBeTrue();
    expect(isClosedEncounterStatus('CANCELLED')).toBeTrue();
    expect(isClosedEncounterStatus('COMPLETED')).toBeTrue();
    for (const open of [
      'SCHEDULED',
      'ARRIVED',
      'TRIAGE',
      'WAITING_FOR_PHYSICIAN',
      'IN_PROGRESS',
      'AWAITING_RESULTS',
      'READY_FOR_DISCHARGE',
    ]) {
      expect(isClosedEncounterStatus(open)).withContext(open).toBeFalse();
    }
    expect(isClosedEncounterStatus(null)).toBeFalse();
    expect(isClosedEncounterStatus(undefined)).toBeFalse();
  });
});
