/**
 * QA CX-010 (round 33; D1197 / D1229 class): one click ended something.
 * "Désactiver" on a subscription plan, the × on a saved search, a DHIS2 mapping
 * delete, a microbiology isolate removal (its correction reason is not a
 * confirmation) went straight to the server, and on /super-admin/emergency a
 * single click turned the whole platform read-only (CX-001).
 *
 * The gate follows the path a reader's click takes:
 *
 *  1. the destructive calls of the portal: every `http.delete(`, and every
 *     service method named as ending something (`DESTRUCTIVE_NAME`) that writes
 *     (`.post(` `.put(` `.patch(` `.delete(`);
 *     QA D1430 (the gate missed it): a service's plain `update` that a component
 *     uses to end something. The name of the SERVICE method said nothing, so
 *     `/registrations` deactivated a patient on one click through
 *     `update(id, { active: false })`. A component method that writes through a
 *     service counts too when it is itself named as ending something, or sends
 *     `active: false`;
 *  2. in each component, the methods that make such a call, directly through
 *     `this.http` or through an injected service, and those that reach them
 *     through `this.other()` in the same class;
 *  3. each template binding that calls such a method.
 *
 * A path is confirmed when, before the call goes out:
 *
 *  - a method on the path asks (`.ask(` of ConfirmService or of any injected
 *    asker, or it awaits the shared reason dialog), or
 *  - the binding is the answer of a question: `(confirmed)` on
 *    `<app-confirm-dialog>` / `<app-reason-dialog>`, or
 *  - the binding is inside a dialog the reader opened first (`role="dialog"`,
 *    `appModalDialog`, `appDialogFocus`, a `.modal`, a native `<dialog>`).
 *
 * Rule `unconfirmed` reports the rest, one finding per (template method, target):
 * `file::unconfirmed::handler->service.method`. What legitimately ends something
 * on one click (a filter chip, a draft row never sent, a thing the reader can
 * undo at once) is pinned with its reason in
 * scripts/destructive-confirm-baseline.json. A pin is a claim someone looked.
 */
import { balanced, endOfLiteralOrComment } from './js-scan.mjs';
import { tags, attrValue, classTokens, hasAttr } from './html-tags.mjs';

export const DESTRUCTIVE_NAME =
  /^(?:delete|remove|revoke|deactivate|cancel|archive|discontinue|void|unlink|purge|retire|withdraw|terminate|suspend|decommission|discard|unassign|reject|forceLogout|forceMfa|killFeature|broadcast)(?:[A-Z_]|$)/;

/** Methods whose name says nothing, and that change the whole platform: `Service.method`. */
const PLATFORM_WIDE = new Set(['DowntimeService.toggle']);

const CONTROL_WORDS = new Set([
  'if',
  'for',
  'while',
  'switch',
  'catch',
  'return',
  'function',
  'super',
  'constructor',
  'do',
  'else',
  'new',
  'typeof',
  'await',
]);

/** The source with comments and string contents blanked, offsets kept. */
function blanked(source, { keepStrings = false } = {}) {
  let out = '';
  for (let i = 0; i < source.length; i++) {
    const end = endOfLiteralOrComment(source, i);
    if (end >= 0) {
      const piece = source.slice(i, end + 1);
      const isComment = piece.startsWith('//') || piece.startsWith('/*');
      out += keepStrings && !isComment ? piece : piece.replace(/[^\n]/g, ' ');
      i = end;
    } else {
      out += source[i];
    }
  }
  return out;
}

