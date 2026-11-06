/**
 * QA round 30 (user decision 2026-11-02): an as-needed (PRN) order carries
 * the minimum interval between two doses, written by the prescriber.
 *
 * QA D1378 / D1360 (round 32, coordinator decision 2026-11-03): an order is
 * as-needed because the prescriber TICKED "As needed (PRN)"; the interval field
 * shows from the tick. The portal never decides as-needed from the frequency.
 *
 * QA D1391 / D1395 (round 33, coordinator decision 2026-11-06): a word list
 * failed in both directions at once, so the server rule on an order NOT ticked
 * is inverted (`FrequencyWording`, hospital-core): accepted only when it states
 * a fixed schedule and every other word is accounted for. Everything below is
 * the MIRROR of that one rule, so a form can say before the round trip what the
 * server will answer. The server stays the judge (`fieldErrors.asNeeded`).
 * The shared fixture `as-needed-frequency.cases.json` holds both sides to the
 * same verdict (FrequencyWordingTest, as-needed-frequency.spec.ts), and
 * FrequencyWordingTest holds the word lists below equal to the Java ones.
 * `scripts/lib/as-needed-frequency.test.mjs` keeps every other reader out of
 * the portal.
 */

/** The longest minimum interval written, in hours (the server's `PrnMinInterval.MAX_HOURS`). */
export const PRN_MAX_INTERVAL_HOURS = 72;

/** What an unticked frequency says: `FrequencyWording.Verdict`. */
export type FrequencyVerdict = 'FIXED' | 'CONDITIONAL' | 'UNREADABLE';

const words = (list: string): ReadonlySet<string> => new Set(list.split(' '));

