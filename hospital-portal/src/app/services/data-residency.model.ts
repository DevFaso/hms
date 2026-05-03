/**
 * Region codes the platform recognises for tenant data-residency
 * tagging (MVP-9). Mirrors `OrganizationRegion` on the backend; keep
 * the two enums in lock-step when adding a code.
 */
export type OrganizationRegion =
  | 'BF'
  | 'CI'
  | 'SN'
  | 'GA'
  | 'CM'
  | 'BJ'
  | 'TG'
  | 'ML'
  | 'NE'
  | 'ML_OAPI'
  | 'EU'
  | 'US'
  | 'OTHER';

/**
 * Per-organization snapshot returned by the Data Residency console.
 */
export interface OrganizationRegionRow {
  organizationId: string;
  organizationName: string;
  organizationCode: string;
  region: OrganizationRegion;
}

/**
 * Body for the region-update endpoint.
 */
export interface OrganizationRegionUpdate {
  region: OrganizationRegion;
  reason?: string;
}
