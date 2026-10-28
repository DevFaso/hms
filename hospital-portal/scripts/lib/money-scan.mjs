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
  'totalAmount|totalDue|totalPaid|unitPrice|lineTotal|grandTotal|amountDue|balanceDue)';
/**
 * The last identifier of the expression must BE a money word (`amount`,
 * `balanceDue`) or END in a capitalised one (`totalAmount`, `copayDue`): a
 * substring match read `feedbackScore`, `overdueInvoices` and `unpaidVisits` as
 * money. And a word that merely contains one is still not money when it ends
 * in a count, a rate, a code or a date.
 */
const MONEY_WORD =
  /^(?:[a-z][A-Za-z0-9]*)?(?:Amount|Price|Balance|Copay|Copayment|Revenue|Subtotal|Fee|Fees|Cost|Paid|Due|Charge|Charges|Payment|Discount|Refund|Total)$|^(?:amount|price|balance|copay|copayment|revenue|subtotal|fee|fees|cost|paid|due|charge|charges|payment|discount|refund)$/;
const NOT_MONEY =
  /(?:rate|percent|pct|ratio|count|days|hours|minutes|index|number|share|code|score|invoices|visits|items|length|at|on|date|by)$/i;

const EXPR = String.raw`([\w$.?!()\[\]'"]*?\w*${MONEY_TAIL}\w*(?:\(\))?)`;
// `[?!]?` — `inv.amount?.toFixed(2)` and `inv.amount!.toFixed(2)` are the same
// hand formatting, and `(line.amount * rate).toFixed(2)` the same again.
const PIPE_RE = new RegExp(String.raw`${EXPR}\s*[?!]?\s*\)?\s*\|\s*(number|currency)\b`, 'gi');
const FIXED_RE = new RegExp(String.raw`${EXPR}\s*[?!]?\s*(?:[^()\n]*\))?\s*\.toFixed\(`, 'gi');

const HELPER_DECL =
  /^\s*(?:private\s+|protected\s+|public\s+|readonly\s+)*(format(?:Currency|Money|Amount|Price|Fcfa|Cfa))\s*\([^)]*\)\s*(?::\s*string\s*)?\{/gm;
const INTL_CURRENCY = /new\s+Intl\.NumberFormat\([^)]*\)|toLocaleString\(\s*[^)]*\)/g;

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

/** Template rules: `pipe` and `fixed`. */
export function moneyTemplateFindings(html) {
  const out = [];
  for (const m of html.matchAll(PIPE_RE)) {
    if (!isMoney(m[1])) continue;
    out.push({
      line: lineAt(html, m.index),
      rule: 'pipe',
      anchor: `${name(m[1])} | ${m[2].toLowerCase()}`,
      message: `${name(m[1])} | ${m[2]}: money without the shared format — use | cfa (shared/pipes/cfa.pipe.ts)`,
    });
  }
  for (const m of html.matchAll(FIXED_RE)) {
    if (!isMoney(m[1])) continue;
    out.push({
      line: lineAt(html, m.index),
      rule: 'fixed',
      anchor: `${name(m[1])}.toFixed`,
      message: `${name(m[1])}.toFixed(): money formatted by hand — use | cfa`,
    });
  }
  return out;
}

function bodyAfter(source, openBraceIndex) {
  let depth = 0;
  for (let i = openBraceIndex; i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}' && --depth === 0) return source.slice(openBraceIndex, i + 1);
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
    if (!/style\s*:\s*['"]currency['"]|currency\s*:/.test(m[0])) continue;
    if (helperBodies.some(([start, end]) => m.index > start && m.index < end)) continue;
    // Asking Intl how many decimals a currency has prints nothing.
    if (
      /^\s*\.resolvedOptions\(/.test(
        source.slice(m.index + m[0].length, m.index + m[0].length + 40),
      )
    )
      continue;
    out.push({
      line: lineAt(source, m.index),
      rule: 'helper',
      anchor: 'Intl currency',
      message:
        'Intl currency formatting outside app-locale.ts: XOF prints "XOF"/"F CFA4,000" — use formatCfa()',
    });
  }
  for (const m of source.matchAll(FIXED_RE)) {
    if (!isMoney(m[1])) continue;
    out.push({
      line: lineAt(source, m.index),
      rule: 'fixed',
      anchor: `${name(m[1])}.toFixed`,
      message: `${name(m[1])}.toFixed(): money formatted by hand — use formatCfa()`,
    });
  }
  return out;
}
