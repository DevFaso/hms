/**
 * QA D1179 (round 21): an e-mail address is checked where it is typed.
 *
 * "Envoyer la facture" sent `pas-un-email` to the server, and the clerk read
 * the server's raw bean-validation text ("doit être une adresse électronique
 * syntaxiquement correcte") in a toast, away from the field. The form now
 * refuses a malformed address itself, on the field, in the reader's language
 * (refuseFields), before anything is sent. The server keeps its own check.
 *
 * The shape checked is the one a person can mistype: one `@`, something before
 * it, a domain with a dot that neither starts nor ends it, no spaces. Written
 * without a regular expression so no input can make it backtrack.
 */
export function isEmailAddress(value: string): boolean {
  const address = value.trim();
  if (address.length === 0 || address.length > 254) return false;
  if ([...address].some((ch) => ch.trim() === '')) return false;
  const at = address.indexOf('@');
  if (at <= 0 || at !== address.lastIndexOf('@')) return false;
  const domain = address.slice(at + 1);
  const dot = domain.lastIndexOf('.');
  return dot > 0 && dot < domain.length - 1 && !domain.includes('..');
}

/** The addresses of a To / Cc field: separated by commas, semicolons or spaces. */
export function emailAddresses(raw: string | null | undefined): string[] {
  return (raw ?? '')
    .split(/[\s,;]+/)
    .map((address) => address.trim())
    .filter((address) => address !== '');
}

/** The addresses of `list` that are not e-mail addresses, in the order typed. */
export function invalidEmailAddresses(list: readonly string[]): string[] {
  return list.filter((address) => !isEmailAddress(address));
}
