/**
 * QA round 30 (user decision 2026-11-02): an as-needed (PRN) order carries
 * the minimum interval between two doses, written by the prescriber.
 *
 * QA D1378 / D1360 (round 32, coordinator decision 2026-11-03): an order is
 * as-needed because the prescriber TICKED "As needed (PRN)"; the interval field
 * shows from the tick. The portal never decides as-needed from the frequency.
 *
 * QA D1419 / D1426 / D1434 (round 34, coordinator decision 2026-11-06): a word
 * list failed in both directions three rounds running, so the server no longer
 * refuses what it cannot read. An order NOT ticked has one of three outcomes,
 * from one reader (`MarDoseSchedule`, `FrequencyCadence`, `FrequencyWording`,
 * hospital-core): it reads as a schedule (accepted, and the MAR schedules it);
 * it holds a real condition (refused on `asNeeded`); anything else is a question
 * (refused once on `frequencyConfirmed`, created when confirmed, flagged on the
 * MAR). Everything below is the MIRROR of that reader, so a form can say before
 * the round trip what the server will answer. The server stays the judge.
 * The shared fixture `as-needed-frequency.cases.json` holds both sides to the
 * same verdict and the same reading (FrequencyWordingTest,
 * as-needed-frequency.spec.ts), and FrequencyWordingTest holds the word lists
 * below equal to the Java ones. `scripts/lib/as-needed-frequency.test.mjs` keeps
 * every other reader out of the portal.
 *
 * QA D1452-D1460 (round 35, coordinator decision 2026-11-07): the reader no longer
 * guesses. A text is a schedule only when ALL of it is one form of the closed
 * grammar below, every token consumed; anything else is asked once. A wording is
 * added by a line here, in `FrequencyCadence.java` and in the fixture.
 */

/** The longest minimum interval written, in hours (the server's `PrnMinInterval.MAX_HOURS`). */
export const PRN_MAX_INTERVAL_HOURS = 72;

/** What an unticked frequency says: `FrequencyWording.Verdict`. */
export type FrequencyVerdict = 'FIXED' | 'CONDITIONAL' | 'UNREADABLE';

const words = (list: string): ReadonlySet<string> => new Set(list.split(' '));

