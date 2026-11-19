/**
 * Display-only reading of a stored outbound HL7 v2 message for the outbox
 * monitor. Nothing here changes the payload: it splits it into segment lines
 * for the `<pre>` and picks the few fields a person needs to recognise the
 * message. Defensive by design — anything that is not a recognisable HL7
 * message returns `null`, and the modal then shows the raw text only.
 */

export interface Hl7Summary {
  /** MSH-9 message code and trigger, e.g. `ORU^R01`. */
  messageType: string;
  /** PID-5, read as "Given Middle FAMILY". */
  patientName: string | null;
  /** OBX-3 text (or OBR-4 when there is no observation), name preferred over code. */
  test: string | null;
  /** OBX-5. */
  value: string | null;
  /** OBX-6 identifier. */
  unit: string | null;
  /** OBX-8 abnormal flag code (N, A, L, H, LL, HH ...). */
  flag: string | null;
  /** OBX-11 result status code (F, P, C ...). */
  resultStatus: string | null;
}

/** CR, LF or CRLF: each starts a new segment line. */
const SEGMENT_BREAK = /\r\n|\r|\n/;

/** The payload as one segment per line, for display. Blank trailing lines are dropped. */
export function hl7SegmentLines(payload: string | null | undefined): string[] {
  if (!payload) {
    return [];
  }
  return payload.split(SEGMENT_BREAK).filter((line) => line.length > 0);
}

/** The payload with LF line breaks, for the clipboard. */
export function hl7Readable(payload: string | null | undefined): string {
  return hl7SegmentLines(payload).join('\n');
}

/** HL7 escape sequences (\F\ \S\ \T\ \R\ \E\ \.br\) back to their characters. */
function unescape(value: string): string {
  return value.replace(/\\(F|S|T|R|E|\.br)\\/g, (_m, code: string) => {
    switch (code) {
      case 'F':
        return '|';
      case 'S':
        return '^';
      case 'T':
        return '&';
      case 'R':
        return '~';
      case 'E':
        return '\\';
      default:
        return ' ';
    }
  });
}

function component(field: string | undefined, index: number): string {
  if (!field) {
    return '';
  }
  const parts = field.split('^');
  return unescape((parts[index] ?? '').trim());
}

function orNull(value: string): string | null {
  return value.length > 0 ? value : null;
}

/** A coded element (CE/CWE) read as its text, falling back to its code. */
function codedText(field: string | undefined): string | null {
  return orNull(component(field, 1)) ?? orNull(component(field, 0));
}

function personName(field: string | undefined): string | null {
  const family = component(field, 0);
  const given = component(field, 1);
  const middle = component(field, 2);
  const name = [given, middle, family.toUpperCase()].filter((p) => p.length > 0).join(' ');
  return orNull(name);
}

/**
 * The summary of a stored message, or `null` when it is not a readable HL7
 * v2 message (no MSH first, or no message type).
 */
export function parseHl7Summary(payload: string | null | undefined): Hl7Summary | null {
  try {
    const lines = hl7SegmentLines(payload);
    if (lines.length === 0 || !lines[0].startsWith('MSH|')) {
      return null;
    }
    // MSH-1 is the field separator itself, so MSH-n sits at split index n-1.
    const msh = lines[0].split('|');
    const messageType = [component(msh[8], 0), component(msh[8], 1)].filter(Boolean).join('^');
    if (!messageType) {
      return null;
    }
    const segment = (name: string): string[] | undefined =>
      lines.find((l) => l.startsWith(name + '|'))?.split('|');
    const pid = segment('PID');
    const obr = segment('OBR');
    const obx = segment('OBX');
    return {
      messageType,
      patientName: personName(pid?.[5]),
      test: codedText(obx?.[3]) ?? codedText(obr?.[4]),
      value: obx ? orNull(unescape((obx[5] ?? '').trim())) : null,
      unit: obx ? orNull(component(obx[6], 0)) : null,
      flag: obx ? orNull((obx[8] ?? '').trim()) : null,
      resultStatus: obx ? orNull((obx[11] ?? '').trim()) : null,
    };
  } catch {
    return null;
  }
}

/** i18n key for a message type, or null when it has no plain-language name. */
export function messageTypeKey(messageType: string | null | undefined): string | null {
  const code = (messageType ?? '').split('^').slice(0, 2).join('^');
  if (code === 'OML^O21') {
    return 'LAB_OUTBOX.TYPE_OML';
  }
  if (code === 'ORU^R01') {
    return 'LAB_OUTBOX.TYPE_ORU';
  }
  return null;
}

const RESULT_STATUSES = new Set(['F', 'P', 'C']);

/** i18n key for an OBX-11 status shown as a badge, or null for none / an unlisted code. */
export function resultStatusKey(status: string | null | undefined): string | null {
  return status && RESULT_STATUSES.has(status) ? `LAB_OUTBOX.RESULT_STATUS_${status}` : null;
}

const FLAGS = new Set(['N', 'A', 'L', 'H', 'LL', 'HH']);

/** i18n key for an OBX-8 flag, or null when the code has no label (shown raw). */
export function flagKey(flag: string | null | undefined): string | null {
  return flag && FLAGS.has(flag) ? `LAB_OUTBOX.FLAG_${flag}` : null;
}
