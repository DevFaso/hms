import { toNoteHistoryView } from './note-history-view';

describe('toNoteHistoryView (QA D43)', () => {
  it('reads the SOAP sections and signer out of the snapshots', () => {
    const view = toNoteHistoryView({
      id: 'h1',
      changedAt: '2026-10-17T10:00:00',
      changeType: 'NOTE_SIGNED',
      contentSnapshot: JSON.stringify({
        template: 'SOAP',
        subjective: 'Cough',
        objective: '  ',
        assessment: 'Bronchitis',
        plan: 'Fluids',
      }),
      metadataSnapshot: JSON.stringify({ authorName: 'Dr A', signedByName: 'Dr Signer' }),
    });
    expect(view.changeKey).toBe('ENCOUNTERS.NOTE_HISTORY_CHANGE.SIGNED');
    expect(view.authorName).toBe('Dr Signer');
    expect(view.sections).toEqual([
      { labelKey: 'ENCOUNTERS.NOTE_FORM_SOAP_S', text: 'Cough' },
      { labelKey: 'ENCOUNTERS.NOTE_FORM_SOAP_A', text: 'Bronchitis' },
      { labelKey: 'ENCOUNTERS.NOTE_FORM_SOAP_P', text: 'Fluids' },
    ]);
  });

  it('falls back to the author, and survives missing or malformed snapshots', () => {
    const view = toNoteHistoryView({
      id: 'h2',
      changedAt: '2026-10-17T10:00:00',
      changeType: 'SOMETHING_NEW',
      contentSnapshot: '{not json',
      metadataSnapshot: JSON.stringify({ authorName: 'Dr A' }),
    });
    expect(view.changeKey).toBeNull();
    expect(view.authorName).toBe('Dr A');
    expect(view.sections).toEqual([]);
    expect(toNoteHistoryView({ id: 'h3', changedAt: 'x' }).authorName).toBeNull();
  });
});
