import { EncounterNoteHistoryEntry, EncounterNoteResponse } from '../services/encounter.service';
import { NoteAddendumView, addendumWhen } from '../shared/note-addenda/note-addendum-view';

/** A note-history row as the encounter panel renders it. */
export interface NoteHistoryView {
  id: string;
  /** i18n key of the change ("Signed", "Updated", …), or null for an unknown type. */
  changeKey: string | null;
  changeType: string | null;
  changedAt: string;
  authorName: string | null;
  sections: { labelKey: string; text: string }[];
  /** QA D2260: on an addendum row, the addendum it records (the newest of the snapshot's). */
  addenda: NoteAddendumView[];
}

/** Snapshot field → the label the note form uses for that section, in form order. */
const SECTION_LABELS: readonly (readonly [string, string])[] = [
  ['chiefComplaint', 'ENCOUNTERS.CHIEF_COMPLAINT'],
  ['historyOfPresentIllness', 'ENCOUNTERS.NOTE_FORM_HPI'],
  ['reviewOfSystems', 'ENCOUNTERS.NOTE_FORM_ROS'],
  ['physicalExam', 'ENCOUNTERS.NOTE_FORM_EXAM'],
  ['diagnosticResults', 'ENCOUNTERS.NOTE_FORM_DIAG'],
  ['subjective', 'ENCOUNTERS.NOTE_FORM_SOAP_S'],
  ['objective', 'ENCOUNTERS.NOTE_FORM_SOAP_O'],
  ['assessment', 'ENCOUNTERS.NOTE_FORM_SOAP_A'],
  ['plan', 'ENCOUNTERS.NOTE_FORM_SOAP_P'],
  ['implementation', 'ENCOUNTERS.NOTE_FORM_SOAP_I'],
  ['evaluation', 'ENCOUNTERS.NOTE_FORM_SOAP_E'],
  ['patientInstructions', 'ENCOUNTERS.NOTE_FORM_PATIENT_INSTRUCTIONS'],
];

const CHANGE_KEYS: Record<string, string> = {
  NOTE_CREATED: 'ENCOUNTERS.NOTE_HISTORY_CHANGE.CREATED',
  NOTE_UPDATED: 'ENCOUNTERS.NOTE_HISTORY_CHANGE.UPDATED',
  NOTE_SIGNED: 'ENCOUNTERS.NOTE_HISTORY_CHANGE.SIGNED',
  NOTE_COSIGNED: 'ENCOUNTERS.NOTE_HISTORY_CHANGE.COSIGNED',
  NOTE_ADDENDUM: 'ENCOUNTERS.NOTE_HISTORY_CHANGE.ADDENDUM',
};

function parseSnapshot(json: string | null | undefined): Record<string, unknown> {
  if (!json) return {};
  try {
    const parsed: unknown = JSON.parse(json);
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

/**
 * Reads the content and metadata snapshots of a history row. The server stores
 * them as JSON strings; the panel used to read note fields (content,
 * authorName, createdAt) that a history row does not have, so every entry
 * rendered blank (QA D43).
 */
export function toNoteHistoryView(entry: EncounterNoteHistoryEntry): NoteHistoryView {
  const content = parseSnapshot(entry.contentSnapshot);
  const metadata = parseSnapshot(entry.metadataSnapshot);
  const sections: { labelKey: string; text: string }[] = [];
  for (const [field, labelKey] of SECTION_LABELS) {
    const value = text(content[field]);
    if (value) sections.push({ labelKey, text: value });
  }
  const changeType = entry.changeType ?? null;
  const signer = changeType === 'NOTE_SIGNED' ? text(metadata['signedByName']) : null;
  const added = changeType === 'NOTE_ADDENDUM' ? newestAddendum(content['addendaEntries']) : null;
  return {
    id: entry.id,
    changeKey: changeType ? (CHANGE_KEYS[changeType] ?? null) : null,
    changeType,
    changedAt: entry.changedAt,
    authorName: text(added?.authorName) ?? signer ?? text(metadata['authorName']),
    sections,
    addenda: added ? [added] : [],
  };
}

/**
 * QA D2260 (r63): the history row of an addendum showed its header and the
 * unchanged note, never the addendum's text. The snapshot carries every
 * addendum so far (`addendaEntries`); the row records the newest.
 */
function newestAddendum(entries: unknown): NoteAddendumView | null {
  if (!Array.isArray(entries)) return null;
  const views: NoteAddendumView[] = entries
    .filter((e): e is Record<string, unknown> => !!e && typeof e === 'object')
    .map((e) => ({
      content: text(e['content']),
      authorName: text(e['authorName']),
      authorCredentials: text(e['authorCredentials']),
      documentedAt: text(e['documentedAt']),
      signedAt: text(e['signedAt']),
    }))
    .filter((entry) => !!entry.content);
  if (views.length === 0) return null;
  return views.reduce((newest, v) =>
    (addendumWhen(v) ?? '') >= (addendumWhen(newest) ?? '') ? v : newest,
  );
}

/**
 * The sections of a note entry, labelled as the note form labels them, so a
 * clinician reads the entry before signing or co-signing it (#965 review: an
 * earlier entry of a stay was signable without its text on screen).
 */
export function noteEntrySections(
  note: EncounterNoteResponse | null | undefined,
): { labelKey: string; text: string }[] {
  if (!note) return [];
  const fields = note as unknown as Record<string, unknown>;
  const sections: { labelKey: string; text: string }[] = [];
  for (const [field, labelKey] of SECTION_LABELS) {
    const value = text(fields[field]);
    if (value) sections.push({ labelKey, text: value });
  }
  return sections;
}
