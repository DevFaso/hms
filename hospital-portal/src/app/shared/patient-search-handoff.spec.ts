import { patientSearchState, takePatientSearch } from './patient-search-handoff';

/** QA D3750: the dashboard's header search reaches the patient list without a URL. */
describe('patient search hand-off', () => {
  function fakeHistory(state: unknown): History {
    const history = {
      state,
      replaceState(next: unknown) {
        history.state = next;
      },
    };
    return history as unknown as History;
  }

  it('carries the term in the navigation state', () => {
    expect(patientSearchState('Awa')).toEqual({ state: { patientSearch: 'Awa' } });
  });

  it('gives the carried term once, trimmed, keeping the rest of the state', () => {
    const history = fakeHistory({ navigationId: 3, patientSearch: '  Awa Kabore ' });
    expect(takePatientSearch(history)).toBe('Awa Kabore');
    expect(history.state).toEqual({ navigationId: 3, patientSearch: null });
    expect(takePatientSearch(history)).toBe('');
  });

  it('reads nothing from a state without a term', () => {
    expect(takePatientSearch(fakeHistory(null))).toBe('');
    expect(takePatientSearch(fakeHistory({ patientSearch: 42 }))).toBe('');
  });
});
