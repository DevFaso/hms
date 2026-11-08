/**
 * Hand-written patient pickers (QA D947; the class behind D943/D942 at the
 * screen level: each copy has its own keyboard, accent and phone behaviour).
 *
 * The portal has ONE patient picker, `<app-patient-picker>`
 * (shared/patient-picker): typeahead, keyboard selection, MRN/phone matching,
 * hospital scope. A page that writes its own — a search `<input>` plus a list
 * of suggestions each `(mousedown)`/`(click)` selecting a patient — gets the
 * behaviour wrong in a new way every time (D947: ArrowDown and Enter select
 * nothing, Tab closes the list).
 *
 * A template is a hand-written picker when it holds BOTH
 *
 *  - a search field bound to a patient query: an `<input>` whose binding or
 *    event names `patientSearch*`, `patientQuery`, `searchPatient*`,
 *    `patientTerm`, `patientFilter`, `patientLookup` (case-insensitive), and
 *  - a suggestion list: an `@for`/`*ngFor` over `patientSuggestions`,
 *    `patientResults`, `patientMatches`, `patientCandidates`,
 *    `patientOptions`, `patientHits`, `filteredPatients`, `matchingPatients`,
 *    `foundPatients`, `patientSearchResults` whose items are chosen with a
 *    `select|pick|choose|apply|setPatient(...)` handler.
 *
 * The anchor is the list's iterable, so a pin outlives line moves and dies
 * with the list. shared/patient-picker itself is skipped.
 */
import { tags, lineOf } from './html-tags.mjs';

export const PICKER_COMPONENT = 'src/app/shared/patient-picker/';

const QUERY_BINDING =
  /patient(?:Search|Query|Term|Filter|Lookup)\w*|searchPatient\w*|patientSearch\w*/i;

const SUGGESTION_LIST = new RegExp(
  String.raw`(?:@for\s*\(\s*\w+\s+of\s+|\*ngFor="\s*let\s+\w+\s+of\s+)` +
    String.raw`((?:[\w$.()?!]*\.)?(?:patient(?:Suggestions|Results|Matches|Candidates|Options|Hits|SearchResults)|` +
    String.raw`(?:filtered|matching|found|suggested)Patients)\b[\w$.()?!]*)`,
  'gi',
);

const SELECT_HANDLER =
  /\((?:click|mousedown|keydown\.enter|keyup\.enter)\)="\s*(?:select|pick|choose|apply|set|on|use)\w*[pP]atient\w*\(/;

function hasPatientQueryInput(html) {
  for (const tag of tags(html)) {
    if (tag.type !== 'open' || tag.name !== 'input') continue;
    for (const [key, value] of tag.attrs) {
      if (QUERY_BINDING.test(key) || (value && QUERY_BINDING.test(value))) return true;
    }
  }
  return false;
}

/**
 * @param {string} html
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function pickerFindings(html) {
  if (!hasPatientQueryInput(html)) return [];
  const out = [];
  for (const m of html.matchAll(SUGGESTION_LIST)) {
    // The list's items: up to the next closing of the block is too much
    // parsing; the select handler is within the next 1500 characters of any
    // real suggestion list, and a worklist row's `(click)="openPatient()"`
    // does not match the handler shape.
    const window = html.slice(m.index, m.index + 1500);
    if (!SELECT_HANDLER.test(window)) continue;
    out.push({
      line: lineOf(html, m.index),
      rule: 'hand-written-picker',
      anchor: m[1],
      message: `patient search input + suggestion list over ${m[1]}: use <app-patient-picker> (shared/patient-picker)`,
    });
  }
  return out;
}

/** Whether the template already renders the shared picker (reported for context). */
export function usesSharedPicker(html) {
  return /<app-patient-picker\b/.test(html);
}
