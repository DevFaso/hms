/** Recorded audit metadata only: never infer a hospital or role from the current viewer. */
export function recordedAuditValue(value: string | null | undefined): string {
  const text = value?.trim() ?? '';
  return !text || ['Unknown Hospital', 'Unknown Role', 'Unknown Resource', '—'].includes(text)
    ? ''
    : text;
}

/** Technical destinations stay in details; known routes get localized labels in the row. */
export function auditTargetKey(target: string | null | undefined): string {
  const path = target?.split('?')[0].replace(/^\/api/, '') ?? '';
  const labels: Record<string, string> = {
    '/treatment-plans': 'SUPER_ADMIN.STAT.TREATMENT_PLANS',
    'recent-activity': 'SUPER_ADMIN.RECENT_CLINICAL_ACTIVITY',
    '/super-admin/recent-activity': 'SUPER_ADMIN.RECENT_CLINICAL_ACTIVITY',
    '/super-admin/summary': 'SUPER_ADMIN.TITLE',
    'consultation-stats': 'SUPER_ADMIN.CLINICAL_OVERVIEW',
  };
  return labels[path] ?? '';
}
