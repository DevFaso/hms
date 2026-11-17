const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The order code a person reads. QA D291 (D177 / D227): the server now sends
 * the order's own number (LAB-000123), shown as it is. An order the server
 * has no number for still arrives as its id, shown as "#" and the id's
 * first 8 characters, upper-cased; the full id stays in the title attribute.
 */
export function shortOrderCode(code: string | null | undefined): string {
  const trimmed = (code ?? '').trim();
  if (!trimmed) return '—';
  return UUID_SHAPE.test(trimmed) ? '#' + trimmed.slice(0, 8).toUpperCase() : trimmed;
}
