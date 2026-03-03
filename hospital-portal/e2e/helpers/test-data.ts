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
  // Use cryptographically secure randomness (CWE-338 / CodeQL js/insecure-randomness).
  // crypto.getRandomValues is available in both Node ≥ 15 and modern browsers.
  const buf = new Uint32Array(1);
  crypto.getRandomValues(buf);
  // Map the 32-bit value into a 10-digit number in the range [1_000_000_000, 9_999_999_999].
  const num = 1_000_000_000 + (buf[0] % 9_000_000_000);
  const digits = String(num);
  return `+1-${digits.slice(0, 3)}-${digits.slice(3, 6)}-${digits.slice(6)}`;
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
