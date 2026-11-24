import ts from 'typescript';
import { compileString } from 'sass';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { PORTAL_DIR } from './template-gate.mjs';

// An exception may cite its own component CSS or the real global stylesheet.
export function controlStyleSources(templates) {
  const options = {
    loadPaths: [resolve(PORTAL_DIR, 'src'), resolve(PORTAL_DIR, 'node_modules')],
    logger: { warn() {}, debug() {} },
  };
  const global = compileString(
    readFileSync(resolve(PORTAL_DIR, 'src/styles.scss'), 'utf8'),
    options,
  ).css;
  const result = new Map();
  for (const template of templates) {
    const file = template.rel.replace(/\.html$/, '.ts');
    const path = resolve(PORTAL_DIR, file);
    const source = existsSync(path) ? readFileSync(path, 'utf8') : '';
    const tree = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
    const styles = [];
    const visit = (node) => {
      if (
        ts.isPropertyAssignment(node) &&
        ['styleUrl', 'styleUrls'].includes(node.name.getText(tree))
      ) {
        const values = ts.isArrayLiteralExpression(node.initializer)
          ? node.initializer.elements
          : [node.initializer];
        for (const value of values) {
          if (ts.isStringLiteral(value))
            styles.push(readFileSync(resolve(dirname(path), value.text), 'utf8'));
        }
      }
      if (
        ts.isPropertyAssignment(node) &&
        node.name.getText(tree) === 'styles' &&
        ts.isArrayLiteralExpression(node.initializer)
      ) {
        for (const value of node.initializer.elements) {
          if (ts.isStringLiteral(value) || ts.isNoSubstitutionTemplateLiteral(value))
            styles.push(value.text);
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(tree);
    result.set(
      template.rel,
      global +
        '\n' +
        styles
          .map(
            (style) =>
              compileString(style, {
                ...options,
                loadPaths: [dirname(path), ...options.loadPaths],
              }).css,
          )
          .join('\n'),
    );
  }
  return result;
}
