// QA D956 guard: every literal in-app link (routerLink, router.navigate,
// navigateByUrl) in the portal matches a route app.routes.ts declares. A link
// to a route nobody declared falls through to the wildcard and silently
// lands the user on the dashboard, as "Demander des Analyses" did (/lab/new).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';
import { declaredRoutePaths, linkMatchesSomeRoute, literalLinks } from './router-links.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

test('QA D956: no in-app link leads to an undeclared route', () => {
  const routes = declaredRoutePaths(readFileSync(join(appDir, 'app.routes.ts'), 'utf8'));
  assert.ok(routes.length > 100, `read ${routes.length} routes`);
  const dead = [];
  let seen = 0;
  for (const file of walk(appDir, ['.html', '.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    for (const link of literalLinks(readFileSync(file, 'utf8'))) {
      seen++;
      if (!linkMatchesSomeRoute(link.segments, routes)) {
        dead.push(`${relative(appDir, file).split('\\').join('/')}: ${link.text}`);
      }
    }
  }
  assert.ok(seen > 150, `scanned ${seen} links`);
  assert.deepEqual(dead, []);
});

test('QA D956 guard: the scan sees the shape the defect had', () => {
  const routes = declaredRoutePaths(`[
    { path: 'lab', loadComponent: () => import('./lab').then((m) => m.Lab) },
    { path: 'patients', children: [{ path: '' }, { path: ':id' }] },
  ]`);
  assert.deepEqual(routes.sort(), ['lab', 'patients', 'patients/:id']);
  const [dead] = literalLinks(
    `<a [routerLink]="['/lab/new']" [queryParams]="{ patientId: x }"></a>`,
  );
  assert.equal(linkMatchesSomeRoute(dead.segments, routes), false);
  const [ok] = literalLinks(`<a [routerLink]="['/patients', item.patientId]"></a>`);
  assert.equal(linkMatchesSomeRoute(ok.segments, routes), true);
  const [nav] = literalLinks(`this.router.navigate(['/lab'], { queryParams })`);
  assert.equal(linkMatchesSomeRoute(nav.segments, routes), true);
});
