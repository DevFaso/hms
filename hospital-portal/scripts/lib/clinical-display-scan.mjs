// X1: locale-owned punctuation, age units and generated medication text.
import ts from 'typescript';

export function templateDisplayProblems(text) {
  const problems = [];
  if (/\{\{[^}]*daysSupply[^}]*\}\}\s*(?:d\b|days?\b)/u.test(text)) {
    problems.push('D2009: supply duration has a literal day unit');
  }
  if (/polypharmacyDetected/u.test(text) && /\.warnings\b/u.test(text)) {
    problems.push('D2010: polypharmacy warning has both local and server presentation owners');
  }
  if (/[“«]\s*\{\{/u.test(text)) {
    problems.push('D1880: quote/value punctuation must belong to a translation');
  }
  if (/\{\{\s*['"]COMMON\.COLON['"]\s*\|\s*translate\s*\}\}\{\{/u.test(text)) {
    problems.push('D1883: translated colon glued to its value');
  }
  return problems;
}

export function ageUnitProblems(text) {
  const problems = [];
  const source = ts.createSourceFile('component.ts', text, ts.ScriptTarget.Latest, true);
  const visit = (node) => {
    if (
      ts.isMethodDeclaration(node) ||
      ts.isFunctionDeclaration(node) ||
      ts.isArrowFunction(node)
    ) {
      const body = node.body?.getText(source) ?? '';
      if (
        body.includes('ageUnit') &&
        body.includes('AGE_UNIT_MONTHS') &&
        !body.includes('AGE_UNIT_YEARS')
      ) {
        problems.push('D1881: age-band formatter labels months but omits years');
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return problems;
}

export function generatedTextProblems(text) {
  const problems = [];
  if (/(?:\.source\(\s*|String\s+source\s*=\s*)"[^"\n]*[a-zA-Z] [a-zA-Z][^"\n]*"/u.test(text)) {
    problems.push('D1574: generated source label is literal prose');
  }
  if (/warnings\.add\([^;]*"[^"\n]*[a-zA-Z] [a-zA-Z][^"\n]*"/u.test(text)) {
    problems.push('D1574: warning list composes literal prose');
  }
  if (/\.duration\([^;]*" days"/u.test(text)) {
    problems.push('D1574: generated duration hard-codes days');
  }
  return problems;
}
