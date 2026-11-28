import { providerLifecycleLabelKey } from './provider.model';

describe('providerLifecycleLabelKey (QA D4073)', () => {
  it('a facility never verified waits for verification; it is not shown as a sanction', () => {
    expect(
      providerLifecycleLabelKey({
        suspensionCause: 'PENDING_VERIFICATION',
        verificationStatus: 'SUBMITTED',
      }),
    ).toBe('PROVIDER.LIFECYCLE_STATE.PENDING_VERIFICATION');
    expect(
      providerLifecycleLabelKey({
        suspensionCause: 'PENDING_VERIFICATION',
        verificationStatus: 'REJECTED',
      }),
    ).toBe('PROVIDER.LIFECYCLE_STATE.REJECTED_NOT_ACTIVATED');
  });

  it('a revocation and an operator suspension stay distinct', () => {
    expect(providerLifecycleLabelKey({ suspensionCause: 'VERIFICATION_REVOKED' })).toBe(
      'PROVIDER.LIFECYCLE_STATE.VERIFICATION_REVOKED',
    );
    expect(providerLifecycleLabelKey({ suspensionCause: 'OPERATOR' })).toBeNull();
    expect(providerLifecycleLabelKey({ suspensionCause: null })).toBeNull();
  });
});
