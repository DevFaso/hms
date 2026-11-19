/**
 * One addendum to a note, as every reader DTO carries it: the chart review's
 * `NoteAddendumEntryDTO`, the visit's `EncounterNoteAddendumResponseDTO`, a
 * nursing note's addendum and a note-history snapshot all share these fields.
 */
export interface NoteAddendumView {
  id?: string | null;
  content?: string | null;
  authorName?: string | null;
  authorCredentials?: string | null;
  documentedAt?: string | null;
  signedAt?: string | null;
  createdAt?: string | null;
}

/** When the addendum is dated: its signature, else when it was documented or saved. */
export function addendumWhen(a: NoteAddendumView): string | null {
  return a.signedAt || a.documentedAt || a.createdAt || null;
}
