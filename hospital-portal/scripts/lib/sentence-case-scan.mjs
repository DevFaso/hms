/**
 * English title case copied into French and Spanish labels (i18n review
 * 2026-11-01: 232 of the 330 typography findings, e.g. "Mode de Paiement",
 * "Nouvelle Facture", "Fecha de Nacimiento", "Oui, Annuler").
 *
 * French and Spanish write labels in sentence case: only the first word (and
 * proper nouns) take a capital. The scan flags a capitalised word that is not
 * the first word of the value and does not start a sentence, a quotation, a
 * parenthesis or a placeholder's text, i.e. a word that can only be capitalised
 * because the English source was title-cased.
 *
 * Not flagged:
 *  - the first word (the first one with a letter: `← Retour`), and a word after
 *    `. ! ? : ; — – - [ / … « " “ ¿ ¡ •` (a new sentence, a label after its
 *    colon, an example after `ex. :`). A word right after `(` IS read:
 *    `Estudios (Últimos 30 días)` is title case;
 *  - text inside guillemets (`« Validé par son auteur »`, `«Validar»`): a UI
 *    label quoted by its name keeps its own capital;
 *  - a word glued to another capital or a digit (`SpO2`, `RxNorm`, `MenAfriVac`,
 *    `FeatureFlag`, `Mg2+`): an identifier, not a title-cased word;
 *  - an example placeholder (`ex. Or, Argent`, `p. ej., Cardiología`): it shows
 *    what a user types, names included, so its capitals are the user's;
 *  - proper nouns, brands and units ({@link PROPER_NOUNS}, {@link PROPER_PHRASES}).
 *
 * A quoted UI label in running text is written in guillemets: "utilisez
 * « Désactiver »", never "utilisez Désactiver".
 */

/** Single-word proper nouns, brands, eponyms and units that keep their capital. */
export const PROPER_NOUNS = new Set([
  // places (address and country placeholders)
  'Burkina',
  'Faso',
  'Ouagadougou',
  'Uagadugú',
  'Kadiogo',
  'Dassasgho',
  'Paris',
  'París',
  'France',
  'Francia',
  'California',
  // people and facilities in examples
  'Yalgado',
  'Jeanne',
  'Dupont',
  'Pérez',
  // products, standards and eponyms
  'Google',
  'Authenticator',
  'Authy',
  'Bridges',
  'Cerner',
  'Java',
  'Doppler',
  'Morse',
  'Apgar',
  'Web',
  'Internet',
  // abbreviations and units
  'Dr',
  'Rh',
  'Rx',
  'Mo',
  'Gio',
]);

/** Multi-word proper names, removed before the scan. */
export const PROPER_PHRASES = [
  /Côte d['’]Ivoire/g,
  /Costa de Marfil/g,
  /New York/g,
  /Nueva York/g,
  /Île-de-France/g,
  /Isla de Francia/g,
  /États-Unis/g,
  /Estados Unidos/g,
  /Unión Europea/g,
  /Union européenne/g,
  /Levey-Jennings/g,
  /Orange Money/g,
  /Moov Money/g,
  /Mobile Money/g,
  /Pharmacie Centrale/g,
  // address parts, the professional order and an eponym, only in their phrase
  /Secteur \d+/g,
  /Quartier \p{Lu}\p{L}+/gu,
  /l['’]Ordre/g,
  /de Gram/g,
];

/** `ex.`, `p. ex.`, `p. ej.`, `ej.`, `e.g.` at the start of a placeholder. */
const EXAMPLE = /^\s*(?:p\.\s?)?(?:ex|ej)\.|^\s*e\.g\./i;
const OPENERS = new Set([...'.!?:;—–-[/…«"“¿¡•·|>\n', '\u0002']);
const SPACE = /[ \u00a0\u202f'’]/u;
const WORD = /(?<=[ \u00a0\u202f'’(])(\p{Lu}\p{Ll}+)(?![\p{L}\p{N}])/gu;

/**
 * The title-cased words of one French or Spanish value, in order.
 * @param {string} value
 * @returns {{ word: string, index: number }[]}
 */
export function titleCasedWords(value) {
  if (EXAMPLE.test(value)) return [];
  let text = value.replace(/\{\{[^}]*\}\}|\{[^}]*\}/g, (m) => '\u0002'.padEnd(m.length, ' '));
  text = text.replace(/«[^»]*»/g, (m) => '\u0002'.padEnd(m.length, ' '));
  for (const phrase of PROPER_PHRASES)
    text = text.replace(phrase, (m) => '\u0002'.padEnd(m.length, ' '));
  const out = [];
  for (const m of text.matchAll(WORD)) {
    if (PROPER_NOUNS.has(m[1])) continue;
    let k = m.index - 1;
    while (k >= 0 && SPACE.test(text[k])) k--;
    if (k < 0 || OPENERS.has(text[k])) continue;
    // `← Retour`, `1. Patient`: no letter before it, so it is the first word
    if (!/\p{L}/u.test(text.slice(0, m.index))) continue;
    out.push({ word: m[1], index: m.index });
  }
  return out;
}