/* word-lists:start (held equal to FrequencyWording.java and FrequencyCadence.java by FrequencyWordingTest) */
const CONDITIONAL_WORDS = words(
  'PRN SOS SB SIBESOIN AUBESOIN SI IF WHEN WHENEVER QUAND LORSQUE CUANDO BESOIN ' +
    'BESOINS DEMANDE DEMAND DEMANDA NECESSITE NECESSAIRE NECESARIO NECESARIA ' +
    'NECESIDAD NECESITE NECESITA NECESSARY NEEDED NEED REQUIRED DOULEUR DOULEURS ' +
    'FIEVRE FIEVRES NAUSEE NAUSEES VOMISSEMENT VOMISSEMENTS INSOMNIE CRISE CRISES ' +
    'MAL AGITATION CEPHALEE CEPHALEES CONSTIPATION EVA DOLOR DOLORES FIEBRE NAUSEAS ' +
    'VOMITO VOMITOS CRISIS PAIN FEVER NAUSEA NAUSEOUS NAUSEATED VOMITING ATTACK ' +
    'ATTACKS SLEEP HEADACHE HEADACHES TOUX TOS COUGH VOLONTE LIBITUM REQUERIMIENTO ' +
    'DISCRECION OCCASIONNELLEMENT PONCTUELLEMENT INTERMITTENCE HYPERTHERMIE ANXIETY ' +
    'ANXIETE ANSIEDAD ANGOISSE DIARRHEE DIARRHEA DIARREA DYSPNEE DYSPNEA DISNEA ' +
    'VERTIGE VERTIGES VERTIGO PRURIT ITCH ITCHING WHEEZE SIFFLEMENT SPASME SPASMES ' +
    'CONVULSION CONVULSIONS SAIGNEMENT BLEEDING HEMORRAGIE HEMORRHAGE GLYCEMIE ' +
    'GLUCEMIA DEXTRO EFFORT EXERCISE MAUX SIFFLEMENTS SAIGNEMENTS HEMORRAGIES ' +
    'WHEEZING DIARRHOEA SPASM INSOMNIA VOMITS FIEBRES CONVULSIONES ESPASMOS ' +
    'HEMORRAGIA INSOMNIO MIGRAINE CRAMPS RASH SEIZURES DIZZINESS HEARTBURN HIVES ' +
    'RESTLESSNESS SOB CRAMPES DEMANGEAISONS BRULURES PALPITATIONS AIGREURS ' +
    'ESSOUFFLEMENT MAREOS PICAZON PRURITO SANGRADO CEFALEA MIGRANA ATAQUES ' +
    'SYMPTOMES SINTOMAS SYMPTOMS TEMPERATURE TEMPERATURA ONSET PARFOIS SOMETIMES ' +
    'POUSSEES FEBRILE APPARITION SIGNES SIGNS',
);
const PREVENTION_WORDS = words(
  'PREVENIR PREVENTION PROPHYLAXIE PREVENCION PREVENT PROPHYLAXIS PROFILAXIS',
);
const HOUR_WORDS = words('H HR HRS HS HOUR HOURS HEURE HEURES HORA HORAS');
const DAY_WORDS = words('J JR JRS D DAY DAYS JOUR JOURS DIA DIAS');
const WEEK_WORDS = words('W SEM SEMAINE SEMAINES WEEK WEEKS SEMANA SEMANAS');
const MONTH_WORDS = words('MOIS MONTH MONTHS MES MESES');
const TIMES_WORDS = words('FOIS X F TIMES TIME VECES VEZ');
const RATE_WORDS = words('ML CC MG MCG UG UI IU G L');
const RATE_HOUR_WORDS = words('H HR HOUR HEURE HORA');
const WEEKLY_WORDS = words('WEEKLY HEBDOMADAIRE SEMANAL');
const DOSE_WORDS = words(
  'CP CPS COMPRIME COMPRIMES TAB TABS TABLET TABLETS COMPRIMIDO COMPRIMIDOS GELULE ' +
    'GELULES CAPSULE CAPSULES CAPSULA CAPSULAS SACHET SACHETS SOBRE SOBRES AMPOULE ' +
    'AMPOULES AMP AMPOLLA AMPOLLAS MG G MCG ML UI IU',
);
const TAKE_WORDS = words('PRENDRE TAKE TOMAR');
const LIST_JOINS = words('ET AND Y');
const BEFORE_BARE_CLOCK = words('AT LAS');
const BEFORE_CLOCK = words('A AT LAS VERS');
const AFTER_SPANISH_ELEVEN = words('VECES HORA HORAS');
const BARE_SLASH_HOURS = words('2 3 4 6 8 12');
/* word-lists:end */

