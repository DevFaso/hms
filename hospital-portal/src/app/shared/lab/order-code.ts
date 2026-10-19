/**
 * The order code a person reads: "#" and the first 8 characters of the
 * order id, upper-cased. The full id stays in the API, the search and the
 * title attribute. QA D177 / D227: the lab screens showed the raw UUID.
 */
export function shortOrderCode(code: string | null | undefined): string {
  const trimmed = (code ?? '').trim();
  return trimmed ? '#' + trimmed.slice(0, 8).toUpperCase() : '—';
}