/** The body of each method of the (one) class of a source: name → body text. */
export function classMethods(source) {
  const code = blanked(source);
  const methods = new Map();
  const head =
    /(?:^|\n)[ \t]{2}(?:(?:private|public|protected|static|async|override|readonly)\s+)*([A-Za-z_$][\w$]*)\s*(?:<[^>\n]*>)?\s*(\(|=\s*(?:async\s*)?\()/g;
  for (const m of code.matchAll(head)) {
    const name = m[1];
    if (CONTROL_WORDS.has(name)) continue;
    const open = m.index + m[0].length - 1;
    const close = open + balanced(code, open).length - 1;
    const brace = code.indexOf('{', close);
    if (brace < 0) continue;
    // `name(args): Type {` or `name = (args): Type => {`; a field call has no body.
    if (code.slice(close + 1, brace).includes(';')) continue;
    methods.set(name, balanced(code, brace, '{', '}'));
  }
  return methods;
}

/** The injected fields of a class: field name → class name. */
export function injectedFields(source) {
  const out = new Map();
  for (const m of source.matchAll(/\b([\w$]+)\s*(?::\s*[\w$<>\s,|]+)?=\s*inject\(\s*([\w$]+)/g)) {
    out.set(m[1], m[2]);
  }
  for (const m of source.matchAll(
    /(?:private|public|protected)\s+(?:readonly\s+)?([\w$]+)\s*:\s*([A-Z][\w$]*)/g,
  )) {
    if (!out.has(m[1])) out.set(m[1], m[2]);
  }
  return out;
}

const HTTP_CLIENT = /\bHttpClient\b/;
const CLASS_NAME = /export\s+(?:abstract\s+)?class\s+([A-Z][\w$]*)/;

/**
 * The destructive methods of the services: `Service → Set(method)`.
 *
 * @param {{file:string, text:string}[]} sources every non-spec `.ts`
 */
export function destructiveServiceMethods(sources) {
  const out = new Map();
  writingServiceMethods.clear();
  for (const { text } of sources) {
    if (!/@Injectable/.test(text) || !HTTP_CLIENT.test(text)) continue;
    const cls = CLASS_NAME.exec(text)?.[1];
    if (!cls) continue;
    const found = new Set();
    for (const [name, body] of classMethods(text)) {
      const deletes = /\.\s*delete\s*(?:<[^(]*>)?\s*\(/.test(body);
      const writes = /\.\s*(?:post|put|patch|delete)\s*(?:<[^(]*>)?\s*\(/.test(body);
      const named = DESTRUCTIVE_NAME.test(name) || PLATFORM_WIDE.has(`${cls}.${name}`);
      if (deletes || (writes && named)) found.add(name);
      if (writes) {
        if (!writingServiceMethods.has(cls)) writingServiceMethods.set(cls, new Set());
        writingServiceMethods.get(cls).add(name);
      }
    }
    if (found.size > 0) out.set(cls, found);
  }
  return out;
}

const ASKS = /\.\s*ask\s*\(/;

/** Every writing method of every service, read by {@link destructiveServiceMethods} (QA D1430). */
const writingServiceMethods = new Map();

/** A flag in a write that takes the record out of use: `active: false`. */
const ENDS_BY_FLAG = /\bactive\s*:\s*false\b/;

/**
 * Per method of a component: the destructive targets it reaches with nothing
 * asked on the way.
 */
function unaskedTargets(text, services) {
  const methods = classMethods(text);
  const fields = injectedFields(text);
  const http = [...fields].filter(([, type]) => type === 'HttpClient').map(([name]) => name);
  const direct = new Map();
  for (const [name, body] of methods) {
    const targets = new Set();
    for (const h of http) {
      if (new RegExp(`this\\.${h}\\s*\\.\\s*delete\\b`).test(body)) targets.add('http.delete');
    }
    for (const m of body.matchAll(/this\.([\w$]+)\s*\.\s*([\w$]+)\s*(?:<[^(]*>)?\s*\(/g)) {
      const type = fields.get(m[1]);
      const namedByTheService = type && services.get(type)?.has(m[2]);
      const endedByTheCaller =
        type &&
        writingServiceMethods.get(type)?.has(m[2]) &&
        (DESTRUCTIVE_NAME.test(name) || ENDS_BY_FLAG.test(body));
      if (namedByTheService || endedByTheCaller) targets.add(`${type}.${m[2]}`);
    }
    direct.set(name, targets);
  }
  const memo = new Map();
  const reach = (name, stack = new Set()) => {
    if (memo.has(name)) return memo.get(name);
    if (stack.has(name)) return new Set();
    stack.add(name);
    const body = methods.get(name);
    const out = new Set();
    if (body !== undefined && !ASKS.test(body)) {
      for (const t of direct.get(name) ?? []) out.add(t);
      for (const m of body.matchAll(/this\.([\w$]+)\s*\(/g)) {
        if (methods.has(m[1])) for (const t of reach(m[1], stack)) out.add(t);
      }
    }
    stack.delete(name);
    memo.set(name, out);
    return out;
  };
  return { methods, reach };
}

function isDialogTag(tag) {
  const role = (attrValue(tag, 'role') ?? '').replace(/^["']|["']$/g, '').trim();
  return (
    tag.name === 'dialog' ||
    tag.name === 'app-confirm-dialog' ||
    tag.name === 'app-reason-dialog' ||
    role === 'dialog' ||
    role === 'alertdialog' ||
    hasAttr(tag, 'appModalDialog') ||
    hasAttr(tag, 'appDialogA11y') ||
    hasAttr(tag, 'appDialogFocus') ||
    classTokens(tag).has('modal')
  );
}

/** Each event binding of a template with whether a dialog encloses it. */
export function eventBindings(html) {
  const out = [];
  const stack = [];
  for (const tag of tags(html)) {
    if (tag.type === 'close') {
      const at = stack.map((t) => t.name).lastIndexOf(tag.name);
      if (at >= 0) stack.length = at;
      continue;
    }
    const enclosed = isDialogTag(tag) || stack.some(isDialogTag);
    for (const [name, value] of tag.attrs) {
      if (!/^\(.+\)$/.test(name) || value === null) continue;
      const answers = name === '(confirmed)';
      out.push({
        event: name,
        value: value.replace(/^["']|["']$/g, ''),
        enclosed: enclosed || answers,
        line: tag.line,
      });
    }
    if (!tag.selfClosing) stack.push(tag);
  }
  return out;
}

/**
 * @param {string} html the template
 * @param {string} source the component's `.ts`
 * @param {Map<string, Set<string>>} services {@link destructiveServiceMethods}
 * @returns {{line:number, rule:string, anchor:string, message:string}[]}
 */
export function destructiveConfirmFindings(html, source, services) {
  const { methods, reach } = unaskedTargets(source, services);
  const seen = new Set();
  const out = [];
  for (const binding of eventBindings(html)) {
    if (binding.enclosed) continue;
    for (const call of binding.value.matchAll(/(?<![.\w$])([A-Za-z_$][\w$]*)\s*\(/g)) {
      const handler = call[1];
      if (!methods.has(handler)) continue;
      for (const target of reach(handler)) {
        const anchor = `${handler}->${target}`;
        if (seen.has(anchor)) continue;
        seen.add(anchor);
        out.push({
          line: binding.line,
          rule: 'unconfirmed',
          anchor,
          message:
            `${binding.event}="${handler}(…)" reaches ${target} with no confirmation on its path (QA CX-010): ` +
            'ask with ConfirmService.ask(), or send it from <app-confirm-dialog> / <app-reason-dialog>',
        });
      }
    }
  }
  return out;
}
