import { assignmentReactivationHref } from './assignment-reactivation';

describe('assignment reactivation navigation', () => {
  it('opens the existing workflow and safely encodes an identifier', () => {
    expect(assignmentReactivationHref('a/b')).toBe('/admin-assignments?reactivate=a%2Fb');
    expect(assignmentReactivationHref()).toBeNull();
    expect(assignmentReactivationHref('')).toBeNull();
  });
});