/* grammar:start (`FrequencyCadence.GRAMMAR`, held equal by FrequencyWordingTest) */
const GRAMMAR = `
H | TOUTES LES # HOUR
H | TOUTE LES # HOUR
H | TOUS LES # HOUR
H | TTES LES # HOUR
H | TT LES # HOUR
H | EVERY # HOUR
H | CADA # HOUR
H | C / # HOUR
H | Q # HOUR
CONT | 24 H / 24
CONT | 24 H / 24 H
CONT | 24 / 24
CONT | 24 / 24 HOUR
S | # HOUR / # HOUR
S | # HOUR / #
S | # / # HOUR
B | # / #
=60 | TOUTES LES HEURES
=60 | CHAQUE HEURE
=60 | EVERY HOUR
=60 | CADA HORA
=60 | HOURLY
=60 | QH
D | TOUS LES # DAY
D | EVERY # DAY
D | CADA # DAY
D | Q # DAY
=2880 | EVERY OTHER DAY
=2880 | QOD
=2880 | UN JOUR SUR DEUX
=2880 | UN JOUR SUR 2
=2880 | 1 JOUR SUR 2
=2880 | 1 JOUR SUR DEUX
=2880 | JOURS ALTERNES
=2880 | DIAS ALTERNOS
=2880 | UN DIA SI Y OTRO NO
=2880 | UN DIA SI OTRO NO
=2880 | UN DIA SI UN DIA NO
=2880 | DIA SI DIA NO
=2880 | DIA SI Y DIA NO
W | TOUTES LES # WEEK
W | EVERY # WEEK
W | CADA # WEEK
W | Q # WEEK
PD | # TIMES PAR DAY
PD | # TIMES POR DAY
PD | # TIMES PER DAY
PD | # TIMES AL DAY
PD | # TIMES A DAY
PD | # TIMES / DAY
PD | # TIMES DAILY
PD | # TIMES PAR 24 HOUR
PD | # TIMES EN 24 HOUR
PD | # TIMES / 24 HOUR
PD | # TIMES / 24
PD | # TIMES DANS LA JOURNEE
PD | # / DAY
P24 | # / 24 HOUR
P24 | # / 24
=1440 | ONCE A DAY
=1440 | ONCE DAILY
=1440 | ONCE PER DAY
*1440 | EVERY DAY
*1440 | EVERYDAY
*1440 | TOUS LES JOURS
*1440 | CHAQUE JOUR
*1440 | TODOS LOS DIAS
*1440 | CADA DIA
*1440 | DAILY
*1440 | QUOTIDIEN
*1440 | QUOTIDIENNE
*1440 | QUOTIDIENNEMENT
*1440 | DIARIO
*1440 | DIARIA
=1440 | QD
=1440 | Q D
!=1440 | OD
=1440 | QAM
=1440 | QPM
=1440 | NIGHTLY
=1440 | EVERY NIGHT
=1440 | EVERY MORNING
=1440 | EVERY EVENING
=1440 | TOUS LES MATINS
=1440 | TOUS LES SOIRS
=1440 | CHAQUE MATIN
=1440 | CHAQUE SOIR
=1440 | CADA MANANA
=1440 | CADA NOCHE
=1440 | TODAS LAS MANANAS
=1440 | TODAS LAS NOCHES
=720 | TWICE A DAY
=720 | TWICE DAILY
=720 | TWICE PER DAY
=720 | BID
=720 | B I D
=720 | BD
=480 | THRICE DAILY
=480 | TID
=480 | T I D
=480 | TDS
=360 | QID
=360 | Q I D
=360 | QDS
PW | # TIMES PAR WEEK
PW | # TIMES POR WEEK
PW | # TIMES PER WEEK
PW | # TIMES AL WEEK
PW | # TIMES A WEEK
PW | # TIMES A LA WEEK
PW | # TIMES / WEEK
PW | # TIMES WEEKLYWORD
PW | # / WEEK
=10080 | ONCE A WEEK
=10080 | ONCE WEEKLY
=10080 | ONCE PER WEEK
*10080 | WEEKLYWORD
=10080 | QW
*10080 | EVERY WEEK
*10080 | CHAQUE SEMAINE
*10080 | TOUTES LES SEMAINES
*10080 | CADA SEMANA
*10080 | TODAS LAS SEMANAS
=5040 | TWICE A WEEK
=5040 | TWICE WEEKLY
PM | # TIMES PAR MONTH
PM | # TIMES POR MONTH
PM | # TIMES PER MONTH
PM | # TIMES AL MONTH
PM | # TIMES A MONTH
PM | # TIMES / MONTH
PM | # / MONTH
=43200 | ONCE A MONTH
*43200 | MONTHLY
*43200 | MENSUEL
*43200 | MENSUELLE
*43200 | MENSUAL
*43200 | EVERY MONTH
*43200 | CHAQUE MOIS
*43200 | TOUS LES MOIS
*43200 | CADA MES
*43200 | TODOS LOS MESES
ONE | STAT
ONE | PRISE UNIQUE
ONE | EN PRISE UNIQUE
ONE | DOSE UNIQUE
ONE | EN DOSE UNIQUE
ONE | UNE SEULE FOIS
ONE | 1 SEULE FOIS
ONE | UNE SEULE DOSE
ONE | UNE SEULE PRISE
ONE | UNE FOIS SEULEMENT
ONE | 1 FOIS SEULEMENT
!ONE | EN UNE PRISE
!ONE | EN 1 PRISE
ONE | SINGLE DOSE
ONE | ONCE ONLY
ONE | ONE TIME
ONE | ONE TIME ONLY
ONE | DOSIS UNICA
ONE | TOMA UNICA
ONE | UNA SOLA DOSIS
ONE | UNA SOLA TOMA
ONE | UNA SOLA VEZ
ONE | BOLUS
ONE | EN BOLUS
ONE | IV BOLUS
ONE | BOLUS IV
ONE | BOLO
ONE | DOSE DE CHARGE
ONE | LOADING DOSE
ONE | DOSIS DE CARGA
ONE | TPI
ONE | TPI #
ONE | TPI SP
ONE | TPI SP #
ONE | IPT
ONE | IPTP
ONE | IPTP #
ONE | IPTP SP
ONE | IPTP SP #
ONE | SP DOSE UNIQUE
CONT | CONTINU
CONT | EN CONTINU
CONT | PERFUSION CONTINUE
CONT | PERFUSION CONTINU
CONT | PERFUSION CONTINUA
CONT | EN PERFUSION CONTINUE
CONT | INFUSION CONTINUE
CONT | INFUSION CONTINUA
CONT | CONTINUOUS INFUSION
CONT | IVSE
CONT | PSE
CONT | SERINGUE ELECTRIQUE
CONT | ~ RATE / RATEHOUR
CONT | DEBIT ~ RATE / RATEHOUR
`;
/* grammar:end */

