import { PatientSublinePipe, patientSubline } from './patient-subline';

describe('patientSubline (QA D903/D912)', () => {
  it("prefers this hospital's MRN over the email and the phone", () => {
    expect(
      patientSubline({ mrn: 'CHW2572', email: 'awa@example.test', phoneNumberPrimary: '70001001' }),
    ).toBe('CHW2572');
  });

  it('falls back to the email, then the phone, for a record with no MRN', () => {
    expect(patientSubline({ mrn: '  ', email: 'awa@example.test', phoneNumberPrimary: '7' })).toBe(
      'awa@example.test',
    );
    expect(patientSubline({ email: '', phoneNumberPrimary: ' 70001003 ' })).toBe('70001003');
  });

  it('gives nothing, never a separator, when no identifier is known', () => {
    expect(patientSubline({ email: '' })).toBe('');
    expect(patientSubline(null)).toBe('');
    expect(patientSubline(undefined)).toBe('');
  });

  it('is what the pipe renders', () => {
    const pipe = new PatientSublinePipe();
    expect(pipe.transform({ mrn: 'CHW1444', phoneNumberPrimary: '70001003' })).toBe('CHW1444');
    expect(pipe.transform(null)).toBe('');
  });
});