/* word-lists:start (held equal to FrequencyWording.java by FrequencyWordingTest) */
const CONDITIONAL_WORDS = words(
  'PRN SOS SB SIBESOIN AUBESOIN SI IF WHEN WHENEVER QUAND LORSQUE CUANDO ' +
    'BESOIN BESOINS DEMANDE DEMAND DEMANDA NECESSITE NECESSAIRE NECESARIO NECESARIA ' +
    'NECESIDAD NECESITE NECESITA NECESSARY NEEDED NEED REQUIRED PRECISA PRECISE ' +
    'DOULEUR DOULEURS FIEVRE FIEVRES NAUSEE NAUSEES VOMISSEMENT VOMISSEMENTS INSOMNIE ' +
    'CRISE CRISES MAL AGITATION CEPHALEE CEPHALEES CONSTIPATION EVA ' +
    'DOLOR DOLORES FIEBRE NAUSEAS VOMITO VOMITOS CRISIS ' +
    'PAIN FEVER NAUSEA NAUSEOUS NAUSEATED VOMITING ATTACK ATTACKS',
);
const CLAUSE_OPENERS = words('FOR POUR PARA');
const AFTER_OPENER = words(
  'A AN ONE TWO THREE FOUR FIVE SIX SEVEN EIGHT NINE TEN UN UNE UNA ' +
    'DAY DAYS WEEK WEEKS MONTH MONTHS YEAR YEARS HOUR HOURS LIFE MAINTENANCE ' +
    'DEUX TROIS QUATRE CINQ SEPT HUIT DIX JOUR JOURS SEMAINE SEMAINES MOIS ' +
    'DOS TRES CUATRO CINCO SEIS SIETE OCHO DIEZ DIA DIAS SEMANA SEMANAS MES MESES',
);
const LIMIT_WORDS = words(
  'MAX MAXI MAXIMUM MAXIMUN MAXIMAL MAXIMALE MAXIMO MAXIMA JUSQU JUSQUE JUSQUA HASTA UPTO',
);
const RANGE_JOINS = words('A TO OU OR O');
const TIMES_WORDS = words('FOIS X VECES VEZ TIMES TIME');
const HOUR_WORDS = words('H HR HRS HOUR HOURS HEURE HEURES HORA HORAS');
const AFTER_RANGE = words(
  'PAR PER POR AL J JOUR JOURS D DAY DAYS DIA DIAS PRISES TOMAS DOSES DOSIS',
);
const SPAN_WORDS = words(
  'JOUR JOURS JR JRS J DAY DAYS DIA DIAS SEMAINE SEMAINES SEM WEEK WEEKS ' +
    'SEMANA SEMANAS MOIS MONTH MONTHS MES MESES AN ANS YEAR YEARS ANO ANOS SA',
);
const PERIOD_WORDS = words('JOUR J DAY DIA SEMAINE SEM WEEK SEMANA MOIS MONTH MES AN YEAR ANO');
const PERIOD_JOINS = words('PAR PER POR AL A');
const INTAKE_WORDS = words('DOSE DOSES DOSIS PRISE PRISES TOMA TOMAS');
const MINUTE_WORDS = words('MIN MN MINUTE MINUTES MINUTOS');
const BEFORE_CLOCK = words('A AT LAS VERS');
const NUMBER_WORDS = words(
  'UN UNE UNA UNO ONE DEUX DOS TWO TROIS TRES THREE QUATRE CUATRO FOUR ' +
    'CINQ CINCO FIVE SIX SEIS SEPT SIETE SEVEN HUIT OCHO EIGHT NEUF NUEVE NINE DIX DIEZ TEN',
);
const NUMBER_SUFFIXES = words(
  'H HR HRS HEURE HEURES X J JR JRS JOUR JOURS D DAY DAYS DIA DIAS SEM KG MG MCG UG ML CC G L ' +
    'UI IU CP CPS MIN MN E EME ER ERE ND RD TH ST SA AM PM S',
);
const SCHEDULE_SUFFIXES = words('J JR JRS JOUR JOURS D DAY DAYS DIA DIAS SEM MIN MN SA');
const SCHEDULE_WORDS = words(
  'QD OD BID BD TID TDS QID QDS QH QOD QAM QPM QHS HS QW ' +
    'DAILY HOURLY WEEKLY MONTHLY NIGHTLY YEARLY TWICE THRICE ONCE ' +
    'QUOTIDIEN QUOTIDIENNE QUOTIDIENNEMENT HEBDOMADAIRE HEBDOMADAIRES MENSUEL MENSUELLE ' +
    'MENSUELLEMENT ANNUEL ANNUELLE DIARIO DIARIA SEMANAL MENSUAL ANUAL ' +
    'MATIN MATINS MATINEE MIDI SOIR SOIRS SOIREE NUIT NUITS COUCHER REVEIL JEUN REPAS DEJEUNER DINER MANE NOCTE ' +
    'MORNING MORNINGS NOON EVENING EVENINGS NIGHT NIGHTS BEDTIME MEAL MEALS BREAKFAST LUNCH DINNER ' +
    'MANANA MANANAS MEDIODIA TARDE TARDES NOCHE NOCHES ACOSTARSE AYUNAS COMIDA COMIDAS DESAYUNO ALMUERZO CENA ' +
    'LUNDI MARDI MERCREDI JEUDI VENDREDI SAMEDI DIMANCHE ' +
    'MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY ' +
    'LUNES MARTES MIERCOLES JUEVES VIERNES SABADO DOMINGO ' +
    'CONTINU CONTINUE CONTINUS CONTINUES CONTINUOUS CONTINUO CONTINUA ' +
    'UNIQUE UNICA UNICO SINGLE STAT IMMEDIATEMENT IMMEDIATELY INMEDIATAMENTE MAINTENANT NOW AHORA ' +
    'TPI TPIP TPIG IPT IPTP IPTI SMC PROTOCOLE PROTOCOLO PROTOCOL SCHEMA ESQUEMA ' +
    'NAISSANCE DELIVRANCE EXPULSION ACCOUCHEMENT CPN CONSULTATION SORTIE TRAVAIL ' +
    'LENDEMAIN DEMAIN BIRTH DELIVERY LABOR LABOUR DISCHARGE TOMORROW NACIMIENTO PARTO ALTA',
);
const HARMLESS_WORDS = words(
  'LE LA LES DE DU DES AU AUX ET EN PAR PUIS SUR APRES AVANT PENDANT ' +
    'DURANT PDT SEULEMENT AVEC SANS DANS LORS MOMENT CHAQUE TOUS TOUTES TOUT TOUTE SEUL SEULE VERS ' +
    'EL LOS LAS LO DEL AL CON POR ANTES DESPUES DURANTE SOLO SOLA CADA TODOS TODAS DIRECTAMENTE ' +
    'THE OF AND THEN IN AT BY PER AFTER BEFORE WITH DURING ONLY EACH EVERY AN AS TO OTHER OVER ' +
    'JOURNEE ALTERNE ALTERNES ALTERNOS CHARGE ENTRETIEN MAINTENANCE LONG COURS TERME ' +
    'TRAITEMENT TRATAMIENTO CURE LIFE VIE ' +
    'SELON SUIVANT SEGUN ACCORDING ORDONNANCE PRESCRIPTION MEDICALE MEDICAL MEDICO ' +
    'RECETA PRESCRIPCION POIDS PESO WEIGHT AVIS DIRECTED PRESCRIBED PEV ' +
    'PRENATALE PRENATALES PRENATAL ANTENATAL ANC GATPA ' +
    'SP AQ FANSIDAR SULFADOXINE PYRIMETHAMINE AMODIAQUINE ' +
    'CP CPS COMPRIME COMPRIMES GELULE GELULES AMP AMPOULE AMPOULES FLACON SACHET ' +
    'SACHETS SUPPO SUPPOSITOIRE GOUTTE GOUTTES CUILLERE CUILLEREE MESURE DEMI ' +
    'SIROP SYRUP JARABE POMMADE CREME COLLYRE SOLUTION SUSPENSION GEL SPRAY OVULE OVULES PATCH ' +
    'MG MCG UG ML CC UI IU KG TABLET TABLETS TAB CAPSULE CAPSULES PUFF ' +
    'PUFFS BOUFFEE BOUFFEES INJECTION PERFUSION BOLUS COMPRIMIDO COMPRIMIDOS TABLETA ' +
    'TABLETAS CAPSULA CAPSULAS AMPOLLA GOTAS UNIT UNITS UNITE UNITES UNIDAD UNIDADES ' +
    'IV IM SC PO VO SL IVL IVD IVSE ORAL ORALE RECTAL RECTALE LENTE ' +
    'LENT LENTEMENT SLOW DIRECTE SERINGUE PSE ' +
    'LAIT EAU VERRE GRAND JUS NOURRITURE ALIMENT ALIMENTS FOOD WATER MILK AGUA LECHE ALIMENTOS PETIT',
);
/* word-lists:end */

