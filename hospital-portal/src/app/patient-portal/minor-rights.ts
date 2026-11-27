/**
 * QA D4269: what a patient may do alone on their own portal account, by age, as the server decides it
 * (`portalRights` on GET /me/patient/profile). The server refuses the same actions; this only lets a screen
 * hide or disable them and say why.
 */
export type PortalAgeBand = 'UNDER_13' | 'AGE_13_14' | 'AGE_15_17' | 'ADULT';

export interface PortalRights {
  band: PortalAgeBand;
  grantFamilyAccess: boolean;
  revokeFamilyAccess: boolean;
  closeSharing: boolean;
  reopenSharing: boolean;
  withdrawContactConsent: boolean;
  giveContactConsent: boolean;
}

/** Static key maps, not concatenation: the i18n gates scan string literals. */
const NOTICE_KEYS: Readonly<
  Record<'family' | 'sharing', Readonly<Record<PortalAgeBand, string | null>>>
> = {
  family: {
    UNDER_13: 'MINOR_RIGHTS.UNDER_13',
    AGE_13_14: 'MINOR_RIGHTS.AGE_13_14',
    AGE_15_17: 'MINOR_RIGHTS.FAMILY_15_17',
    ADULT: null,
  },
  sharing: {
    UNDER_13: 'MINOR_RIGHTS.UNDER_13',
    AGE_13_14: 'MINOR_RIGHTS.AGE_13_14',
    AGE_15_17: 'MINOR_RIGHTS.SHARING_15_17',
    ADULT: null,
  },
};

/** The explanation a minor reads on a screen, or null for an adult and while the rights are unknown. */
export function minorNoticeKey(
  rights: PortalRights | null | undefined,
  screen: 'family' | 'sharing',
): string | null {
  return rights ? (NOTICE_KEYS[screen][rights.band] ?? null) : null;
}
