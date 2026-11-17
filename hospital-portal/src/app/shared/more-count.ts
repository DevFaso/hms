/**
 * QA D1362 (round 31, the D1347 class): how many rows a card does NOT show.
 *
 * The "Mon dossier médical" lab card loaded 50 results, showed 4 and said
 * "+ 46 plus" when the patient had 121 more: the count was taken from the
 * rows on hand, not from the server. A "+ N more" is always the server's
 * total less the rows on screen. When the total is unknown (the server
 * could not count) there is no number to show: 0, and the card's link to
 * the full list stays.
 *
 * @param total the server's count of every row (or the length of a list the
 *   server sends WHOLE), null/undefined when unknown
 * @param shown how many rows the card displays
 */
export function moreCount(total: number | null | undefined, shown: number): number {
  if (total === null || total === undefined || !Number.isFinite(total)) return 0;
  return Math.max(0, Math.floor(total) - Math.max(0, shown));
}
