/**
 * Wire shapes for the DHIS2 ADX export admin surface mounted at
 * {@code /admin/integrations/dhis2}. Mirrors the response DTOs on the
 * backend exactly — secrets are never carried over the wire.
 */

export type Dhis2AuthMode = 'PAT' | 'BASIC';

export type Dhis2PeriodType = 'MONTHLY' | 'WEEKLY' | 'YEARLY';

export type Dhis2RunStatus = 'PENDING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';

export type Dhis2OutboxStatus = 'PENDING' | 'SENT' | 'FAILED';

export interface Dhis2FacilityConfig {
  id: string;
  hospitalId: string;
  baseUrl: string;
  authMode: Dhis2AuthMode;
  authSecretEnvVar: string;
  authSecretConfigured: boolean;
  defaultPeriodType: Dhis2PeriodType;
  defaultDatasetUid: string | null;
  lastExportAt: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Dhis2FacilityConfigRequest {
  baseUrl: string;
  authMode: Dhis2AuthMode;
  authSecretEnvVar: string;
  defaultPeriodType: Dhis2PeriodType;
  defaultDatasetUid?: string | null;
  active?: boolean;
}

export interface Dhis2DataElementMapping {
  id: string;
  hospitalId: string;
  hmsConceptSystem: string;
  hmsConceptCode: string;
  dhis2DataElementUid: string;
  dhis2CategoryOptionComboUid: string | null;
  periodType: Dhis2PeriodType;
  datasetUid: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Dhis2DataElementMappingRequest {
  hmsConceptSystem: string;
  hmsConceptCode: string;
  dhis2DataElementUid: string;
  dhis2CategoryOptionComboUid?: string | null;
  periodType: Dhis2PeriodType;
  datasetUid: string;
  active?: boolean;
}

export interface Dhis2ExportRun {
  id: string;
  hospitalId: string;
  datasetUid: string;
  periodIso: string;
  triggeredByStaffId: string | null;
  startedAt: string;
  completedAt: string | null;
  status: Dhis2RunStatus;
  valueCount: number;
  skippedCount: number;
  httpStatus: number | null;
  errorMessage: string | null;
  requestId: string;
}

export interface Dhis2TriggerRequest {
  hospitalId: string;
  datasetUid: string;
  periodType: Dhis2PeriodType;
  periodIso: string;
}

export interface Dhis2Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** DHIS2 11-char UID validator (letter + 10 alphanumeric). */
export const DHIS2_UID_REGEX = /^[A-Za-z][A-Za-z0-9]{10}$/;

/** DHIS2-canonical period token validator (matches the backend pattern). */
export const DHIS2_PERIOD_REGEX = /^(\d{4}(\d{2})?|\d{4}W\d{1,2})$/;

/** Upper-snake-case env-var name (matches the backend pattern). */
export const ENV_VAR_REGEX = /^[A-Z][A-Z0-9_]*$/;
