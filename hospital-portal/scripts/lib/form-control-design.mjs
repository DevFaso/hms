import { createHash } from 'node:crypto';
import { parseTemplate, TmplAstRecursiveVisitor, tmplAstVisitAll } from '@angular/compiler';
import postcss from 'postcss';

const DESIGN_CLASSES = new Set(['keneya-form-control', 'filter-input', 'filter-select']);
const DESIGN_WRAPPERS = new Set(['field', 'form-group', 'modal-body', 'dialog-body', 'search-box']);
export const NATIVE_EXCEPTIONS = Object.freeze({
  hidden: 'Non-visible submitted value; visual form styling does not apply.',
  checkbox: 'Native binary control retains the checkbox interaction and shared focus styles.',
  radio: 'Native option control retains radio-group keyboard behavior and shared focus styles.',
  range: 'Native slider retains its scale/thumb design rather than text-field padding.',
  file: 'Native file chooser is an upload control; text-field styling would obscure its chooser button.',
  color: 'Native color swatch retains its color-picker affordance rather than text-field styling.',
});

export function controlsIn(text, file, componentIdentity = '', occurrences = new Map()) {
  const parsed = parseTemplate(text, file);
  if (parsed.errors?.length) throw new Error(`${file}: template could not be parsed`);
  const controls = [];
  const owner = componentIdentity ? `${file}::${componentIdentity}` : file;
  class Collector extends TmplAstRecursiveVisitor {
    ancestors = [];
    visitElement(node) {
      const ancestors = this.ancestors;
      const attributes = Object.fromEntries(node.attributes.map((a) => [a.name, a.value]));
      const classes = (attributes.class ?? '').split(/\s+/);
      if (['input', 'select', 'textarea'].includes(node.name)) {
        const opening = node.startSourceSpan.toString().replace(/\s+/g, ' ').trim();
        const fingerprint = createHash('sha256')
          .update(JSON.stringify({ opening, ancestors }))
          .digest('hex')
          .slice(0, 20);
        const occurrenceKey = `${owner}::${fingerprint}`;
        const occurrence = occurrences.get(occurrenceKey) ?? 0;
        occurrences.set(occurrenceKey, occurrence + 1);
        controls.push({
          file,
          key: `${owner}::${node.name}::${fingerprint}::${occurrence}`,
          index: controls.length,
          tag: node.name,
          attributes,
          line: node.sourceSpan.start.line + 1,
          start: node.startSourceSpan.start.offset,
          end: node.startSourceSpan.end.offset,
          designed:
            Object.hasOwn(attributes, 'appFormControl') ||
            classes.some((c) => DESIGN_CLASSES.has(c)) ||
            ancestors.some(
              (a) =>
                !['ng-container', 'ng-template'].includes(a.name) &&
                a.classes.some((c) => DESIGN_WRAPPERS.has(c)),
            ) ||
            ancestors.some((a) => a.name === 'mat-form-field'),
          nativeReason: NATIVE_EXCEPTIONS[attributes.type] ?? null,
        });
      }
      ancestors.push({ name: node.name, classes });
      super.visitElement(node);
      ancestors.pop();
    }
  }
  tmplAstVisitAll(new Collector(), parsed.nodes);
  return controls;
}

export function bareControlFindings(
  templates,
  exceptions = {},
  inScope = () => true,
  styles = new Map(),
) {
  const findings = [],
    seen = new Set();
  const occurrences = new Map();
  const selectorCache = new Map();
  for (const template of templates) {
    if (!inScope(template.rel)) continue;
    for (const control of controlsIn(
      template.text,
      template.rel,
      template.componentIdentity,
      occurrences,
    )) {
      const exception = exceptions[control.key];
      if (exception) {
        seen.add(control.key);
        if (typeof exception.reason !== 'string' || exception.reason.trim().length < 30)
          findings.push(`${control.key}: exception requires a specific reason`);
        const owner = template.componentIdentity
          ? `${template.rel}::${template.componentIdentity}`
          : template.rel;
        let selectors = selectorCache.get(owner);
        const normalize = (selector) => postcss.list.space(selector.trim()).join(' ');
        if (!selectors) {
          selectors = new Set();
          postcss.parse(styles.get(owner) ?? '').walkRules((rule) => {
            for (const selector of postcss.list.comma(rule.selector))
              selectors.add(normalize(selector));
          });
          selectorCache.set(owner, selectors);
        }
        if (
          !Array.isArray(exception.evidence) ||
          !exception.evidence.length ||
          !exception.evidence.every(
            (selector) =>
              typeof selector === 'string' &&
              selector.trim() &&
              postcss.list.comma(selector).every((part) => selectors.has(normalize(part))),
          )
        ) {
          findings.push(
            `${control.key}: exception requires existing component/global CSS selector evidence`,
          );
        }
        if (control.designed || control.nativeReason)
          findings.push(
            `${control.key}: obsolete exception; design-system or native-type coverage now applies`,
          );
      } else if (!control.designed && !control.nativeReason) {
        findings.push(
          `${control.file}:${control.line + (template.lineOffset ?? 0)}: bare ${control.tag}; adopt a design-system field or document a reviewed visual exception`,
        );
      }
    }
  }
  for (const key of Object.keys(exceptions))
    if (inScope(key.split('::')[0]) && !seen.has(key)) findings.push(`${key}: stale exception`);
  return findings;
}
