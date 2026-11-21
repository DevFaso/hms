import ts from 'typescript';

/**
 * QA D2927 (r66): a nav entry named the accountant and the billing specialist in its `roles`, but
 * sat inside an `if (this.hasAnyRole([...]))` block whose list held neither. The entry read as a
 * mirror of its route guard and reached nobody new: finance could only type the URL.
 *
 * Reads the shell's nav builder and answers, for every entry that names roles: which of those
 * roles can never see it, because no push of that route sits behind gates that admit the role.
 */

/** The string literals of an array literal, or null when it is not one of plain strings. */
function stringArray(node) {
  if (!node || !ts.isArrayLiteralExpression(node)) return null;
  const out = [];
  for (const el of node.elements) {
    if (!ts.isStringLiteralLike(el)) return null;
    out.push(el.text);
  }
  return out;
}

/** The roles of every `hasAnyRole([...])` call in a condition: the most a gate can admit. */
function gateRoles(condition, ast) {
  const roles = new Set();
  let found = false;
  function visit(node) {
    if (ts.isCallExpression(node) && node.expression.getText(ast).endsWith('hasAnyRole')) {
      const listed = stringArray(node.arguments[0]);
      if (listed) {
        found = true;
        for (const r of listed) roles.add(r);
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(condition);
  return found ? roles : null;
}

function property(objectLiteral, name, ast) {
  return objectLiteral.properties.find(
    (p) => ts.isPropertyAssignment(p) && p.name.getText(ast) === name,
  );
}

/** Every nav entry literal (an object with a `route` and a `translationKey`), with its gates. */
export function navEntries(source) {
  const ast = ts.createSourceFile('shell.ts', source, ts.ScriptTarget.Latest, true);
  const entries = [];
  function visit(node, gates) {
    let inner = gates;
    if (ts.isIfStatement(node)) {
      const roles = gateRoles(node.expression, ast);
      // Only the `then` branch sits behind the gate.
      visit(node.expression, gates);
      visit(node.thenStatement, roles ? [...gates, roles] : gates);
      if (node.elseStatement) visit(node.elseStatement, gates);
      return;
    }
    if (ts.isObjectLiteralExpression(node)) {
      const route = property(node, 'route', ast);
      const key = property(node, 'translationKey', ast);
      if (route && key && ts.isStringLiteralLike(route.initializer)) {
        const roles = property(node, 'roles', ast);
        entries.push({
          route: route.initializer.text,
          roles: roles ? stringArray(roles.initializer) : null,
          gates,
          line: ast.getLineAndCharacterOfPosition(node.getStart(ast)).line + 1,
        });
      }
    }
    ts.forEachChild(node, (child) => visit(child, inner));
  }
  visit(ast, []);
  return entries;
}

/** Whether `role` sees this entry: every gate around it admits the role, and so does its own list. */
function admits(entry, role) {
  return (
    entry.gates.every((gate) => gate.has(role)) && (!entry.roles || entry.roles.includes(role))
  );
}

/** `route ← role` for every role an entry names that no push of that route lets through. */
export function unreachableNamedRoles(source) {
  const entries = navEntries(source);
  const dead = new Set();
  for (const entry of entries) {
    for (const role of entry.roles ?? []) {
      const reached = entries.some((other) => other.route === entry.route && admits(other, role));
      if (!reached) dead.add(`${entry.route} ← ${role}`);
    }
  }
  return [...dead].sort();
}