/** The frozen round-31 phrases `MarDoseSchedule` holds, which V260 backfilled from. */
const ROUND31_PHRASES: readonly string[] = [
  'PRN',
  'AS NEEDED',
  'AS REQUIRED',
  'WHEN NEEDED',
  'WHEN REQUIRED',
  'IF NEEDED',
  'IF REQUIRED',
  'IF NECESSARY',
  'ON DEMAND',
  'IF PAIN',
  'IF FEVER',
  'SI BESOIN',
  'AU BESOIN',
  'SELON BESOIN',
  'SELON LES BESOINS',
  'EN CAS DE BESOIN',
  'A LA DEMANDE',
  'SI NECESSAIRE',
  'SI DOULEUR',
  'SI FIEVRE',
  'EN CAS DE DOULEUR',
  'EN CAS DE FIEVRE',
  'SI NECESARIO',
  'SI ES NECESARIO',
  'EN CASO NECESARIO',
  'SEGUN NECESIDAD',
  'A DEMANDA',
  'SI DOLOR',
  'SI FIEBRE',
  'EN CASO DE DOLOR',
  'EN CASO DE FIEBRE',
];
const CONDITIONAL_PHRASES: readonly string[] = [
  'PRO RE NATA',
  'S IL',
  'SIL YA',
  'EN CAS',
  'EN CASO',
  'ON REQUEST',
  'UPON REQUEST',
  'AU CAS',
  'IN CASE',
  'IN THE EVENT',
];
const LIMIT_PHRASES: readonly string[] = [
  'UP TO',
  'AT MOST',
  'AU PLUS',
  'NO MORE THAN',
  'NOT MORE THAN',
  'PAS PLUS DE',
  'NO MAS DE',
  'A LO SUMO',
];

