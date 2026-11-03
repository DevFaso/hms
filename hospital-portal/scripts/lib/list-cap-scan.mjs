/**
 * QA D1347 (round 30): a list read from ONE page and treated as the whole
 * list. The lab orders page asked for `{ size: 200 }` once and counted that
 * page, so the 16 oldest of 216 orders — a pending one among them — were
 * unreachable (the D1213 lab results and D1295 nurse inbox were the same
 * shape). The fix is `allRows` / `allPages` (src/app/shared/all-pages.ts),
 * which reads every page, or a pager that shows the server's total.
 *
 * Rules (one finding per site, anchored on the enclosing member so a line
 * move does not unpin it):
 *
 *  - `fixed-size`: a literal page size or limit passed to a read —
 *    `{ size: 200 }`, `limit: 50`, `.set('size', '20')`, or the positional
 *    `.listX(…, 0, 100)` (page 0 of 100). A bare literal is the shape of the
 *    defect: one page, a number somebody guessed was "enough".
 *  - `page-flattened`: a page response reduced to its rows inside a `map`
 *    (`map((r) => r?.data?.content ?? [])`): the total is thrown away, so no
 *    caller can know the list was cut.
 *  - `page-content`: a page's rows read (`page.content`, `res?.data?.content`)
 *    in a file that reads neither `totalElements`, `totalPages` nor `last` —
 *    nothing on that screen can say there is more.
 *
 * Not findings: `font-size: 64px` and other CSS in inline styles (a unit
 * follows), `slice(0, 8)` / `substring(0, 50)` (not a read), `size = 20`
 * default parameters of a service method (the call site decides: the rules
 * above see the call), and the shared helper itself.
 */

const HELPER = 'src/app/shared/all-pages.ts';

/** The class member (method or field) a line belongs to, for a stable anchor. */
function memberAt(lines, index) {
  for (let i = index; i >= 0; i--) {
    const m =
      /^ {2}(?:(?:private|public|protected|readonly|static|async|override)\s+)*([A-Za-z_$][\w$]*)\s*(?:[(<=:]|\?\s*:)/.exec(
        lines[i],
      );
    if (m && !['if', 'for', 'while', 'switch', 'return', 'const', 'let'].includes(m[1])) {
      return m[1];
    }
    const fn = /^(?:export\s+)?(?:async\s+)?function\s+([\w$]+)/.exec(lines[i]);
    if (fn) return fn[1];
  }
  return '(top)';
}

function lineOf(text, offset) {
  let line = 1;
  for (let i = 0; i < offset; i++) if (text.charCodeAt(i) === 10) line++;
  return line;
}

/** The method name of the nearest call opened before `offset` on the same statement. */
function calleeBefore(text, offset) {
  const window = text.slice(Math.max(0, offset - 400), offset);
  const calls = [...window.matchAll(/\.?([A-Za-z_$][\w$]*)\s*\(/g)];
  return calls.length > 0 ? calls[calls.length - 1][1] : '?';
}

const FIXED_SIZE_OBJECT = /(?<![-\w$])(size|limit)\s*:\s*(\d+)(?![\w.%])/g;
const FIXED_SIZE_PARAM = /\.(?:set|append)\(\s*['"](size|limit)['"]\s*,\s*['"]?(\d+)['"]?\s*\)/g;
const FIXED_SIZE_POSITIONAL =
  /\.((?:list|search|get|find)[\w$]*)\(([^()]*?,\s*)?0\s*,\s*(\d+)\s*[,)]/g;
const PAGE_FLATTENED =
  /map\(\s*\(?\s*([\w$]+)\s*\)?\s*=>\s*\(?\s*\1\??\.(?:data\??\.|body\??\.)?content\s*\?\?\s*\[\]/g;
const PAGE_CONTENT =
  /(?<![\w$.])([\w$]+)(?:\?\.|\.)(?:(?:data|body|items|page|dispenses|routings)(?:\?\.|\.))?content\b(?!\s*:)/g;
/** Receivers whose `.content` is not a page's rows. */
const NOT_A_PAGE = new Set(['msg', 'message', 'note', 'n', 'entry', 'event', 'm', 'c', 'body']);

export function listCapFindings(source, relFile) {
  if (relFile === HELPER) return [];
  const lines = source.split('\n');
  const out = [];
  const push = (offset, rule, detail, message) => {
    const line = lineOf(source, offset);
    out.push({ line, rule, anchor: `${memberAt(lines, line - 1)}:${detail}`, message });
  };
  // Comments are not code: blank them out (keeping offsets) before scanning.
  const code = source
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:'"`\\])\/\/[^\n]*/g, (m, lead) => lead + ' '.repeat(m.length - lead.length));

  for (const m of code.matchAll(FIXED_SIZE_OBJECT)) {
    push(
      m.index,
      'fixed-size',
      `${calleeBefore(code, m.index)}:${m[1]}=${m[2]}`,
      `${m[1]}: ${m[2]} reads ONE page of a list (QA D1347): read every page (allRows) or show the server's total`,
    );
  }
  for (const m of code.matchAll(FIXED_SIZE_PARAM)) {
    push(
      m.index,
      'fixed-size',
      `${calleeBefore(code, m.index)}:${m[1]}=${m[2]}`,
      `'${m[1]}', ${m[2]} reads ONE page of a list (QA D1347): read every page (allRows) or show the server's total`,
    );
  }
  for (const m of code.matchAll(FIXED_SIZE_POSITIONAL)) {
    push(
      m.index,
      'fixed-size',
      `${m[1]}:page0x${m[3]}`,
      `${m[1]}(…, 0, ${m[3]}) reads ONE page of a list (QA D1347): read every page (allRows) or show the server's total`,
    );
  }
  for (const m of code.matchAll(PAGE_FLATTENED)) {
    push(
      m.index,
      'page-flattened',
      'content',
      'a page reduced to its rows: the total is lost, so the list can be cut silently (QA D1347)',
    );
  }
  if (!/\btotalElements\b|\btotalPages\b|\.last\b/.test(code)) {
    for (const m of code.matchAll(PAGE_CONTENT)) {
      if (NOT_A_PAGE.has(m[1])) continue;
      // Already reported as page-flattened on the same line.
      const line = lineOf(code, m.index);
      if (out.some((f) => f.rule === 'page-flattened' && f.line === line)) continue;
      push(
        m.index,
        'page-content',
        m[1],
        "a page's rows read with nothing on the screen saying there is more (QA D1347)",
      );
    }
  }
  return out;
}