/* parts:start (`FrequencyCadence.PARTS`, held equal by FrequencyWordingTest) */
const PARTS = `
M | MATIN
M | LE MATIN
M | MORNING
M | THE MORNING
M | IN THE MORNING
M | MANANA
M | LA MANANA
M | POR LA MANANA
M | EN LA MANANA
N | MIDI
N | LE MIDI
N | A MIDI
N | NOON
N | AT NOON
N | MEDIODIA
N | AL MEDIODIA
A | APRES MIDI
A | L APRES MIDI
A | AFTERNOON
A | IN THE AFTERNOON
A | TARDE
A | LA TARDE
A | POR LA TARDE
A | EN LA TARDE
E | SOIR
E | LE SOIR
E | EVENING
E | THE EVENING
E | IN THE EVENING
B | NUIT
B | LA NUIT
B | NIGHT
B | AT NIGHT
B | NOCHE
B | LA NOCHE
B | POR LA NOCHE
B | EN LA NOCHE
B | COUCHER
B | AU COUCHER
B | AU MOMENT DU COUCHER
B | AVANT LE COUCHER
B | AVANT DE SE COUCHER
B | AVANT DE DORMIR
B | AU MOMENT DE DORMIR
B | LE SOIR AU COUCHER
B | LE SOIR AVANT DE DORMIR
B | LE SOIR AVANT DE SE COUCHER
B | BEDTIME
B | AT BEDTIME
B | BEFORE BED
B | BEFORE BEDTIME
B | BEFORE SLEEP
B | BEFORE GOING TO SLEEP
B | QHS
B | AL ACOSTARSE
B | ANTES DE ACOSTARSE
B | ANTES DE DORMIR
`;
/* parts:end */

const WORD_LISTS: ReadonlyMap<string, ReadonlySet<string>> = new Map([
  ['HOUR', HOUR_WORDS],
  ['DAY', DAY_WORDS],
  ['WEEK', WEEK_WORDS],
  ['MONTH', MONTH_WORDS],
  ['TIMES', TIMES_WORDS],
  ['RATE', RATE_WORDS],
  ['RATEHOUR', RATE_HOUR_WORDS],
  ['WEEKLYWORD', WEEKLY_WORDS],
]);

interface Line {
  meaning: string;
  tokens: readonly string[];
}

function lines(table: string): Line[] {
  return table
    .trim()
    .split('\n')
    .map((line) => line.split(' | '))
    .map(([meaning, tokens]) => ({ meaning, tokens: tokens.split(' ') }));
}

const FORMS = lines(GRAMMAR);
/** The longest first: "LE SOIR AU COUCHER" before "LE SOIR". */
const PART_PHRASES = lines(PARTS).sort((a, b) => b.tokens.length - a.tokens.length);

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
  'AD LIB',
  'COUP PAR COUP',
  'AS REQ',
  'AS DIRECTED',
  'PR N',
  'SELON TA',
  'SELON LA TA',
  'SEGUN TA',
];
/** `FrequencyWording.NOT_A_CONDITION`: a name holding a condition word. */
const NOT_A_CONDITION: readonly string[] = [
  'DE PAIN',
  'FIEVRE RHUMATISMALE',
  'RHEUMATIC FEVER',
  'FIEVRE DE LASSA',
  'FIEVRE THYPHOIDE',
  'MAL DE POTT',
  'FIEVRE TYPHOIDE',
  'FIEVRE JAUNE',
  'TYPHOID FEVER',
  'YELLOW FEVER',
  'FIEBRE TIFOIDEA',
  'FIEBRE AMARILLA',
  'DU PAIN',
  'AVEC PAIN',
  'BEFORE SLEEP',
];

