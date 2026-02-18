/**
 * Test data utilities – Generates unique test data for E2E tests.
 */

let counter = 0;

function uid(): string {
  return `${Date.now()}-${++counter}`;
}

export function uniqueEmail(prefix = 'test'): string {
  return `${prefix}.${uid()}@hms-test.com`;
}

export function uniqueUsername(prefix = 'testuser'): string {
  return `${prefix}_${uid()}`;
}

export function uniquePhone(): string {
  const num = Math.floor(1000000000 + Math.random() * 9000000000);
  return `+1-${String(num).slice(0, 3)}-${String(num).slice(3, 6)}-${String(num).slice(6)}`;
}

export function createPatientData(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const id = uid();
  return {
    firstName: `TestFirst${id}`,
    lastName: `TestLast${id}`,
    email: uniqueEmail('patient'),
    phoneNumber: uniquePhone(),
    dateOfBirth: '1990-01-15',
    gender: 'MALE',
    bloodGroup: 'O+',
    ...overrides,
  };
}

export function createStaffData(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const id = uid();
  return {
    firstName: `StaffFirst${id}`,
    lastName: `StaffLast${id}`,
    email: uniqueEmail('staff'),
    phoneNumber: uniquePhone(),
    specialization: 'General',
    ...overrides,
  };
}

export function createAppointmentData(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const dateStr = tomorrow.toISOString().split('T')[0];
  return {
    appointmentDate: dateStr,
    startTime: '09:00',
    endTime: '09:30',
    type: 'CONSULTATION',
    status: 'SCHEDULED',
    reason: 'E2E Test Appointment',
    ...overrides,
  };
}

export function createDepartmentData(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const id = uid();
  return {
    name: `TestDept${id}`,
    code: `TD${counter}`,
    description: `E2E test department ${id}`,
    ...overrides,
  };
}
