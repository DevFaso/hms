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
 * Mirrors the backend `IntegrationProbeResultDTO`.
 */
export interface IntegrationProbeResult {
  integrationId: string;
  ok: boolean;
  latencyMs: number | null;
  errorMessage: string | null;
  probedAt: string;
}

/**
 * MVP-3b — one bucketed entry from
 * GET /super-admin/integrations/{id}/history. Each row covers a fixed
 * window (default: one hour) and reports counts per status. The backend
 * orders the response oldest → newest so a sparkline can render
 * left-to-right without a client-side sort.
 */
export interface IntegrationHistoryBucket {
  bucketStart: string;
  bucketEnd: string;
  healthyCount: number;
  degradedCount: number;
  failingCount: number;
}
