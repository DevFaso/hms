/** A navigation to the existing, permission-checked assignment workflow; never an activation request. */
export function assignmentReactivationHref(assignmentId?: string | null): string | null {
  return assignmentId ? `/admin-assignments?reactivate=${encodeURIComponent(assignmentId)}` : null;
}