const HOUR_UNIT = '(?:H|HR|HRS|HOUR|HOURS|HEURE|HEURES|HORA|HORAS)';
const EVERY = '(?:EVERY|TOUTES LES|CHAQUE|CADA)';
const PER_DAY_SLASH = /\/ ?(?:JOURS?|J|DIAS?|DAYS?|D)\b/;
const PER_DAY_WORDS = /^(?:PAR JOUR|PAR 24 ?H|AL DIA|POR DIA|DAILY|A DAY|PER DAY)\b/;
const COMPARISON = /[<>≤≥]/;
const SLASH_SP = /\bS *\/ *P\b/;
const WEIGHT_BAND = /\b(?:POIDS|PESO|WEIGHT|\d+ *KG)\b/;
const NUMBER_THEN_REST = /^(\d{1,4})([A-Z0-9]*)$/;
const CODED_COUNT_WORD = /^(?:Q\d{1,2}H|X\d{1,2})$/;
const HOUR_AND_MINUTES = /^H\d{2}$/;
const ONE_OFF_WORDS = words(
  'ONCE ONCEONLY ONETIME SINGLEDOSE IMMEDIATELY NOW 1X 1FOIS UNESEULEFOIS 1FOISSEULEMENT DOSEUNIQUE ' +
    'ENDOSEUNIQUE PRISEUNIQUE IMMEDIATEMENT MAINTENANT 1VEZ UNASOLAVEZ DOSISUNICA AHORA INMEDIATAMENTE',
);
const NAMED_REPEATS = words(
  'QD OD DAILY ONCEDAILY ONCEADAY QAM QPM QHS HS 1XDAILY EVERYDAY TOUSLESJOURS CHAQUEJOUR QUOTIDIEN ' +
    'QUOTIDIENNE QUOTIDIENNEMENT DIARIO DIARIA CADADIA TODOSLOSDIAS BID BD TWICEDAILY TWICEADAY MATINETSOIR ' +
    'MANANAYNOCHE TID TDS THREETIMESDAILY THREETIMESADAY MATINMIDIETSOIR MANANAMEDIODIAYNOCHE QID QDS ' +
    'FOURTIMESDAILY FOURTIMESADAY QH HOURLY EVERYOTHERDAY QOD UNJOURSURDEUX TOUSLESDEUXJOURS CADADOSDIAS DIASALTERNOS',
);

/** A count of doses written as a word, `MarDoseSchedule.countDigitOf`. */
const COUNT_DIGITS: ReadonlyMap<string, string> = new Map(
  (
    [
      ['1', 'UNE UN UNA UNO ONE'],
      ['2', 'DEUX DOS TWO'],
      ['3', 'TROIS TRES THREE'],
      ['4', 'QUATRE CUATRO FOUR'],
      ['5', 'CINQ CINCO FIVE'],
      ['6', 'SIX SEIS'],
      ['7', 'SEPT SIETE SEVEN'],
      ['8', 'HUIT OCHO EIGHT'],
      ['9', 'NEUF NUEVE NINE'],
      ['10', 'DIX DIEZ TEN'],
      ['11', 'ONZE ONCE ELEVEN'],
      ['12', 'DOUZE DOCE TWELVE'],
    ] as const
  ).flatMap(([digit, names]) => names.split(' ').map((name) => [name, digit] as [string, string])),
);
const HOUR_DIGITS: ReadonlyMap<string, string> = new Map([
  ...[...COUNT_DIGITS].filter(([name]) => name !== 'UNO'),
  ['VINGT-QUATRE', '24'],
  ['VEINTICUATRO', '24'],
  ['TWENTY-FOUR', '24'],
  ['QUARANTE-HUIT', '48'],
  ['FORTY-EIGHT', '48'],
]);

