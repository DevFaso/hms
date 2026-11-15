import fs from 'node:fs';
import path from 'node:path';
import ts from 'typescript';

export interface RoutedPage {
  path: string;
  roles: string[];
  parameterized: boolean;
}

/** Read route declarations without importing Angular or executing route guards. */
export function routedPages(): RoutedPage[] {
  const filename = path.resolve(__dirname, '../../src/app/app.routes.ts');
  const source = ts.createSourceFile(
    filename,
    fs.readFileSync(filename, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
  );
  const pages: RoutedPage[] = [];
  function property(node: ts.ObjectLiteralExpression, key: string) {
    return node.properties.find(
      (entry): entry is ts.PropertyAssignment =>
        ts.isPropertyAssignment(entry) && entry.name.getText(source) === key,
    )?.initializer;
  }
  function walk(array: ts.ArrayLiteralExpression, prefix: string, inherited: string[]) {
    for (const node of array.elements) {
      if (!ts.isObjectLiteralExpression(node)) throw new Error('Uninventoried non-literal route');
      const routePath = property(node, 'path');
      if (!routePath || !ts.isStringLiteral(routePath)) throw new Error('Uninventoried route path');
      const fullPath = `${prefix}/${routePath.text}`.replace(/\/+/g, '/').replace(/\/$/, '') || '/';
      const data = property(node, 'data');
      const rolesNode =
        data && ts.isObjectLiteralExpression(data) ? property(data, 'roles') : undefined;
      const declared =
        rolesNode && ts.isArrayLiteralExpression(rolesNode)
          ? rolesNode.elements.map((role) => {
              if (!ts.isStringLiteral(role)) throw new Error(`Non-literal role on ${fullPath}`);
              return role.text;
            })
          : [];
      const roles =
        inherited.length && declared.length
          ? inherited.filter((role) => declared.includes(role))
          : declared.length
            ? declared
            : inherited;
      const children = property(node, 'children');
      if (property(node, 'loadChildren'))
        throw new Error(`Lazy route inventory needed: ${fullPath}`);
      if (children && !ts.isArrayLiteralExpression(children))
        throw new Error(`Non-literal children: ${fullPath}`);
      if (children && ts.isArrayLiteralExpression(children)) walk(children, fullPath, roles);
      // Shell/layout parents are exercised through their child screens; aliases are not pages.
      if (
        !children &&
        !property(node, 'redirectTo') &&
        routePath.text !== '**' &&
        (property(node, 'loadComponent') || property(node, 'component'))
      ) {
        pages.push({ path: fullPath, roles, parameterized: fullPath.includes(':') });
      }
    }
  }
  let found = false;
  source.forEachChild((node) => {
    if (!ts.isVariableStatement(node)) return;
    for (const declaration of node.declarationList.declarations) {
      if (
        declaration.name.getText(source) === 'routes' &&
        declaration.initializer &&
        ts.isArrayLiteralExpression(declaration.initializer)
      ) {
        found = true;
        walk(declaration.initializer, '', []);
      }
    }
  });
  if (!found || pages.length === 0) throw new Error('No portal route inventory found');
  return pages;
}
