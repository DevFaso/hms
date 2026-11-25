import ts from 'typescript';
import { compile, compileString } from 'sass';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { PORTAL_DIR } from './template-gate.mjs';
import { componentSources } from './component-sources.mjs';
import { controlsIn } from './form-control-design.mjs';

// Follow actual @Component metadata; template and component filenames may differ.
export function controlTemplates(templates, components = componentSources(), root = PORTAL_DIR) {
  const owners = [];
  for (const component of components) {
    if (!component.text.includes('template')) continue;
    const tree = ts.createSourceFile(component.file, component.text, ts.ScriptTarget.Latest, true);
    const visit = (node) => {
      if (ts.isClassDeclaration(node)) {
        for (const decorator of ts.getDecorators(node) ?? []) {
          const call = decorator.expression;
          if (
            !ts.isCallExpression(call) ||
            !/(?:^|\.)Component$/.test(call.expression.getText(tree))
          )
            continue;
          const meta = call.arguments[0];
          if (!meta || !ts.isObjectLiteralExpression(meta)) continue;
          const values = new Map(
            meta.properties
              .filter(ts.isPropertyAssignment)
              .map((p) => [p.name.getText(tree).replace(/^['"]|['"]$/g, ''), p.initializer]),
          );
          const literal = (value) =>
            value && (ts.isStringLiteral(value) || ts.isNoSubstitutionTemplateLiteral(value))
              ? value.text
              : null;
          const owner = { file: component.file, identity: node.name?.text, styles: [] };
          for (const key of ['styleUrl', 'styleUrls', 'styles']) {
            const value = values.get(key);
            for (const item of value && ts.isArrayLiteralExpression(value)
              ? value.elements
              : value
                ? [value]
                : []) {
              const text = literal(item);
              if (text !== null)
                owner.styles.push(
                  key === 'styles'
                    ? { text }
                    : { file: resolve(root, dirname(component.file), text) },
                );
            }
          }
          const url = literal(values.get('templateUrl'));
          if (url !== null)
            owners.push({ ...owner, template: resolve(root, dirname(component.file), url) });
          const inline = values.get('template');
          if (inline)
            owners.push({
              ...owner,
              template: resolve(root, component.file),
              lineOffset: tree.getLineAndCharacterOfPosition(inline.getStart(tree) + 1).line,
            });
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(tree);
  }
  return templates.map((template) => {
    const matches = owners.filter(
      (owner) =>
        owner.template === resolve(root, template.rel) &&
        (owner.lineOffset === undefined || owner.lineOffset === (template.lineOffset ?? 0)),
    );
    if (matches.length > 1)
      throw new Error(`${template.rel}: shared template needs component-specific style evidence`);
    const styleOwner = matches[0];
    if (template.rel.endsWith('.ts') && !styleOwner?.identity)
      throw new Error(`${template.rel}: inline template needs a named component identity`);
    return {
      ...template,
      styleOwner,
      componentIdentity: template.rel.endsWith('.ts') ? styleOwner.identity : undefined,
    };
  });
}

// Compile only scoped templates with visual exceptions, by the real Sass URL.
export function controlStyleSources(
  templates,
  { exceptions = {}, inScope = () => true, root = PORTAL_DIR } = {},
) {
  const options = {
    loadPaths: [resolve(root, 'src'), resolve(root, 'node_modules')],
    logger: { warn() {}, debug() {} },
  };
  let global;
  const result = new Map();
  for (const template of templates) {
    if (
      !inScope(template.rel) ||
      !controlsIn(template.text, template.rel, template.componentIdentity).some(
        (control) => exceptions[control.key],
      )
    )
      continue;
    if (!template.styleOwner)
      throw new Error(`${template.rel}: no @Component owns this exception's template`);
    global ??= compile(resolve(root, 'src/styles.scss'), options).css;
    const owner = template.styleOwner;
    const css = owner.styles
      .map((style) =>
        style.file
          ? compile(style.file, options).css
          : compileString(style.text, { ...options, url: pathToFileURL(resolve(root, owner.file)) })
              .css,
      )
      .join('\n');
    result.set(
      template.componentIdentity ? `${template.rel}::${template.componentIdentity}` : template.rel,
      `${global}\n${css}`,
    );
  }
  return result;
}