function accentlessUpper(text: string): string {
  return text.normalize('NFD').replaceAll(/\p{M}/gu, '').toUpperCase();
}

/**
 * `MarDoseSchedule.normalize`: separators read as spaces, number words as
 * digits. Runs of spaces become one, so the patterns below need no open repeat.
 */
function normalize(text: string): string {
  const plain = accentlessUpper(text.trim()).replaceAll(/[_.,]/g, ' ').replaceAll(/\s+/g, ' ');
  const counts = plain.replaceAll(
    /\b([A-Z]+)(?= ?(?:FOIS|VECES|VEZ|TIMES)\b)/g,
    (word) => COUNT_DIGITS.get(word) ?? word,
  );
  return counts.replaceAll(
    new RegExp(`\\b([A-Z]+(?:-[A-Z]+)?)(?= ?${HOUR_UNIT}\\b)`, 'g'),
    (word) => HOUR_DIGITS.get(word) ?? word,
  );
}

/** `MarDoseSchedule.isAsNeeded`: a frozen phrase where a word starts. */
function readsRound31Phrase(text: string): boolean {
  const spaced = accentlessUpper(text)
    .replaceAll(/[^A-Z0-9]+/g, ' ')
    .trim();
  return ROUND31_PHRASES.some((phrase) => {
    let at = spaced.indexOf(phrase);
    while (at >= 0) {
      if (at === 0 || !/[A-Z]/.test(spaced[at - 1])) return true;
      at = spaced.indexOf(phrase, at + 1);
    }
    return false;
  });
}

function hoursOf(f: string): number | null {
  const fixed =
    /\bQ ?(\d{1,2}) ?H\b/.exec(f) ?? new RegExp(`\\b${EVERY} (\\d{1,2}) ?${HOUR_UNIT}\\b`).exec(f);
  if (fixed) return validHours(Number(fixed[1]));
  if (new RegExp(`\\b${EVERY} (?:HOUR|HEURES?|HORA)\\b`).test(f)) return 1;
  for (const m of f.matchAll(/\b(\d{1,2}) ?(H?) ?\/ ?(\d{1,2}) ?(H?)\b/g)) {
    if ((m[2] || m[4]) && m[1] === m[3] && Number(m[1]) < 24) return validHours(Number(m[1]));
  }
  const days = /\b(?:EVERY|TOUS LES|CADA) (\d) ?(?:DAYS|JOURS|DIAS)\b/.exec(f);
  return days ? validHours(Number(days[1]) * 24) : null;
}

function validHours(hours: number): number | null {
  return hours > 0 && hours <= 72 ? hours : null;
}

function timesPerDay(f: string): number | null {
  const compact = f.replaceAll(' ', '');
  if (/^\d(?:-\d){2,3}$/.test(compact)) {
    return [...compact].filter((c) => c >= '1' && c <= '9').length;
  }
  for (const m of f.matchAll(/\b(\d{1,2}) ?(?:X|FOIS|VECES|VEZ|TIMES) ?/g)) {
    const rest = f.slice(m.index + m[0].length);
    if (/^\/ ?(?:JOURS?|J|DIAS?|DAYS?|D)\b/.test(rest) || PER_DAY_WORDS.test(rest)) {
      return Number(m[1]) <= 24 ? Number(m[1]) : null;
    }
  }
  const bare = /\b(\d{1,2}) ?\/ ?(?:JOURS?|J|DIAS?|DAYS?|D)\b/.exec(f);
  if (bare) return Number(bare[1]) <= 24 ? Number(bare[1]) : null;
  return null;
}

