import { PlatformServiceType } from './platform.service';

export type IntegrationHealthStatus = 'HEALTHY' | 'DEGRADED' | 'FAILING' | 'NO_HISTORY';

export interface IntegrationHealthOrgEntry {
  organizationId: string | null;
  organizationName: string | null;
  status: IntegrationHealthStatus;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastErrorMessage: string | null;
  successCount24h: number;
  failureCount24h: number;
  updatedAt: string | null;
}

export interface IntegrationHealthRow {
  integrationId: string;
  displayName: string;
  serviceType: PlatformServiceType | null;
  provider: string | null;
  enabled: boolean;
  /** QA D851: a connectivity probe exists, so "Test connection" can run. */
  probeSupported: boolean;
  /** QA D851: a re-sync exists, so "Re-sync" can run. */
  resyncSupported: boolean;
  capabilities: string[];
  rolledUpStatus: IntegrationHealthStatus;
  organizations: IntegrationHealthOrgEntry[];
}

export interface IntegrationHealthSummary {
  totalIntegrations: number;
  healthyCount: number;
  degradedCount: number;
  failingCount: number;
  noHistoryCount: number;
  integrations: IntegrationHealthRow[];
}

/**
 * MVP-3b — result of POST /super-admin/integrations/{id}/probe.
 * Mirrors the backend `IntegrationProbeResultDTO` (QA D851: the field is
 * `message`; the console read an `errorMessage` the server never sends).
 */
export interface IntegrationProbeResult {
  integrationId: string;
  ok: boolean;
  latencyMs: number | null;
  message: string | null;
}

/**
 * MVP-3b — one hourly bucket from GET /super-admin/integrations/{id}/history,
 * oldest first. Mirrors the backend `IntegrationHistoryBucketDTO` (QA D851:
 * success and failure counts; the console read three fields it never got).
 */
export interface IntegrationHistoryBucket {
  bucketStart: string;
  successCount: number;
  failureCount: number;
}
