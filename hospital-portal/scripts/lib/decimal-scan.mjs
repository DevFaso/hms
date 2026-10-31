/**
 * A measured decimal shown without the shared locale number formatter
 * (QA D1002 class: D1106, D1115, D1116 in round 19).
 *
 * `shared/i18n/app-locale.ts` holds the one formatter (formatLabNumber,
 * formatLabRange, formatMeasure, formatDecimal, localizeDecimals) and
 * `shared/pipes/lab-value.pipe.ts` / `measure.pipe.ts` are its template face
 * (`| labValue`, `| labRange`, `| localeNumber`, `| measure`). Round 19 found
 * "37.9 °C", "4.8 kg", "0.7 - 0.99 g/L" on eight screens the round-18 guard
 * (lab templates and `.resultValue` only) could not see.
 *
 * Template rule — an interpolation `{{ … }}` that reads a decimal-shaped field:
 *   - a field whose NAME says it is a decimal measurement: resultValue,
 *     referenceRange, normalRange, bmi, temperature, or a camelCase name ending
 *     in a decimal unit (…Kg, …Cm, …Celsius, …Mmol, …MmolL);
 *   - a lab row's generic value (`lab.result`, `lab.value`, `l.result`,
 *     `labResult.value`, …): `result` / `value` on a receiver named for a lab.
 * Integers by name (…Bpm, …MmHg, …MgDl, …Ml, …Grams — Integer columns on the
 * server — spo2Percent, counts, ids) are not
 * matched: they carry no separator. The interpolation passes when it pipes
 * through `labValue`, `labRange`, `localeNumber`, `measure` or Angular's
 * `number` (LOCALE_ID-bound), or when the field is the argument of a
 * component method whose body calls the shared formatter (growth's
 * `formatMeasure`, my-lab-results' `displayResult`). A method that formats by
 * hand (`${value} ${unit}`, the round-19 growth table) does not pass.
 *
 * Any `.toFixed(` / `.toPrecision(` in an interpolation fails.
 *
 * Code rule — a component or service `.ts` that writes a decimal for display
 * with `.toFixed(` / `.toPrecision(` or `(Math.round(…)).toString()`. SVG
 * coordinates (`x.toFixed`, `p.y.toFixed`) are geometry, not text.
 */
import { balanced } from './js-scan.mjs';

const DECIMAL_NAME =
  /^(?:resultValue|referenceRange|normalRange|bmi|temperature)$|[a-z0-9](?:Kg|Cm|Celsius|Mmol|MmolL)$/;
