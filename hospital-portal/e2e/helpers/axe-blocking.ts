import type { Result } from 'axe-core';

/** Same approved logotype-only contrast exception as the existing routed suite.
 * Never exempt another rule, another element, or other nodes in a mixed result.
 */
export function blockingAxeViolations(violations: Result[]): Result[] {
  return violations
    .map((violation) =>
      violation.id === 'color-contrast'
        ? {
            ...violation,
            nodes: violation.nodes.filter(
              (node) => !node.html.includes('brand-word') && !node.html.includes('brand-accent'),
            ),
          }
        : violation,
    )
    .filter(
      (violation) =>
        violation.nodes.length > 0 &&
        (violation.impact === 'serious' || violation.impact === 'critical'),
    );
}
