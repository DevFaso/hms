import { noteEntrySections, toNoteHistoryView } from './note-history-view';

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

describe('toNoteHistoryView: an addendum row (QA D2260)', () => {
  it('carries the addendum it records, the newest of the snapshot, and its author', () => {
    const view = toNoteHistoryView({
      id: 'h4',
      changedAt: '2026-11-19T04:20:00',
      changeType: 'NOTE_ADDENDUM',
      contentSnapshot: JSON.stringify({
        assessment: 'Pneumonie',
        addendaEntries: [
          { content: 'Old', authorName: 'Dr B', documentedAt: '2026-11-18T10:00:00' },
          {
            content: 'CRP à 85 mg/L',
            authorName: 'Dr A',
            documentedAt: '2026-11-19T04:20:00',
            signedAt: '2026-11-19T04:20:00',
          },
        ],
      }),
      metadataSnapshot: JSON.stringify({ authorName: 'Dr Note' }),
    });
    expect(view.addenda.map((a) => a.content)).toEqual(['CRP à 85 mg/L']);
    expect(view.authorName).toBe('Dr A');
  });

  it('has no addendum on any other row', () => {
    const view = toNoteHistoryView({
      id: 'h5',
      changedAt: '2026-11-19T04:00:00',
      changeType: 'NOTE_SIGNED',
      contentSnapshot: JSON.stringify({ addendaEntries: [{ content: 'x' }] }),
    });
    expect(view.addenda).toEqual([]);
  });
});

describe('noteEntrySections (#965 review)', () => {
  it('lists the filled sections of an entry in form order, with their labels', () => {
    const sections = noteEntrySections({
      id: 'n1',
      subjective: ' QA R13 J0 toux fébrile ',
      plan: 'Ceftriaxone IV',
      objective: '   ',
    } as never);
    expect(sections).toEqual([
      { labelKey: 'ENCOUNTERS.NOTE_FORM_SOAP_S', text: 'QA R13 J0 toux fébrile' },
      { labelKey: 'ENCOUNTERS.NOTE_FORM_SOAP_P', text: 'Ceftriaxone IV' },
    ]);
  });

  it('is empty for no entry', () => {
    expect(noteEntrySections(null)).toEqual([]);
  });
});