const LAB_RECEIVER = /^(?:lab|labs|l|lr|labResult|latestLab|res)$/;
const FORMATTER_PIPE = /\|\s*(?:labValue|labRange|localeNumber|measure|number)\b/;
const SHARED_FORMATTER =
  /\b(?:formatLabNumber|formatLabRange|formatMeasure|formatDecimal|localizeDecimals)\s*\(/;
// An identifier that is not a call; its receiver is read from what precedes it.
const IDENT = /(?<![\w$])[A-Za-z_$][\w$]*(?![\w$])(?!\s*\()/g;
const RECEIVER = /([\w$]+)\s*(?:\?\.|!\.|\.)\s*$/;

const lineAt = (text, index) => text.slice(0, index).split('\n').length;

/** The body of method `name` in a component source, or undefined. */
function methodBody(source, name) {
  const decl = new RegExp(
    String.raw`(?:^|\n)\s*(?:(?:private|protected|public|readonly|static)\s+)*${name}\s*(?:\((?:[^()]|\([^()]*\))*\)\s*(?::\s*[^{;=]+)?\{|=\s*\()`,
  );
  const m = decl.exec(source);
  if (!m) return undefined;
  const open = source.indexOf(m[0].endsWith('(') ? '=>' : '{', m.index + m[0].length - 1);
  if (open < 0) return undefined;
  if (m[0].endsWith('(')) return source.slice(open, open + 400);
  return balanced(source, open, '{', '}');
}

/** The name of the innermost call whose parentheses enclose `index` in `expr`, if any. */
function enclosingCall(expr, index) {
  let depth = 0;
  for (let i = index - 1; i >= 0; i--) {
    const ch = expr[i];
    if (ch === ')') depth++;
    else if (ch === '(') {
      if (depth === 0) {
        const m = /([\w$]+)\s*$/.exec(expr.slice(0, i));
        return m ? m[1] : undefined;
      }
      depth--;
    }
  }
  return undefined;
}

/** True when `name(...)` is a component method that writes through the shared formatter. */
function formatsThroughShared(name, componentSource) {
  // A component method of that name is judged by its body, even when it
  // shares a shared formatter's name (the growth tab's own `formatMeasure`
  // wrote `${value} ${unit}`); otherwise the shared function itself is trusted.
  const body = methodBody(componentSource ?? '', name);
  if (body !== undefined) return SHARED_FORMATTER.test(body);
  return SHARED_FORMATTER.test(`${name}(`);
}

/** Decimal-shaped fields read by one interpolation, with their offset in it. */
function decimalFields(expr, labLoopVars = new Set()) {
  const out = [];
  // Strings are not code: `'—'` and `' cm'` hold no field.
  const code = expr.replaceAll(/'[^']*'|"[^"]*"/g, (s) => ' '.repeat(s.length));
  for (const m of code.matchAll(IDENT)) {
    const name = m[0];
    const receiver = RECEIVER.exec(code.slice(0, m.index))?.[1];
    if (
      DECIMAL_NAME.test(name) ||
      (/^(?:result|value)$/.test(name) &&
        (LAB_RECEIVER.test(receiver ?? '') || labLoopVars.has(receiver ?? '')))
    ) {
      out.push({ name: receiver ? `${receiver}.${name}` : name, index: m.index });
    }
  }
  return out;
}

/**
 * @param {string} html a template
 * @param {string} [componentSource] the template's component `.ts` (the methods it calls)
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function decimalTemplateFindings(html, componentSource = '') {
  const findings = [];
  // `@for (r of labResults(); …)`: a row of a lab-result collection is a lab row,
  // whatever its loop variable is called (the chart's Laboratoire table).
  const labLoopVars = new Set(
    [...html.matchAll(/@for\s*\(\s*([\w$]+)\s+of\s+([^;)]*)/g)]
      .filter((f) => /lab\w*results?\b/i.test(f[2]))
      .map((f) => f[1]),
  );
  for (const m of html.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    const expr = m[1];
    const line = lineAt(html, m.index);
    if (/\.to(?:Fixed|Precision)\s*\(/.test(expr)) {
      findings.push({
        line,
        rule: 'fixed',
        anchor: expr.trim(),
        message: `decimal written with toFixed/toPrecision: {{${expr}}}`,
      });
      continue;
    }
    // QA D1135 / D1106 (round 20): a percentage with the "%" typed after it.
    // `{{ rate }}%` printed "16.7%" in French; even `{{ rate | number }}%`
    // glues the sign ("16,7%" where French writes "16,7 %"). The sign belongs
    // to the formatter: `| measure: '%'` (formatMeasure).
    const after = html.slice(m.index + m[0].length, m.index + m[0].length + 3);
    if ((/^\s*%/.test(after) || /\+\s*'\s?%'/.test(expr)) && !/\|\s*measure\s*:\s*'%'/.test(expr)) {
      findings.push({
        line,
        rule: 'percent',
        anchor: expr.trim(),
        message: `percentage with a typed "%": use | measure: '%' — {{${expr}}}%`,
      });
      continue;
    }
    // The bed board's ward line handed the raw rate to its sentence
    // (`translate: { rate: ward.occupancyRate }` -> "16.7 %" in French): a
    // rate/percentage translation parameter goes through a number formatter.
    for (const p of expr.matchAll(PERCENT_PARAM)) {
      if (!FORMATTER_PIPE.test(p[2])) {
        findings.push({
          line,
          rule: 'percent-param',
          anchor: `${p[1]}: ${p[2].trim()}`,
          message: `percentage parameter ${p[1]} handed to a sentence unformatted: pipe it through | number / | measure`,
        });
      }
    }
    if (FORMATTER_PIPE.test(expr)) continue;
    for (const field of decimalFields(expr, labLoopVars)) {
      const call = enclosingCall(expr, field.index);
      if (call && formatsThroughShared(call, componentSource)) continue;
      findings.push({
        line,
        rule: 'raw',
        anchor: field.name,
        message: `decimal ${field.name} shown without | labValue / | labRange / | localeNumber / | measure: {{${expr}}}`,
      });
    }
  }
  return findings;
}

/** A translation parameter named for a rate or a percentage, and its value (up to `,` or `}`). */
const PERCENT_PARAM = /\b(rate|pct|percent\w*|percentage\w*)\s*:\s*([^,}]+)/gi;

const COORDINATE = /(?:^|[^\w$.])(?:[\w$]+\.)?(?:x|y|cx|cy|x1|x2|y1|y2)\s*$/;

/**
 * @param {string} source a `.ts` file
 * @returns {{ line: number, rule: string, anchor: string, message: string }[]}
 */
export function decimalCodeFindings(source) {
  const findings = [];
  for (const m of source.matchAll(/\.to(?:Fixed|Precision)\s*\(/g)) {
    const before = source.slice(Math.max(0, m.index - 40), m.index);
    if (COORDINATE.test(before)) continue;
    findings.push({
      line: lineAt(source, m.index),
      rule: 'fixed',
      anchor: source.slice(Math.max(0, m.index - 30), m.index).trim(),
      message: 'toFixed/toPrecision writes a decimal point in every language: use formatDecimal',
    });
  }
  for (const m of source.matchAll(
    /Math\.round\([^;\n]*\)\s*(?:\/\s*[\d.]+\s*)?\)\s*\.toString\(\)/g,
  )) {
    findings.push({
      line: lineAt(source, m.index),
      rule: 'round-string',
      anchor: m[0],
      message:
        'a rounded number turned into text with toString(): use formatLabNumber / formatDecimal',
    });
  }
  // QA D1135: a percentage composed in code (`x + '%'`, `${x}%`). A CSS
  // length (a bar's height or width) is not display text.
  for (const m of source.matchAll(/\+\s*' ?%'|\}%`/g)) {
    const before = source.slice(Math.max(0, m.index - 160), m.index);
    if (CSS_CONTEXT.test(before)) continue;
    findings.push({
      line: lineAt(source, m.index),
      rule: 'percent',
      anchor: source.slice(Math.max(0, m.index - 30), m.index + 4).trim(),
      message: "a percentage composed with a typed '%': use formatMeasure(value, '%')",
    });
  }
  return findings;
}

const CSS_CONTEXT =
  /(?:height|width|left|top|right|bottom|style|offset|size|translate)\w*\s*(?:\(|=|:)[^;]*$/i;
