import { isCriticalNotification, notificationIcon } from './notification-icon';

describe('notificationIcon (QA D423)', () => {
  it('draws every critical clinical alert with the emergency icon, not the routine one', () => {
    for (const type of [
      'CRITICAL_LAB_RESULT',
      'CRITICAL_LAB_RESULT_ESCALATION',
      'CRITICAL_IMAGING_FINDING',
      'PRO_CRITICAL_ITEM_ESCALATION',
    ]) {
      expect(isCriticalNotification(type)).toBeTrue();
      expect(notificationIcon(type)).toBe('emergency');
    }
  });

  it('keeps routine messages on their own icons', () => {
    expect(isCriticalNotification('PHARMACY_EVENT')).toBeFalse();
    expect(notificationIcon('PHARMACY_EVENT')).not.toBe('emergency');
    expect(notificationIcon('ALERT')).toBe('warning');
    expect(notificationIcon(null)).toBe('notifications');
  });
});
