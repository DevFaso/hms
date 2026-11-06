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
 *  - `fixed-limit` (QA D1362, round 31): a read called with ONE literal
 *    number — `getMyLabResults(50)` — the positional spelling of a limit the
 *    `fixed-size` rule could not see. The "Mon dossier médical" card read 50
 *    results and counted those.
 *  - `slice-count` (QA D1362, templates — {@link sliceCountFindings}): a
 *    "+ N more" computed in a template from the rows on hand
 *    (`labs().length - 4`). The count is the server's total less the rows
 *    shown: `moreCount(total, shown)` in src/app/shared/more-count.ts.
 *  - `default-limit` (QA D1379, round 32): a read method whose row limit is a
 *    DEFAULTED parameter and that answers a bare array —
 *    `getMyVitals(limit = 10): Observable<VitalSignSummary[]>`. The call site
 *    `getMyVitals()` passes no number, so `fixed-limit` cannot see it, and the
 *    array carries no total: `/my-vitals` showed 10 of 45 records. A method
 *    that answers a page (with its total) is not reported.
 *  - `payload-length-count` (QA D1380, round 32): a count or total read from
 *    the length of a list in a server payload (`c?.medications?.length ?? 0`
 *    in a member named like a count). The chart review badges said
 *    MÉDICAMENTS 20 for 667 prescriptions: the payload held one page. A
 *    count is the server's total (`totals`, `totalElements`).
 *  - `server-limit` (QA D1399, round 33 — {@link serverLimitFindings}): a read of a
 *    PatientPortalController list whose row limit has a server DEFAULT, without an
 *    `offset`. No number at the call site, none in the method: "Médicaments passés"
 *    stopped at 20 of 249 and the proxy tab at active + 50. Page it with the total.
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
const FIXED_LIMIT_POSITIONAL = /\.((?:list|search|get|find)[\w$]*)\(\s*(\d+)\s*\)/g;
/** A method signature (parameters on one or more lines) up to its `: Observable<…> {`. */
const METHOD_SIGNATURE =
  /\n {2}(?:(?:private|public|protected|readonly|static|async|override)\s+)*([\w$]+)\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)\s*:\s*Observable<([^{;]*?)>\s*\{/g;
const DEFAULTED_LIMIT = /\b(limit|size|pageSize|max[A-Z][\w$]*)\s*(?::\s*number\s*)?=\s*(\d+)\b/;
/** `x?.y?.length ?? 0`: the length of an optional payload list. */
const PAYLOAD_LENGTH = /([\w$]+)\?\.([\w$]+)\?\.length\s*\?\?\s*0/g;
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
  for (const m of code.matchAll(FIXED_LIMIT_POSITIONAL)) {
    push(
      m.index,
      'fixed-limit',
      `${m[1]}:${m[2]}`,
      `${m[1]}(${m[2]}) reads at most ${m[2]} rows (QA D1362): read every page, or count from the server's total`,
    );
  }
  for (const m of code.matchAll(METHOD_SIGNATURE)) {
    const limit = DEFAULTED_LIMIT.exec(m[2]);
    if (!limit || !/\[\]\s*$/.test(m[3].trim())) continue;
    // Anchored on the method itself (the match starts on the line before it).
    const offset = m.index + 1;
    const line = lineOf(code, offset);
    out.push({
      line,
      rule: 'default-limit',
      anchor: `${m[1]}:${limit[1]}=${limit[2]}`,
      message:
        `${m[1]}(${limit[1]} = ${limit[2]}) answers at most ${limit[2]} rows and no total, and a call ` +
        'without the argument cannot be seen (QA D1379): answer a page with its total, or read every page',
    });
  }
  for (const m of code.matchAll(PAYLOAD_LENGTH)) {
    const line = lineOf(code, m.index);
    const member = memberAt(lines, line - 1);
    if (!/count|total/i.test(member)) continue;
    push(
      m.index,
      'payload-length-count',
      `${m[1]}.${m[2]}`,
      `${m[1]}?.${m[2]}?.length counts the rows a payload carries, not every row (QA D1380): use the server's total`,
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

/** `x.length - 4` inside an interpolation, a binding or a control-flow expression: rows on hand less rows shown. */
const SLICE_COUNT = /([\w$.()!?\]]+?)\.length\s*-\s*(\d+)\b/g;

/**
 * QA D1362 (round 31): a "+ N more" counted in a template from the rows the
 * page loaded. An index into the list (`list[list.length - 1]`) is not a
 * count and is not reported.
 *
 * @param {string} html the template
 * @returns {{line:number, rule:string, anchor:string, message:string}[]}
 */
export function sliceCountFindings(html) {
  const out = [];
  const code = html.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  for (const m of code.matchAll(SLICE_COUNT)) {
    const before = code.slice(Math.max(0, m.index - 1), m.index);
    const after = code.slice(m.index + m[0].length).trimStart();
    if (before === '[' || after.startsWith(']')) continue;
    out.push({
      line: lineOf(code, m.index),
      rule: 'slice-count',
      anchor: `${m[1]}:${m[2]}`,
      message:
        `${m[1]}.length - ${m[2]} counts "more" from the rows on hand (QA D1362): ` +
        'use moreCount(serverTotal, shown) (src/app/shared/more-count.ts)',
    });
  }
  return out;
}

/** The GET paths of a controller whose method takes `@RequestParam(defaultValue = "N") int limit`. */
export function serverLimitedPaths(controllerSource) {
  const paths = [];
  const mapping = /@GetMapping\(\s*(?:value\s*=\s*)?"([^"]+)"[^)]*\)/g;
  for (const m of controllerSource.matchAll(mapping)) {
    const rest = controllerSource.slice(m.index + m[0].length);
    const signature = rest.slice(0, rest.search(/\)\s*(?:throws [\w, ]+)?\{/));
    if (/@RequestParam\(\s*defaultValue\s*=\s*"\d+"\s*\)\s*int\s+limit\b/.test(signature)) {
      paths.push(m[1]);
    }
  }
  return paths;
}

/**
 * QA D1399: a read of one of those paths (`${this.base}/medications`) with no
 * `offset` in the call. The paths are read from the controller, so a new limited
 * list is covered the day it is added.
 */
export function serverLimitFindings(source, limitedPaths) {
  const lines = source.split(/\r?\n/);
  const found = [];
  for (const path of limitedPaths) {
    const spelled = path.replace(/\{(\w+)\}/g, (_, name) => '${' + name + '}');
    const literal = '`${this.base}' + spelled + '`';
    lines.forEach((line, index) => {
      if (!line.includes(literal)) return;
      // A write to the same path is not a read of the list.
      if (/\.(post|put|patch|delete)\b/.test(`${lines[index - 1] ?? ''} ${line}`)) return;
      const call = lines.slice(index, index + 4).join(' ');
      if (!/\boffset\b/.test(call)) {
        found.push({
          line: index + 1,
          rule: 'server-limit',
          anchor: `${memberAt(lines, index)}:${path}`,
          message: `${path} has a server default limit and no offset here (QA D1399): page it with the total, or pin a preview`,
        });
      }
    });
  }
  return found;
}
