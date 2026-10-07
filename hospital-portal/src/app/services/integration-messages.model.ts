/**
 * MVP-c3 — TypeScript mirrors of the Bridges-style message log DTOs.
 * The values match the backend `IntegrationMessageDirection` /
 * `IntegrationMessageStatus` enums string-for-string so the wire
 * payload is JSON-pass-through.
 */
export type IntegrationMessageDirection = 'OUTBOUND' | 'INBOUND';

export type IntegrationMessageStatus = 'SENT' | 'RECEIVED' | 'FAILED' | 'REPLAYED';

export interface IntegrationMessageEvent {
  id: string;
  integrationId: string;
  organizationId: string | null;
  direction: IntegrationMessageDirection;
  messageType: string | null;
  correlationId: string | null;
  payload: string | null;
  /**
   * Set when the retention sweep erased the content (V177). `payload` is
   * then null and the row cannot be replayed; a null payload without this
   * stamp is a row that never carried one.
   */
  payloadPurgedAt: string | null;
  status: IntegrationMessageStatus;
  errorMessage: string | null;
  attemptCount: number;
  lastAttemptedAt: string;
  receivedAt: string;
}

export interface IntegrationMessagePage {
  content: IntegrationMessageEvent[];
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  /** DLQ badge — number of FAILED messages still awaiting replay. */
  deadLetterCount: number;
  /**
   * False when the retention sweep is disabled or refuses its configuration:
   * the windows below are then not enforced and the page says so.
   */
  retentionActive: boolean;
  /** Configured content-retention window (`hms.integration.retention.payload-days`). */
  payloadRetentionDays: number;
  /** Ceiling for unresolved dead letters (`hms.integration.retention.unresolved-max-days`). */
  payloadUnresolvedMaxDays: number;
}

export interface IntegrationMessageSearchFilter {
  integrationId?: string;
  organizationId?: string;
  status?: IntegrationMessageStatus;
  fromDate?: string;
  toDate?: string;
  page?: number;
  size?: number;
}
