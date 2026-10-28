/**
 * Money shown without the shared F CFA formatting (QA D948, D1008; earlier D621).
 *
 * `formatCfa` (shared/i18n/app-locale.ts) and the `| cfa` pipe are the one
 * place an amount in francs is written: "1 500 F CFA", no decimals, the
 * locale's grouping. Every other way of printing money has produced a defect:
 * `| number: '1.2-2'` shows "1 500,00" with no currency (D948), `Intl` with
 * `currency: 'XOF'` prints "XOF" in Spanish (D621), `toFixed(2)` ignores the
 * locale. Three rules:
 *
 *  pipe    — a template applies `| number` or `| currency` to an expression
 *            whose field is money-shaped (amount, price, balance, copay,
 *            revenue, subtotal, fee, cost, paid, due, charge, payment,
 *            discount, refund, total*Amount) — rates, counts and percentages
 *            are not money and are not matched.
 *  helper  — a component declares its own formatCurrency / formatMoney /
 *            formatAmount / formatPrice / formatFcfa whose body does not call
 *            formatCfa, or formats a currency with Intl.NumberFormat or
 *            toLocaleString outside app-locale.ts.
 *  fixed   — `.toFixed(` on a money-shaped expression in a template or a
 *            component.
 */
import { lineOf } from './html-tags.mjs';

const MONEY_TAIL =
  '(?:amount|price|balance|copay|copayment|revenue|subtotal|fee|fees|cost|paid|due|charge|charges|payment|discount|refund|' +
  'total|tax|vat|net|gross|totalAmount|totalDue|totalPaid|unitPrice|lineTotal|grandTotal|amountDue|balanceDue)';
/**
 * The last identifier of the expression must BE a money word (`amount`,
 * `balanceDue`) or END in a capitalised one (`totalAmount`, `copayDue`): a
 * substring match read `feedbackScore`, `overdueInvoices` and `unpaidVisits` as
 * money. And a word that merely contains one is still not money when it ends
 * in a count, a rate, a code or a date.
 */
const MONEY_WORD =
  /^(?:[a-z][A-Za-z0-9]*)?(?:Amount|Price|Balance|Copay|Copayment|Revenue|Subtotal|Fee|Fees|Cost|Paid|Due|Charge|Charges|Payment|Discount|Refund|Total|Tax|Vat|Net|Gross)$|^(?:amount|price|balance|copay|copayment|revenue|subtotal|fee|fees|cost|paid|due|charge|charges|payment|discount|refund|tax|vat)$/;
// A bare `total`, `net` or `gross` is as often a count (beds, visits) as money:
// only the compound forms (grandTotal, lineTotal, netAmount) count, in every rule.
// Case-sensitive camelCase suffixes: `discount` is money, `lineCount` is not.
const NOT_MONEY =
  /[a-z0-9](?:Rate|Percent|Pct|Ratio|Count|Days|Hours|Minutes|Index|Number|Share|Code|Score|Invoices|Visits|Items|Length|At|On|Date|By)$/;

const EXPR = String.raw`([\w$.?!()\[\]'"]*?\w*${MONEY_TAIL}\w*(?:\(\))?)`;
// `[?!]?` — `inv.amount?.toFixed(2)` and `inv.amount!.toFixed(2)` are the same
// hand formatting, and `(line.amount * rate).toFixed(2)` the same again.
// `(?![\w.])` keeps the whole chain: `(total.amount).toFixed` names `total.amount`, not `total`.
// `(inv.amount ?? 0) | number` and `(inv.amount || 0) | number` are the same render with a null guard.
const PIPE_RE = new RegExp(
  String.raw`${EXPR}(?![\w.])\s*[?!]?\s*(?:(?:\?\?|\|\|)\s*0\s*)?\)?\s*\|\s*(number|currency)\b`,
  'gi',
);
/**
 * `(done / total).toFixed(1)` is a ratio: an ambiguous word (total, net, gross)
 * with an arithmetic operator just before it or inside the match is not money.
 */
