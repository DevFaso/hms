/**
 * People filtered by hand (QA D1019, D1020; D942/D943 as the list saw them).
 *
 * A page that filters the people it already holds with
 * `name.toLowerCase().includes(term)` misses accents ("Kiemde" never finds
 * "Kiemdé"), full names ("Pélagie Kiemdé" matches neither part) and every
 * written phone format ("65 11 22 33" never finds "+22665112233"). The
 * server's patient search folds all three (PatientMatchKeys); the pages must
 * fold the same way, through src/app/shared/person-search.ts.
 *
 *  raw-name   — a person-name field (`patientName`, `patientFullName`,
 *               `firstName`, `lastName`, `staffName`, …) compared with
 *               `.toLowerCase().includes(`, directly, through `?.`, or as
 *               `(x ?? '').toLowerCase().includes(`.
 *  raw-phone  — a phone field compared with `.includes(` on its raw text.
 */
import { lineOf } from './html-tags.mjs';

const NAME_FIELD = String.raw`[\w$.?!]*?(?:[pP]atient\w*Name|\w*(?:[fF]irst|[lL]ast|[mM]iddle|[fF]ull|[sS]taff|[pP]erson|[cC]onsultant|[sS]ignedBy|[uU]ser|[pP]rescriber|[dD]octor|[nN]urse|[pP]rovider|[cC]linician|[aA]uthor|[gG]uardian)\w*Name)`;
const RAW_NAME = new RegExp(
  String.raw`\(?\s*(${NAME_FIELD})\s*(?:\?\?\s*(?:''|"")\s*\))?\s*[?!]?\.toLowerCase\(\)\s*[?!]?\.includes\(`,
  'g',
);
const RAW_PHONE = new RegExp(
  String.raw`([\w$.?!]*?[pP]hone\w*)\s*[?!]?\.includes\(`,
  'g',
);

/**
 * @param {string} text a component source
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function personSearchFindings(text) {
  const out = [];
  for (const m of text.matchAll(RAW_NAME)) {
    out.push({
      line: lineOf(text, m.index),
      rule: 'raw-name',
      anchor: m[1],
      message: `${m[1]} compared with .toLowerCase().includes(): accents and full names are missed — use textMatches / personNameMatches (shared/person-search)`,
    });
  }
  for (const m of text.matchAll(RAW_PHONE)) {
    out.push({
      line: lineOf(text, m.index),
      rule: 'raw-phone',
      anchor: m[1],
      message: `${m[1]}.includes(): a written number ("65 11 22 33") is missed — use phoneMatches (shared/person-search)`,
    });
  }
  return out;
}