function readsARepeat(text: string): boolean {
  if (!text.trim()) return false;
  const f = normalize(text);
  return (
    hoursOf(f) !== null || (timesPerDay(f) ?? 0) > 0 || NAMED_REPEATS.has(f.replaceAll(' ', ''))
  );
}

/** `MarDoseSchedule.read(String).kind() != UNREAD`. */
function readerReadsIt(text: string): boolean {
  if (!text.trim()) return true;
  const upper = normalize(text);
  if (upper.includes('STAT')) return true;
  if (readsRound31Phrase(text)) return false;
  const open = text.indexOf('(');
  const close = text.indexOf(')', open + 1);
  const repeat =
    open >= 0 && close > open
      ? readsARepeat(`${text.slice(0, open)} ${text.slice(close + 1)}`) ||
        readsARepeat(text.slice(open + 1, close))
      : readsARepeat(text);
  return repeat || ONE_OFF_WORDS.has(upper.replaceAll(' ', ''));
}

function numberOf(word: string): number | null {
  const number = NUMBER_THEN_REST.exec(word);
  if (number && !number[2]) return Number(number[1]);
  const digit = COUNT_DIGITS.get(word);
  return digit ? Number(digit) : null;
}

function joinSingleLetters(list: readonly string[]): string[] {
  const out: string[] = [];
  let run = '';
  for (const word of list) {
    if (word.length === 1 && /[A-Z]/.test(word)) {
      run += word;
      continue;
    }
    if (run) {
      out.push(run);
      run = '';
    }
    out.push(word);
  }
  if (run) out.push(run);
  return out;
}

function containsPhrase(list: readonly string[], phrases: readonly string[]): boolean {
  const spaced = ` ${list.join(' ')} `;
  return phrases.some((phrase) => spaced.includes(` ${phrase} `));
}

function opensAClause(list: readonly string[], at: number): boolean {
  const next = list[at + 1];
  return next !== undefined && /^[A-Z]/.test(next) && !AFTER_OPENER.has(next);
}

function readsConditional(text: string, list: readonly string[]): boolean {
  if (COMPARISON.test(text) || SLASH_SP.test(text) || readsRound31Phrase(text)) return true;
  const joined = joinSingleLetters(list);
  return (
    joined.some(
      (word, i) =>
        CONDITIONAL_WORDS.has(word) || (CLAUSE_OPENERS.has(word) && opensAClause(joined, i)),
    ) || containsPhrase(joined, CONDITIONAL_PHRASES)
  );
}

function endsARange(list: readonly string[], at: number): boolean {
  const word = list[at];
  return (
    word !== undefined && (TIMES_WORDS.has(word) || HOUR_WORDS.has(word) || AFTER_RANGE.has(word))
  );
}

function rangeStartsAt(list: readonly string[], at: number): boolean {
  const first = numberOf(list[at]);
  if (first === null || (at > 0 && numberOf(list[at - 1]) !== null)) return false;
  const end = RANGE_JOINS.has(list[at + 1] ?? '') ? at + 2 : at + 1;
  if (end >= list.length) return false;
  const whole = NUMBER_THEN_REST.exec(list[end]);
  if (!whole) {
    const word = numberOf(list[end]);
    return word !== null && word > first && endsARange(list, end + 1);
  }
  const counted = whole[2]
    ? whole[2] === 'X' || HOUR_WORDS.has(whole[2])
    : endsARange(list, end + 1);
  return Number(whole[1]) > first && counted;
}

function readsAsLimit(list: readonly string[]): boolean {
  return (
    list.some((word) => LIMIT_WORDS.has(word)) ||
    containsPhrase(list, LIMIT_PHRASES) ||
    list.some((_, i) => rangeStartsAt(list, i))
  );
}

