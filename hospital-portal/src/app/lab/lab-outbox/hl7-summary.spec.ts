import {
  flagKey,
  hl7Readable,
  hl7SegmentLines,
  messageTypeKey,
  parseHl7Summary,
  resultStatusKey,
} from './hl7-summary';

describe('hl7-summary (outbox message display)', () => {
  const ORU =
    'MSH|^~\\&|HMS|HOSPITAL|LAB_ANALYZER|LAB|20261119153000+0000||ORU^R01^ORU_R01|HMS-1|P|2.5.1\r' +
    'PID|1||p-1||Kaboré^Issa\r' +
    'OBR|1|o-1||WBC^Leucocytes|||20261119152000+0000\r' +
    'OBX|1|NM|WBC^Leucocytes||12.4|10\\S\\9/L^per litre^UCUM||H|||F|||20261119152000+0000\r';

  it('splits on CR, LF and CRLF alike and drops the trailing empty line', () => {
    expect(hl7SegmentLines('A\rB\nC\r\nD\r')).toEqual(['A', 'B', 'C', 'D']);
    expect(hl7SegmentLines('')).toEqual([]);
    expect(hl7SegmentLines(undefined)).toEqual([]);
    expect(hl7Readable('A\rB\r')).toBe('A\nB');
  });

  it('reads the fields a person needs, decoding HL7 escapes', () => {
    expect(parseHl7Summary(ORU)).toEqual({
      messageType: 'ORU^R01',
      patientName: 'Issa KABORÉ',
      test: 'Leucocytes',
      value: '12.4',
      unit: '10^9/L',
      flag: 'H',
      resultStatus: 'F',
    });
  });

  it('falls back to OBR-4 when there is no observation (an order message)', () => {
    const s = parseHl7Summary(
      'MSH|^~\\&|HMS|H|A|L|20261119153000+0000||OML^O21^OML_O21|X|P|2.5.1\rOBR|1|ACC||NFS^\r',
    );
    expect(s?.test).toBe('NFS');
    expect(s?.patientName).toBeNull();
    expect(s?.resultStatus).toBeNull();
  });

  it('returns null for anything that is not an HL7 message', () => {
    expect(parseHl7Summary('hello')).toBeNull();
    expect(parseHl7Summary('MSH|^~\\&|only|a|header')).toBeNull();
    expect(parseHl7Summary(null)).toBeNull();
  });

  it('labels only the codes it knows', () => {
    expect(messageTypeKey('ORU^R01^ORU_R01')).toBe('LAB_OUTBOX.TYPE_ORU');
    expect(messageTypeKey('OML^O21')).toBe('LAB_OUTBOX.TYPE_OML');
    expect(messageTypeKey('ADT^A01')).toBeNull();
    expect(resultStatusKey('C')).toBe('LAB_OUTBOX.RESULT_STATUS_C');
    expect(resultStatusKey('X')).toBeNull();
    expect(resultStatusKey(null)).toBeNull();
    expect(flagKey('HH')).toBe('LAB_OUTBOX.FLAG_HH');
    expect(flagKey('<')).toBeNull();
  });
});
