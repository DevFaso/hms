/**
 * QA D423: one rule for how a notification is drawn, shared by the shell
 * bell panel and the notifications page. A critical clinical alert
 * (CRITICAL_LAB_RESULT, CRITICAL_IMAGING_FINDING, their escalations, a
 * critical PRO item) must not look like a routine message: it gets its own
 * icon, colour and a spoken label, since the icon itself is aria-hidden.
 */
export function isCriticalNotification(type: string | null | undefined): boolean {
  return !!type && type.toUpperCase().includes('CRITICAL');
}

export function notificationIcon(type: string | null | undefined): string {
  if (isCriticalNotification(type)) {
    return 'emergency';
  }
  switch (type) {
    case 'APPOINTMENT':
      return 'calendar_month';
    case 'LAB_RESULT':
      return 'science';
    case 'BILLING':
      return 'receipt_long';
    case 'ALERT':
      return 'warning';
    case 'SUCCESS':
      return 'check_circle';
    case 'SYSTEM':
      return 'settings';
    default:
      return 'notifications';
  }
}
