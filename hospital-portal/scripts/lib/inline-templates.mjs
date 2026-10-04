/**
 * The inline `template:` of every `@Component` in a TypeScript source, found
 * with the TypeScript compiler's own parser.
 *
 * The enum gates read `.html` files. A component whose markup lives in its
 * decorator was invisible to both — the enum-coverage gate could not see an
 * `| enumLabel: 'x'` there, and the raw-enum gate could not see a
 * `{{ row.status }}`. Widening the regex scan to `.ts` was tried twice in #660
 * and withdrawn twice:
 *
 *  - scanning the raw file recorded the pipe's own TSDoc examples
 *    (`{{ rx.status | enumLabel: 'prescriptionStatus' }}` in a `/** … *\/`)
 *    as call sites, so a doc example could fail the build;
 *  - blanking comments first with a hand-written lexer also blanked the
 *    single-quoted domain argument the regex needs — an apostrophe in a
 *    French string (`Aujourd'hui`) or a `//` inside a backtick string throws a
 *    naive blanker out of step for the rest of the file.
 *
 * Both are tokenizer problems, so this asks the tokenizer. The AST has no
 * comments in it, a template literal is one node however many apostrophes or
 * `//` it contains, and only the `template` property of an object literal
 * passed to a decorator named `Component` counts — a `template:` field on a
 * plain object (nurse-task.service.ts has one) is data, not markup.
 *
 * `typescript` is already a devDependency (the Angular compiler needs it), so
 * this adds nothing to install.
 */
import { readFileSync } from 'node:fs';
import ts from 'typescript';

import { walk } from './walk.mjs';

/**
 * Every Angular template under `dir`: each `.html` file whole, and each inline
 * `template:` of a non-spec `.ts` file. `lineOffset` turns a line within
 * `text` into a line of `file` (`line + lineOffset`), so a gate can name the
 * place to go either way.
 *
 * Specs are skipped: a test host's inline template is fixture markup, and a
 * spec that renders `{{ row.status }}` to assert the raw token is not a raw
 * render on anybody's screen.
 *
 * @returns {{ file: string, text: string, lineOffset: number }[]}
 */
export function templatesIn(dir) {
  const out = [];
  for (const file of walk(dir, ['.html', '.ts'])) {
    if (file.endsWith('.html')) {
      out.push({ file, text: readFileSync(file, 'utf8'), lineOffset: 0 });
    } else if (!file.endsWith('.spec.ts')) {
      const source = readFileSync(file, 'utf8');
      // Cheap pre-filter: parsing all ~900 .ts files costs seconds, and only
      // a file that says `template` can hold one.
      if (!source.includes('template')) continue;
      for (const { text, line } of inlineTemplates(source, file)) {
        out.push({ file, text, lineOffset: line - 1 });
      }
    }
  }
  return out;
}

/**
 * @param {string} source the file's text
 * @param {string} [fileName] only used in the parser's diagnostics
 * @returns {{ text: string, line: number }[]} one entry per inline template:
 *   its text (escapes resolved, as Angular will compile it) and the 1-based
 *   line of the file its first character is on. A `${…}` substitution — rare
 *   in a decorator, and never markup — is replaced by a space.
 */
export function inlineTemplates(source, fileName = 'inline.ts') {
  const file = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true);
  const found = [];

  const visit = (node) => {
    if (ts.isDecorator(node) && isComponentCall(node.expression)) {
      const [meta] = node.expression.arguments;
      if (meta && ts.isObjectLiteralExpression(meta)) {
        for (const prop of meta.properties) {
          if (!ts.isPropertyAssignment(prop) || propName(prop) !== 'template') continue;
          const text = literalText(prop.initializer);
          if (text === null) continue;
          // +1 skips the opening quote or backtick, so line N of the template
          // is line (start + N - 1) of the file.
          const start = prop.initializer.getStart(file) + 1;
          found.push({ text, line: file.getLineAndCharacterOfPosition(start).line + 1 });
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(file);
  return found;
}

/** `@Component(…)`, or `@core.Component(…)` from a namespace import. */
function isComponentCall(expr) {
  if (!ts.isCallExpression(expr)) return false;
  const callee = expr.expression;
  if (ts.isIdentifier(callee)) return callee.text === 'Component';
  return ts.isPropertyAccessExpression(callee) && callee.name.text === 'Component';
}

function propName(prop) {
  const { name } = prop;
  if (ts.isIdentifier(name) || ts.isStringLiteral(name)) return name.text;
  return null;
}

/**
 * The text of a string or template literal, or null for anything computed
 * (a constant, a function call) — which the gate cannot read and which no
 * component in src/app uses.
 */
function literalText(node) {
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text;
  if (ts.isTemplateExpression(node)) {
    return node.head.text + node.templateSpans.map((span) => ` ${span.literal.text}`).join('');
  }
  return null;
}