function ratio(m) {
  const last = name(m[1])
    .split(/\?\.|!\.|\./)
    .pop()
    .replace(/[!?()[\]'"]/g, '');
  if (!/^(?:total|net|gross)$/i.test(last)) return false;
  const before = m.input.slice(Math.max(0, m.index - 4), m.index);
  return /[/*%+-]\s*$/.test(before) || /[/*%+-]/.test(m[0]);
}
const FIXED_RE = new RegExp(
  String.raw`${EXPR}(?!\w)(?!\.(?!toFixed\b))\s*[?!]?\s*(?:[^()\n]*\))?\s*\.toFixed\(`,
  'gi',
);

const HELPER_DECL =
  /^\s*(?:private\s+|protected\s+|public\s+|readonly\s+)*(format(?:Currency|Money|Amount|Price|Fcfa|Cfa))\s*\([^)]*\)\s*(?::\s*string\s*)?\{/gm;
// Only the opener: the argument list is read balanced, so
// `new Intl.NumberFormat(currentLocale(), { style: 'currency', … })` is seen whole.
const INTL_CURRENCY = /new\s+Intl\.NumberFormat\(|\.toLocaleString\(/g;

/** The balanced `( … )` argument list opened at `openParenIndex`, strings skipped. */
function argumentsAfter(source, openParenIndex) {
  let depth = 0;
  for (let i = openParenIndex; i < source.length; i++) {
    const ch = source[i];
    if (ch === "'" || ch === '"' || ch === '`') {
      for (i++; i < source.length && source[i] !== ch; i++) {
        if (source[i] === '\\') i++;
      }
      continue;
    }
    if (ch === '(') depth++;
    else if (ch === ')' && --depth === 0) return source.slice(openParenIndex, i + 1);
  }
  return source.slice(openParenIndex);
}

const lineAt = lineOf;

function isMoney(expr) {
  const last = name(expr)
    .split(/\?\.|!\.|\./)
    .pop()
    .replace(/[!?()[\]'"]/g, '');
  return MONEY_WORD.test(last) && !NOT_MONEY.test(last);
}

/** The expression without a leading `(`: `(point.amount | number` names `point.amount`. */
function name(expr) {
  return expr.replace(/^[(\s]+/, '');
}

/**
 * `{{ r.copayAmount }}` — an amount interpolated with no pipe at all prints
 * "150000" (the D621 shape). The whole interpolation must be the one field (a
 * `?? 0` fallback allowed). With no pipe to say "this is a number", only the
 * unambiguous money words count: `stats().paid`, `p.medsDue`, `boardTotal()`
 * and `daysUntilDue` are counts, so Due/Paid/Total/Net/Gross are left to the
 * pipe and toFixed rules.
 */
const RAW_RE = new RegExp(
  String.raw`\{\{\s*([\w$.?!()\[\]]*?\w*${MONEY_TAIL}\w*(?:\(\))?)\s*(?:\?\?\s*0\s*)?\}\}`,
  'gi',
);
const RAW_MONEY_WORD =
  /^(?:[a-z][A-Za-z0-9]*)?(?:Amount|Price|Balance|Copay|Copayment|Revenue|Subtotal|Fee|Fees|Cost|Charge|Charges|Payment|Discount|Refund|Tax|Vat)$|^(?:amount|price|balance|copay|copayment|revenue|subtotal|fee|fees|cost|charge|charges|payment|discount|refund|tax|vat)$/;

/** Template rules: `pipe`, `fixed` and `raw`. */
export function moneyTemplateFindings(html) {
  const out = [];
  for (const m of html.matchAll(RAW_RE)) {
    const last = name(m[1])
      .split(/\?\.|!\.|\./)
      .pop()
      .replace(/[!?()[\]'"]/g, '');
    if (!RAW_MONEY_WORD.test(last) || NOT_MONEY.test(last)) continue;
    out.push({
      line: lineAt(html, m.index),
      rule: 'raw',
      anchor: `${name(m[1])} raw`,
      message: `{{ ${name(m[1])} }}: an amount with no format at all — use | cfa`,
    });
  }
  for (const m of html.matchAll(PIPE_RE)) {
    if (!isMoney(m[1]) || ratio(m)) continue;
    out.push({
      line: lineAt(html, m.index),
      rule: 'pipe',
      anchor: `${name(m[1])} | ${m[2].toLowerCase()}`,
      message: `${name(m[1])} | ${m[2]}: money without the shared format — use | cfa (shared/pipes/cfa.pipe.ts)`,
    });
  }
  for (const m of html.matchAll(FIXED_RE)) {
    if (!isMoney(m[1]) || ratio(m)) continue;
    out.push({
      line: lineAt(html, m.index),
      rule: 'fixed',
      anchor: `${name(m[1])}.toFixed`,
      message: `${name(m[1])}.toFixed(): money formatted by hand — use | cfa`,
    });
  }
  return out;
}

/** The balanced `{ … }` body opened at `openBraceIndex`; a brace inside a string or template literal is text. */
function bodyAfter(source, openBraceIndex) {
  let depth = 0;
  for (let i = openBraceIndex; i < source.length; i++) {
    const ch = source[i];
    if (ch === "'" || ch === '"' || ch === '`') {
      for (i++; i < source.length && source[i] !== ch; i++) {
        if (source[i] === '\\') i++;
      }
      continue;
    }
    if (ch === '/' && source[i + 1] === '/') {
      i = source.indexOf('\n', i);
      if (i < 0) break;
      continue;
    }
    if (ch === '{') depth++;
    else if (ch === '}' && --depth === 0) return source.slice(openBraceIndex, i + 1);
  }
  return source.slice(openBraceIndex);
}

/** Component rules: `helper` and `fixed`. `isLocaleModule` exempts app-locale.ts itself. */
export function moneyComponentFindings(source, isLocaleModule = false) {
  if (isLocaleModule) return [];
  const out = [];
  // A format* helper that delegates francs to formatCfa() may still use Intl
  // for another currency; its body is judged once, by the `helper` rule.
  const helperBodies = [];
  for (const m of source.matchAll(HELPER_DECL)) {
    const bodyStart = m.index + m[0].length - 1;
    const body = bodyAfter(source, bodyStart);
    helperBodies.push([bodyStart, bodyStart + body.length]);
    if (/\bformatCfa\s*\(/.test(body)) continue;
    out.push({
      line: lineAt(source, m.index),
      rule: 'helper',
      anchor: m[1],
      message: `${m[1]}() formats money without formatCfa(): delegate to shared/i18n/app-locale.ts`,
    });
  }
  for (const m of source.matchAll(INTL_CURRENCY)) {
    const args = argumentsAfter(source, m.index + m[0].length - 1);
    if (!/style\s*:\s*['"]currency['"]|currency\s*:/.test(args)) continue;
    if (helperBodies.some(([start, end]) => m.index > start && m.index < end)) continue;
    // Asking Intl how many decimals a currency has prints nothing.
    const afterCall = m.index + m[0].length - 1 + args.length;
    if (/^\s*\.resolvedOptions\(/.test(source.slice(afterCall, afterCall + 40))) continue;
    out.push({
      line: lineAt(source, m.index),
      rule: 'helper',
      anchor: 'Intl currency',
      message:
        'Intl currency formatting outside app-locale.ts: XOF prints "XOF"/"F CFA4,000" — use formatCfa()',
    });
  }
  for (const m of source.matchAll(FIXED_RE)) {
    if (!isMoney(m[1]) || ratio(m)) continue;
    out.push({
      line: lineAt(source, m.index),
      rule: 'fixed',
      anchor: `${name(m[1])}.toFixed`,
      message: `${name(m[1])}.toFixed(): money formatted by hand — use formatCfa()`,
    });
  }
  return out;
}
