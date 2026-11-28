/**
 * QA D4071 (r81): the platform's feature keys with the name a person reads. One list for the
 * feature-flag console and the subscription plans, so a key reads the same on both. A key the
 * list does not know is shown as it is stored.
 */
export const FEATURE_KEY_CATALOG: Readonly<Record<string, { title: string; description: string }>> =
  {
    'ff.platform.billing': {
      title: 'FLAGS.CATALOG.BILLING_TITLE',
      description: 'FLAGS.CATALOG.BILLING_DESCRIPTION',
    },
    'ff.platform.ehr': {
      title: 'FLAGS.CATALOG.EHR_TITLE',
      description: 'FLAGS.CATALOG.EHR_DESCRIPTION',
    },
    'ff.platform.inventory': {
      title: 'FLAGS.CATALOG.INVENTORY_TITLE',
      description: 'FLAGS.CATALOG.INVENTORY_DESCRIPTION',
    },
  };

/** The translation key of a feature key's name, or null when the key has none. */
export function featureKeyTitleKey(key: string | null | undefined): string | null {
  return FEATURE_KEY_CATALOG[(key ?? '').trim().toLowerCase()]?.title ?? null;
}

/** A stored comma-separated key list as trimmed keys, blanks dropped. */
export function splitFeatureKeys(raw: string | null | undefined): string[] {
  return (raw ?? '')
    .split(',')
    .map((key) => key.trim())
    .filter((key) => key.length > 0);
}
