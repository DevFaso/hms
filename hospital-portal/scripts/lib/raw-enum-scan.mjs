/**
 * Find enum-shaped fields an Angular template renders WITHOUT | enumLabel.
 *
 * Extracted from the gate so it can be tested. The first version of this scan
 * matched only `{{ x.status }}` and `{{ x.status || 'y' }}`, which made it
 * close to useless: `??`, a ternary, a text-bearing attribute binding and a
 * two-step optional chain were all invisible, so four new raw renders left the
 * count unchanged. Every one of those shapes has a case in
 * lib/raw-enum-scan.test.mjs.
 */

/**
 * Field names that usually hold an enum. The TAIL is matched, not the whole
 * name: an earlier scan looked for a field CALLED `.type` and so never saw
 * `.encounterType` — six of those were found by hand afterwards, which is the
 * miss this whole gate exists to stop repeating.
 */
export const ENUM_WORDS = [
  'status',
  'state',
  'type',
  'severity',
  'priority',
  'urgency',
  'disposition',
  'category',
  'kind',
  'outcome',
  'method',
  'mode',
  'level',
  'route',
  'gender',
  'relationship',
  'specialty',
  'modality',
  'reason',
  'result',
  'frequency',
  'flag',
  'phase',
  'stage',
  'source',
  'action',
  'channel',
];

/** `a.b?.c.encounterType` — any number of optional-chain steps. */
const FIELD = new RegExp(
  String.raw`\b[A-Za-z_$][\w$]*(?:\??\.[A-Za-z_$][\w$]*)*` +
    String.raw`\??\.\w*?(?:${ENUM_WORDS.join('|')})\b`,
  'gi',
);

/** Attributes whose value a person reads. `[style.background]` is not one. */
const TEXT_ATTRS = ['title', 'alt', 'placeholder', 'aria-label', 'aria-description', 'matTooltip'];

/** `{{ … }}` and `[title]="…"` / `[attr.aria-label]="…"`. */
const INTERPOLATION = /\{\{([\s\S]*?)\}\}/g;
const ATTR_BINDING = new RegExp(
  String.raw`\[(?:attr\.)?(?:${TEXT_ATTRS.join('|')})\]\s*=\s*"([^"]*)"`,
  'gi',
);

/**
 * An expression already handed to a pipe that resolves it — enumLabel does the
 * job, and translate/date/number/currency mean the value is not a bare enum.
 */
const RESOLVED = /\|\s*(enumLabel|translate|date|number|currency|percent)\b/;

/**
 * @returns {{expr: string, line: number}[]} one entry per raw render, in file
 * order. An expression piped in the same binding is not reported, so
 * `{{ x.status | enumLabel: 'labOrderStatus' }}` is clean and
 * `{{ a.status }} {{ b.status | enumLabel: 'x' }}` reports only `a.status`.
 */
export function rawEnumRenders(html) {
  const hits = [];
  const lineAt = (index) => html.slice(0, index).split('\n').length;

  for (const source of [INTERPOLATION, ATTR_BINDING]) {
    source.lastIndex = 0;
    for (const binding of html.matchAll(source)) {
      const body = binding[1];
      if (RESOLVED.test(body)) continue;
      for (const field of body.matchAll(FIELD)) {
        hits.push({ expr: field[0], line: lineAt(binding.index) });
      }
    }
  }
  return hits.sort((a, b) => a.line - b.line);
}
