import { ProviderLifecycleState, ProviderVerificationStatus } from '../../services/provider.model';

/**
 * The shared `.status-badge` modifier for a verification status (page-common
 * styles): one rule for the list, the detail page and the provider profile.
 */
export function verificationBadgeClass(
  status: ProviderVerificationStatus | null | undefined,
): string {
  switch (status) {
    case 'VERIFIED':
      return 'active';
    case 'SUBMITTED':
      return 'in-progress';
    case 'REJECTED':
      return 'inactive';
    case 'REVOKED':
      return 'cancelled';
    default:
      return '';
  }
}

/** The shared `.status-badge` modifier for a facility's lifecycle state. */
export function lifecycleBadgeClass(state: ProviderLifecycleState | null | undefined): string {
  switch (state) {
    case 'ACTIVE':
      return 'active';
    case 'SUSPENDED':
      return 'in-progress';
    case 'ARCHIVED':
      return 'scheduled';
    case 'PENDING_PURGE':
    case 'PURGED':
      return 'inactive';
    default:
      return '';
  }
}
