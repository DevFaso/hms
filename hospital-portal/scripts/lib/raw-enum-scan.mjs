/**
 * Find enum-shaped fields an Angular template renders WITHOUT | enumLabel.
 *
 * Extracted from the gate so it can be tested. The first version of this scan
 * matched only `{{ x.status }}` and `{{ x.status || 'y' }}`, which made it
 * close to useless: `??`, a ternary, a text-bearing attribute binding and a
 * two-step optional chain were all invisible, so four new raw renders left the
 * count unchanged. Every one of those shapes has a case in
 * lib/raw-enum-scan.test.mjs.
 *
 * The second version overshot in the other direction. It scanned `{{ … }}`
 * anywhere in the file, including inside an attribute VALUE, so
 *
 *     <span class="status-badge {{ getStatusClass(o.status) }}">
 *       {{ o.status | enumLabel: 'labOrderStatus' }}
 *     </span>
 *
 * was reported as a raw render of `o.status` — while the label one line below
 * it was piped. The interpolation feeds a CSS class name; nobody reads it. A
 * quarter of the sites the first baseline pinned were that shape, which both
 * overstated the debt and pointed the next tranche at templates that were
 * already done. Worse as a gate: adding a `statusClass()` helper to a template
 * would have failed the build for a render that does not exist.
 *
 * So an interpolation now counts only where a person reads it — in element
 * text content, or in the value of a text-bearing attribute.
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

/**
 * Attributes whose value a person reads. Everything else — `class`, `style`,
 * `id`, `data-*`, `routerLink`, a `(click)` handler, a `#ref` — is machinery,
 * and an enum inside one is not on screen.
 */
const TEXT_ATTRS = new Set([
  'title',
  'alt',
  'placeholder',
  'aria-label',
  'aria-description',
  'aria-valuetext',
  'label',
  'mattooltip',
  'matbadge',
]);

/**
 * `value` is read only when it is written as a plain interpolated attribute:
 * `value="{{ x.status }}"` is painted into the field, while `[value]="x.status"`
 * on an `<option>` is the form value behind a label rendered separately.
 */
const TEXT_IF_INTERPOLATED = new Set(['value']);

/**
 * One attribute assignment. The name may be plain (`title=`), bound
 * (`[title]=`, `[attr.aria-label]=`), an event (`(click)=`), a template ref
 * (`#row=`) or a structural directive (`*ngIf=`); the shape of the name is
 * what tells us whether the value is read. Both quote styles: Prettier
 * normalises these templates to double quotes, but a scan that silently stops
 * seeing half the markup is the failure mode this file exists to prevent.
 */
const ATTR = /([@*#([]?[\w.\-$]+[)\]]?)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;

const COMMENT = /<!--[\s\S]*?-->/g;
const INTERPOLATION = /\{\{([\s\S]*?)\}\}/g;

/**
 * An expression already handed to a pipe that resolves it — enumLabel does the
 * job, and translate/date/number/currency mean the value is not a bare enum.
 */
const RESOLVED = /\|\s*(enumLabel|translate|date|number|currency|percent)\b/;

/** `[attr.aria-label]` and `[title]` and `matTooltip` all reduce to a name. */
function attrName(raw) {
  return raw
    .replace(/^[[(@*#]+/, '')
    .replace(/[\])]+$/, '')
    .replace(/^attr\./i, '')
    .toLowerCase();
}

/** Same length, same newlines — so every offset below still points at the original. */
const blank = (text) => text.replace(/[^\n]/g, ' ');

/**
 * @returns {{expr: string, line: number}[]} one entry per raw render, in file
 * order. An expression piped in the same binding is not reported, so
 * `{{ x.status | enumLabel: 'labOrderStatus' }}` is clean and
 * `{{ a.status }} {{ b.status | enumLabel: 'x' }}` reports only `a.status`.
 */
export function rawEnumRenders(html) {
  const hits = [];
  const lineAt = (index) => html.slice(0, index).split('\n').length;

  const collectExpr = (body, index) => {
    if (RESOLVED.test(body)) return;
    for (const field of body.matchAll(FIELD)) hits.push({ expr: field[0], line: lineAt(index) });
  };

  // Each `{{ … }}` is judged on its own. Testing the whole attribute value at
  // once let one resolved half excuse a raw one beside it, so
  // `alt="{{ p.severity }} {{ p.when | date }}"` went uncounted.
  const collectValue = (value, index) => {
    const parts = [...value.matchAll(INTERPOLATION)];
    if (!parts.length) return collectExpr(value, index);
    for (const part of parts) collectExpr(part[1], index);
  };

  // Commented-out markup is not on screen; counting it mints baseline pins for
  // dead code, and uncommenting the block then reports them stale.
  let work = html.replace(COMMENT, blank);

  // Blank every attribute value, scanning the text-bearing ones on the way
  // past. Blanking through replace() keeps the offsets exact — hand-computed
  // ones were wrong for `class = "…"`, where the spaces the regex permits
  // shifted the window onto the attribute NAME.
  work = work.replace(ATTR, (match, name, doubled, singled, offset) => {
    const value = doubled ?? singled ?? '';
    const plain = !/^[[(@*#]/.test(name);
    const key = attrName(name);
    if (TEXT_ATTRS.has(key) || (plain && TEXT_IF_INTERPOLATED.has(key))) {
      collectValue(value, offset);
    }
    return blank(match);
  });

  // What is left is element text content — the words on screen.
  for (const binding of work.matchAll(INTERPOLATION)) collectExpr(binding[1], binding.index);

  return hits.sort((a, b) => a.line - b.line);
}
