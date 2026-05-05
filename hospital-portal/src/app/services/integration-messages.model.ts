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