function gluedSchedule(list: readonly string[], at: number): boolean {
  const number = NUMBER_THEN_REST.exec(list[at]);
  if (!number || !number[2]) return false;
  const rest = number[2];
  if (SCHEDULE_SUFFIXES.has(rest) || HOUR_AND_MINUTES.test(rest)) return true;
  const clock = at > 0 && HOUR_WORDS.has(rest) && BEFORE_CLOCK.has(list[at - 1]);
  return (rest === 'X' && number[1] === '1') || clock;
}

function isTimes(word: string): boolean {
  const glued = NUMBER_THEN_REST.exec(word);
  return TIMES_WORDS.has(word) || glued?.[2] === 'X';
}

function scheduleAt(list: readonly string[], at: number): boolean {
  const word = list[at];
  if (SCHEDULE_WORDS.has(word) || gluedSchedule(list, at)) return true;
  if (at === 0) return false;
  const before = list[at - 1];
  const count = numberOf(before);
  if (
    count !== null &&
    (SPAN_WORDS.has(word) || INTAKE_WORDS.has(word) || MINUTE_WORDS.has(word))
  ) {
    return true;
  }
  const singleTime = count === 1 && TIMES_WORDS.has(word);
  const period = PERIOD_WORDS.has(word) && (isTimes(before) || PERIOD_JOINS.has(before));
  const clock = at > 1 && HOUR_WORDS.has(word) && count !== null && BEFORE_CLOCK.has(list[at - 2]);
  return singleTime || period || clock;
}

function statesASchedule(text: string, list: readonly string[]): boolean {
  if (list.length === 0) return false;
  return (
    readerReadsIt(text) ||
    PER_DAY_SLASH.test(normalize(text)) ||
    list.some((_, i) => scheduleAt(list, i))
  );
}

function isAccountedFor(word: string): boolean {
  if (
    word.length === 1 ||
    HARMLESS_WORDS.has(word) ||
    SCHEDULE_WORDS.has(word) ||
    NUMBER_WORDS.has(word) ||
    TIMES_WORDS.has(word) ||
    HOUR_WORDS.has(word) ||
    SPAN_WORDS.has(word) ||
    INTAKE_WORDS.has(word) ||
    MINUTE_WORDS.has(word) ||
    CLAUSE_OPENERS.has(word) ||
    CODED_COUNT_WORD.test(word)
  ) {
    return true;
  }
  const number = NUMBER_THEN_REST.exec(word);
  return (
    !!number && (!number[2] || NUMBER_SUFFIXES.has(number[2]) || HOUR_AND_MINUTES.test(number[2]))
  );
}

function wordsOf(text: string): string[] {
  const normal = normalize(text)
    .replaceAll(/[^A-Z0-9]+/g, ' ')
    .trim();
  return normal ? normal.split(' ') : [];
}

/** It names a weight and nothing else stands in it: `FrequencyWording.isWeightBand`. */
function isWeightBand(bracketed: string): boolean {
  return (
    WEIGHT_BAND.test(bracketed) &&
    wordsOf(bracketed)
      .filter((word) => word !== 'SI')
      .every((word) => !CONDITIONAL_WORDS.has(word) && isAccountedFor(word))
  );
}

/**
 * What an order NOT ticked as needed says with this frequency, as the server
 * reads it: `FrequencyWording.classify`.
 */
export function unTickedFrequencyVerdict(frequency: string | null | undefined): FrequencyVerdict {
  if (!frequency?.trim()) return 'FIXED';
  const text = accentlessUpper(frequency).replaceAll(/\([^()]*\)/g, (part) =>
    isWeightBand(part) ? ' ' : part,
  );
  const list = wordsOf(text);
  if (readsConditional(text, list)) return 'CONDITIONAL';
  if (readsAsLimit(list) || !statesASchedule(text, list)) return 'UNREADABLE';
  return list.every(isAccountedFor) ? 'FIXED' : 'CONDITIONAL';
}
