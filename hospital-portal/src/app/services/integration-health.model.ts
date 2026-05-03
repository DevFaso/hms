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
