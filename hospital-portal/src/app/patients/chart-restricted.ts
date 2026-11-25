import { HttpErrorResponse } from '@angular/common/http';

/** The code the server answers a restricted chart with (E8 #54): 403 CHART_RESTRICTED. */
export const CHART_RESTRICTED_CODE = 'CHART_RESTRICTED';

/**
 * QA D3864: a read refused because the chart is restricted and the caller holds
 * no live break-the-glass session. Not a failure to retry: the way in is
 * « Déclencher l'accès d'urgence » on the patient page.
 */
export function isChartRestricted(err: unknown): boolean {
  return (
    err instanceof HttpErrorResponse &&
    err.status === 403 &&
    (err.error as { code?: unknown } | null)?.code === CHART_RESTRICTED_CODE
  );
}
