import { OrganizationRegion } from './data-residency.model';

/**
 * MVP-9c — per-region policy row from
 * GET /super-admin/data-residency/policies. Mirrors the backend
 * `RegionPolicyResponseDTO`.
 */
export interface RegionPolicyRow {
  region: OrganizationRegion;
  retentionDays: number | null;
  defaultExportFormat: string | null;
  targetDeploymentUrl: string | null;
  updatedAt: string;
  updatedBy: string;
}

/**
 * Body for PUT /super-admin/data-residency/policies/{region}. All
 * three fields are nullable; passing null clears the override and
 * falls back to the global policy.
 */
export interface RegionPolicyUpdate {
  retentionDays: number | null;
  defaultExportFormat: string | null;
  targetDeploymentUrl: string | null;
}

/**
 * MVP-c3 foot-guns — capability flags from
 * GET /super-admin/data-residency/policies/capabilities. Mirrors
 * `RegionPolicyCapabilitiesDTO`.
 */
export interface RegionPolicyCapabilities {
  remoteProvisioningCapable: boolean;
}
