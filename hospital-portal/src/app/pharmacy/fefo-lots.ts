/**
 * First expired, first out: the one lot rule every pharmacy screen uses
 * (dispensing, stock transfers), the same rule as the server's FefoLotPolicy.
 *
 * QA D964 then D1029 (round 17): the screens pre-selected "the shortest-dated
 * lot that covers the quantity", so 130 tablets left the 2028 lot while 120
 * stayed in the 2027 lot, and no reason was asked. Now the shortest-dated lot
 * with stock is always the first choice, a later lot needs a reason while an
 * earlier one still holds stock, and a quantity larger than the first lot is
 * split across the lots in expiry order.
 *
 * `scripts/lib/fefo-lots.test.mjs` fails when a screen picks a lot by
 * "covers the quantity" again instead of using these helpers.
 */

/** The fields of a stock lot the rule reads. */
export interface FefoLot {
  id: string;
  expiryDate: string;
  remainingQuantity: number;
  inventoryItemId?: string;
}

/** One part of a fill split across lots: how much comes from which lot. */
export interface FefoPart<L extends FefoLot> {
  lot: L;
  quantity: number;
}

/** Usable lots in expiry order (the caller has already dropped expired and empty lots). */
function byExpiry<L extends FefoLot>(lots: readonly L[]): L[] {
  return [...lots]
    .filter((lot) => lot.remainingQuantity > 0)
    .sort((a, b) => a.expiryDate.localeCompare(b.expiryDate));
}

/** The lot to take first: the shortest-dated lot with stock, whatever the quantity. */
export function fefoFirstLot<L extends FefoLot>(lots: readonly L[]): L | undefined {
  return byExpiry(lots)[0];
}

/**
 * A lot of the same item that expires before `chosen` and still holds stock:
 * while one exists, taking `chosen` skips FEFO and needs a reason.
 */
export function earlierLotWithStock<L extends FefoLot>(
  lots: readonly L[],
  chosen: L | undefined,
): L | undefined {
  if (!chosen?.expiryDate) return undefined;
  return byExpiry(lots).find(
    (lot) =>
      lot.id !== chosen.id &&
      (!chosen.inventoryItemId || lot.inventoryItemId === chosen.inventoryItemId) &&
      lot.expiryDate < chosen.expiryDate,
  );
}

/**
 * `quantity` taken in expiry order: the first lot emptied, then the next, of
 * the same item as the first lot. Empty when the lots together cannot cover
 * it (the caller then lets the server refuse the shortfall as it always did).
 */
export function fefoAllocation<L extends FefoLot>(
  lots: readonly L[],
  quantity: number,
): FefoPart<L>[] {
  const ordered = byExpiry(lots);
  const first = ordered[0];
  if (!first || !(quantity > 0)) return [];
  const sameItem = ordered.filter(
    (lot) => !first.inventoryItemId || lot.inventoryItemId === first.inventoryItemId,
  );
  const parts: FefoPart<L>[] = [];
  let left = quantity;
  for (const lot of sameItem) {
    if (left <= 0) break;
    const take = Math.min(lot.remainingQuantity, left);
    parts.push({ lot, quantity: take });
    // quantity columns are numeric(12,2): no floating residue between parts
    left = Math.round((left - take) * 100) / 100;
  }
  return left > 0 ? [] : parts;
}
