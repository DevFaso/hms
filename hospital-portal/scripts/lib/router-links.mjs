// QA D956: an in-app link must lead to a route that exists.
//
// The doctor worklist's "Demander des Analyses" pointed at /lab/new, which no
// route declares, so the wildcard route sent the doctor back to the dashboard.
// These helpers read every route path declared in app.routes.ts and every
// literal link the templates and components build, so a test can fail on a
// link that no route can match.

/** Every full route path declared in the routes file (nested children joined). */
export function declaredRoutePaths(routesSource) {
  const paths = new Set();
  const stack = [];
  const token = /\{|\}|path:\s*'([^']*)'/g;
  for (const m of routesSource.matchAll(token)) {
    if (m[0] === '{') {
      stack.push(null);
    } else if (m[0] === '}') {
      stack.pop();
    } else if (stack.length) {
      stack[stack.length - 1] = m[1];
      const full = stack.filter((p) => p !== null && p !== '').join('/');
      paths.add(full);
    }
  }
  return [...paths];
}

/** The segments of a link: literal strings as written, anything computed as null. */
function linkSegments(literalParts) {
  return literalParts
    .flatMap((part) => (part === null ? [null] : part.split('/')))
    .filter((seg) => seg !== '');
}

/** Literal links found in a template or a component source, as segment lists. */
export function literalLinks(source) {
  const links = [];
  for (const m of source.matchAll(/\brouterLink="(\/[^"{}]*)"/g)) {
    links.push({ text: m[1], segments: linkSegments([m[1].split(/[?#]/)[0]]) });
  }
  const arrays = [
    ...source.matchAll(/\[routerLink\]="\[([^\]"]*)\]"/g),
    ...source.matchAll(/\.navigate\(\s*\[([^\]]*)\]/g),
  ];
  for (const m of arrays) {
    const items = m[1]
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    if (!items.length || !/^'\//.test(items[0])) continue;
    const parts = items.map((item) => (/^'[^']*'$/.test(item) ? item.slice(1, -1) : null));
    links.push({ text: `[${m[1].trim()}]`, segments: linkSegments(parts) });
  }
  for (const m of source.matchAll(/\.navigateByUrl\(\s*'(\/[^'?#]*)/g)) {
    links.push({ text: m[1], segments: linkSegments([m[1]]) });
  }
  return links;
}

/** True when some declared route can match the link's segments. */
export function linkMatchesSomeRoute(segments, routePaths) {
  return routePaths.some((route) => {
    if (route === '**') return false;
    const parts = route === '' ? [] : route.split('/');
    if (parts.length !== segments.length) return false;
    return parts.every(
      (part, i) => part.startsWith(':') || segments[i] === null || part === segments[i],
    );
  });
}