const MINUTES_PER_HOUR = 60;
const MINUTES_PER_DAY = 1440;
const MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY;
const MINUTES_PER_MONTH = 30 * MINUTES_PER_DAY;
const MAX_COURSE_ENTRIES = 31;
const MIN_CLOCK_GAP_HOURS = 4;
const DECIMAL = 'DECIMAL';
const COMPARISON = /[<>≤≥]/;
const WHO_GETS_IT = /[<>≤≥]=? ?\d{1,3} ?(?:KG|ANS|YEARS?|ANOS|MOIS)\b/g;
const SLASH_SP = /\bS *\/ *P\b/;
const WEIGHT_BAND = /\b(?:POIDS|PESO|WEIGHT|\d+ *KG)\b/;
const ONLY_DIGITS = /^\d{1,4}$/;
const SEPARATOR = /^[\s\p{Z}\uFEFF_.,'\u2019]$/u;
const LABEL_AND_CODE = /^([^()]*)\(([^()]*)\)\s*$/;
/** Spanish "si" with its accent is "yes", never "if": `FrequencyWording.ACCENTED_SI`. */
const ACCENTED_SI = /(?<!\p{L})[sS][\u00ed\u00cd](?!\p{L})/gu;

/** A count of doses written as a word, `MarDoseSchedule.countDigitOf`. English "once" is no eleven here. */
const COUNT_DIGITS: ReadonlyMap<string, number> = new Map(
  (
    [
      [1, 'UNE UN UNA UNO ONE'],
      [2, 'DEUX DOS TWO'],
      [3, 'TROIS TRES THREE'],
      [4, 'QUATRE CUATRO FOUR'],
      [5, 'CINQ CINCO FIVE'],
      [6, 'SIX SEIS'],
      [7, 'SEPT SIETE SEVEN'],
      [8, 'HUIT OCHO EIGHT'],
      [9, 'NEUF NUEVE NINE'],
      [10, 'DIX DIEZ TEN'],
      [11, 'ONZE ELEVEN'],
      [12, 'DOUZE DOCE TWELVE'],
    ] as const
  ).flatMap(([digit, names]) => names.split(' ').map((name) => [name, digit] as [string, number])),
);

/** What a frequency reads as: `MarDoseSchedule.Reading`. */
export interface FrequencyReading {
  kind: 'REPEATING' | 'ONE_OFF' | 'UNREAD' | 'CONTINUOUS' | 'COURSE';
  intervalMinutes?: number;
  courseOffsets?: readonly number[];
}

const ONE_OFF: FrequencyReading = { kind: 'ONE_OFF' };
const UNREAD: FrequencyReading = { kind: 'UNREAD' };
const CONTINUOUS: FrequencyReading = { kind: 'CONTINUOUS' };

function accentlessUpper(text: string): string {
  return text.normalize('NFD').replaceAll(/\p{M}/gu, '').toUpperCase();
}

function every(minutes: number): FrequencyReading | null {
  return minutes > 0 ? { kind: 'REPEATING', intervalMinutes: minutes } : null;
}

function countOver(count: number, period: number): FrequencyReading | null {
  const minutes = count > 0 ? Math.floor(period / count) : 0;
  return minutes >= MINUTES_PER_HOUR ? { kind: 'REPEATING', intervalMinutes: minutes } : null;
}

/** `FrequencyCadence.tokensOf`. */
function tokensOf(upper: string): string[] {
  const joined = upper
    .replaceAll(/\b(\d{1,2})[:H][0-5]\d\b/g, '$1HMIN')
    .replaceAll(/(?<![\d.,])\d{1,4}[.,]\d{1,3}(?![\d.,])/g, ` ${DECIMAL} `)
    .replaceAll('VINGT-QUATRE', '24')
    .replaceAll('VEINTICUATRO', '24')
    .replaceAll('TWENTY-FOUR', '24')
    .replaceAll('QUARANTE-HUIT', '48')
    .replaceAll('FORTY-EIGHT', '48')
    .replaceAll(/(?<![\dA-Z])[.,](?=\d)|(?<=[A-Z])\.(?=\d)|(?<=\d)['\u2019]/g, ' ? ')
    .replaceAll(/(?<=[A-Z])-(?=[A-Z])/g, ' ');
  return (joined.match(/[A-Z]+|\d+|[^A-Z0-9]/gu) ?? []).filter((token) => !SEPARATOR.test(token));
}

function wholeNumber(token: string): number | null {
  if (token.length <= 2 && ONLY_DIGITS.test(token)) return Number(token);
  return COUNT_DIGITS.get(token) ?? null;
}

function isDoseNumber(token: string): boolean {
  return token === DECIMAL || ONLY_DIGITS.test(token) || wholeNumber(token) !== null;
}

function fits(expected: string, token: string): boolean {
  if (expected === '~') return isDoseNumber(token);
  const list = WORD_LISTS.get(expected);
  return list ? list.has(token) : expected === token;
}

function isSpanishEleven(tokens: readonly string[], at: number): boolean {
  return tokens[at] === 'ONCE' && AFTER_SPANISH_ELEVEN.has(tokens[at + 1] ?? '');
}

function matched(form: readonly string[], tokens: readonly string[]): number[] | null {
  if (form.length !== tokens.length) return null;
  const numbers: number[] = [];
  for (let i = 0; i < form.length; i++) {
    if (form[i] === '#') {
      const number = wholeNumber(tokens[i]);
      if (number === null && !isSpanishEleven(tokens, i)) return null;
      numbers.push(number ?? 11);
    } else if (!fits(form[i], tokens[i])) {
      return null;
    }
  }
  return numbers;
}

function readingOf(line: string, numbers: readonly number[]): FrequencyReading | null {
  const n = numbers[0] ?? 0;
  const meaning = line.startsWith('!') ? line.slice(1) : line;
  switch (meaning) {
    case 'ONE':
      return ONE_OFF;
    case 'CONT':
      return CONTINUOUS;
    case 'H':
      return n <= 72 ? every(n * MINUTES_PER_HOUR) : null;
    case 'S':
      return n === numbers[1] && n < 24 ? every(n * MINUTES_PER_HOUR) : null;
    case 'B':
      return n === numbers[1] && BARE_SLASH_HOURS.has(String(n))
        ? every(n * MINUTES_PER_HOUR)
        : null;
    case 'D':
      return every(n * MINUTES_PER_DAY);
    case 'W':
      return every(n * MINUTES_PER_WEEK);
    case 'PD':
      return countOver(n, MINUTES_PER_DAY);
    case 'P24':
      return n <= 4 ? countOver(n, MINUTES_PER_DAY) : null;
    case 'PW':
      return countOver(n, MINUTES_PER_WEEK);
    case 'PM':
      return countOver(n, MINUTES_PER_MONTH);
    default:
      return every(Number(meaning.slice(1)));
  }
}

/** The index after a joining word, -1 when it ends the text, `at` when none stands there. */
function afterJoin(tokens: readonly string[], at: number): number {
  if (at >= tokens.length || !LIST_JOINS.has(tokens[at])) return at;
  return at + 1 === tokens.length ? -1 : at + 1;
}

function partAt(tokens: readonly string[], at: number): Line | null {
  return (
    PART_PHRASES.find((part) => part.tokens.every((word, i) => tokens[at + i] === word)) ?? null
  );
}

/** `FrequencyCadence.startsAList`. */
function startsAList(tokens: readonly string[], after: number): boolean {
  if (after >= tokens.length) return false;
  if (LIST_JOINS.has(tokens[after])) return true;
  const next = partAt(tokens, after);
  return next !== null && next.tokens.length === 1 && after + 1 < tokens.length;
}

/** `FrequencyCadence.partsOfDay`. */
function partsOfDay(tokens: readonly string[]): FrequencyReading | null {
  const seen: string[] = [];
  let at = 0;
  let joined = false;
  while (at < tokens.length) {
    const part = partAt(tokens, at);
    if (!part || seen.includes(part.meaning)) return null;
    if (!joined && part.meaning === 'B' && seen.at(-1) === 'E') return null;
    seen.push(part.meaning);
    const next = at + part.tokens.length;
    const bareWord = part.tokens.length === 1 ? part.tokens[0] : '';
    const tomorrow = bareWord === 'MANANA' && !startsAList(tokens, next);
    if (tomorrow || (bareWord === 'TARDE' && tokens.length === 1)) return null;
    at = afterJoin(tokens, next);
    if (at < 0) return null;
    joined = at !== next;
  }
  return countOver(seen.length, MINUTES_PER_DAY);
}

function entryAt(tokens: readonly string[], at: number, letter: string): number | null {
  const digits = tokens[at + 1];
  const anEntry =
    tokens[at] === letter && digits !== undefined && ONLY_DIGITS.test(digits) && digits.length <= 3;
  return anEntry ? Number(digits) : null;
}

/** `FrequencyCadence.course`. */
function course(tokens: readonly string[]): FrequencyReading | null {
  const letter = tokens[0];
  if (letter !== 'H' && letter !== 'J') return null;
  const values: number[] = [];
  let at = 0;
  while (at < tokens.length) {
    const value = entryAt(tokens, at, letter);
    at = value === null ? -1 : afterJoin(tokens, at + 2);
    if (value === null || at < 0) return null;
    values.push(value);
  }
  if (letter === 'H' && values[0] !== 0) return null;
  if (values.length < 2 || values.length > MAX_COURSE_ENTRIES) return null;
  if (!values.every((v, i) => i === 0 || v > values[i - 1])) return null;
  const unit = letter === 'H' ? MINUTES_PER_HOUR : MINUTES_PER_DAY;
  return { kind: 'COURSE', courseOffsets: values.map((v) => (v - values[0]) * unit) };
}

function wholeDigits(token: string | undefined): number | null {
  return token !== undefined && token.length <= 2 && ONLY_DIGITS.test(token) ? Number(token) : null;
}

function hourOf(number: number, mark: string): number {
  if (mark === 'PM' && number < 12) return number + 12;
  return mark === 'AM' && number === 12 ? 0 : number;
}

/** `FrequencyCadence.ClockList`. */
class ClockList {
  readonly hours: number[] = [];
  private marked = false;

  readOneAt(tokens: readonly string[], at: number): number {
    let i = at;
    if (tokens[i] === 'A' && tokens[i + 1] === 'LAS') i++;
    const before = BEFORE_CLOCK.has(tokens[i]) ? tokens[i] : '';
    if (before) i++;
    const number = wholeDigits(tokens[i]);
    if (number === null) return -1;
    const after = tokens[i + 1] ?? '';
    if (after === 'AM' || after === 'PM') return this.add(hourOf(number, after), true, i + 2);
    if (after === 'HMIN') return this.add(number, true, i + 2);
    if (after === 'H') return this.add(number, before !== '', i + 2);
    return BEFORE_BARE_CLOCK.has(before) ? this.add(number, true, i + 1) : -1;
  }

  private add(hour: number, isMarked: boolean, next: number): number {
    if (hour > 24) return -1;
    this.hours.push(hour);
    this.marked = this.marked || isMarked;
    return next;
  }

  reading(): FrequencyReading | null {
    if (this.hours.length === 0 || (this.hours.length === 1 && !this.marked)) return null;
    if (this.hours[this.hours.length - 1] - this.hours[0] >= 24) return null;
    const tooClose = this.hours.some(
      (hour, i) => i > 0 && hour - this.hours[i - 1] < MIN_CLOCK_GAP_HOURS,
    );
    return tooClose ? null : countOver(this.hours.length, MINUTES_PER_DAY);
  }
}

function clockTimes(tokens: readonly string[]): FrequencyReading | null {
  const list = new ClockList();
  let at = 0;
  while (at < tokens.length) {
    const next = list.readOneAt(tokens, at);
    at = next < 0 ? -1 : afterJoin(tokens, next);
    if (at < 0) return null;
  }
  return list.reading();
}

/** `FrequencyCadence.posology`: "1-0-1", the whole text (QA D1291, user decision). */
function posology(tokens: readonly string[]): FrequencyReading | null {
  if (tokens.length !== 5 && tokens.length !== 7) return null;
  const shaped = tokens.every((token, i) => (i % 2 === 1 ? token === '-' : /^\d$/.test(token)));
  if (!shaped) return null;
  const doses = tokens.filter((token, i) => i % 2 === 0 && token !== '0').length;
  return countOver(doses, MINUTES_PER_DAY);
}

function readForm(tokens: readonly string[]): FrequencyReading | null {
  for (const form of FORMS) {
    const numbers = matched(form.tokens, tokens);
    const reading = numbers === null ? null : readingOf(form.meaning, numbers);
    if (reading) return reading;
  }
  return partsOfDay(tokens) ?? course(tokens) ?? posology(tokens) ?? clockTimes(tokens);
}

function afterDose(tokens: readonly string[]): number {
  const at = tokens.length > 0 && TAKE_WORDS.has(tokens[0]) ? 1 : 0;
  const aDose =
    at + 1 < tokens.length && isDoseNumber(tokens[at]) && DOSE_WORDS.has(tokens[at + 1]);
  return aDose ? at + 2 : at;
}

function doseOfSeveral(tokens: readonly string[], after: number): boolean {
  if (after < 2 || !DOSE_WORDS.has(tokens[after - 1])) return false;
  const dose = tokens[after - 2];
  return dose !== DECIMAL && wholeNumber(dose) !== 1;
}

function isAmountOverAPeriod(rest: readonly string[]): boolean {
  return FORMS.some((form) => form.meaning.startsWith('*') && matched(form.tokens, rest) !== null);
}

function standsAlone(rest: readonly string[]): boolean {
  return FORMS.some((form) => form.meaning.startsWith('!') && matched(form.tokens, rest) !== null);
}

function readWithDose(tokens: readonly string[]): FrequencyReading | null {
  const whole = readForm(tokens);
  if (whole) return whole;
  const after = afterDose(tokens);
  if (after === 0 || after === tokens.length) return null;
  const rest = tokens.slice(after);
  if (standsAlone(rest) || (doseOfSeveral(tokens, after) && isAmountOverAPeriod(rest))) return null;
  const behind = readForm(rest);
  return behind?.kind === 'CONTINUOUS' ? null : behind;
}

function sameReading(a: FrequencyReading, b: FrequencyReading | null): boolean {
  return (
    b !== null &&
    a.kind === b.kind &&
    a.intervalMinutes === b.intervalMinutes &&
    (a.courseOffsets ?? []).join() === (b.courseOffsets ?? []).join()
  );
}

/** `MarDoseSchedule.read(String)`: the one reader of a frequency. */
export function frequencyReading(frequency: string | null | undefined): FrequencyReading {
  if (!frequency?.trim()) return ONE_OFF;
  const upper = accentlessUpper(frequency);
  const label = LABEL_AND_CODE.exec(upper);
  if (label) {
    const words = readWithDose(tokensOf(label[1]));
    return words && sameReading(words, readWithDose(tokensOf(label[2]))) ? words : UNREAD;
  }
  return readWithDose(tokensOf(upper)) ?? UNREAD;
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

function containsPhrase(list: readonly string[], phrases: readonly string[]): boolean {
  const spaced = ` ${list.join(' ')} `;
  return phrases.some((phrase) => spaced.includes(` ${phrase} `));
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

function withoutNames(all: readonly string[]): string[] {
  const prevention = all.findIndex((word) => PREVENTION_WORDS.has(word));
  const list = prevention < 0 ? all : all.slice(0, prevention);
  let spaced = ` ${list.join(' ')} `;
  for (const name of NOT_A_CONDITION) spaced = spaced.replaceAll(` ${name} `, ' ');
  const kept = spaced.trim();
  return kept ? kept.split(' ') : [];
}

function readsConditional(text: string, list: readonly string[]): boolean {
  if (
    COMPARISON.test(text.replaceAll(WHO_GETS_IT, ' ')) ||
    SLASH_SP.test(text) ||
    readsRound31Phrase(text)
  )
    return true;
  const named = withoutNames(list);
  const joined = joinSingleLetters(named);
  return (
    list.at(-1) === 'REPETER' ||
    joined.some((word) => CONDITIONAL_WORDS.has(word)) ||
    containsPhrase(named, CONDITIONAL_PHRASES) ||
    containsPhrase(joined, CONDITIONAL_PHRASES)
  );
}

function wordsOf(text: string): string[] {
  const normal = accentlessUpper(text)
    .replaceAll(/[^A-Z0-9]+/g, ' ')
    .trim();
  return normal ? normal.split(' ') : [];
}

/** It names a weight and no condition stands in it: `FrequencyWording.isWeightBand`. */
function isWeightBand(bracketed: string): boolean {
  return (
    WEIGHT_BAND.test(bracketed) &&
    wordsOf(bracketed)
      .filter((word) => word !== 'SI')
      .every((word) => !CONDITIONAL_WORDS.has(word))
  );
}

/**
 * What an order NOT ticked as needed says with this frequency, as the server
 * reads it: `FrequencyWording.classify`.
 */
export function unTickedFrequencyVerdict(frequency: string | null | undefined): FrequencyVerdict {
  if (!frequency?.trim()) return 'FIXED';
  if (frequencyReading(frequency).kind !== 'UNREAD') return 'FIXED';
  const text = accentlessUpper(frequency.normalize('NFC').replaceAll(ACCENTED_SI, ' '))
    .replaceAll(/\([^()]*\)/g, (part) => (isWeightBand(part) ? ' ' : part))
    .replaceAll(/[_.,\s]+/g, ' ')
    .trim();
  return readsConditional(text, wordsOf(text)) ? 'CONDITIONAL' : 'UNREADABLE';
}
