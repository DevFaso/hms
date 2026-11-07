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
    'ESSOUFFLEMENT MAREOS PICAZON PRURITO SANGRADO CEFALEA MIGRANA ATAQUES DORMIR ' +
    'SYMPTOMES SINTOMAS SYMPTOMS TEMPERATURE TEMPERATURA ONSET PARFOIS SOMETIMES ' +
    'POUSSEES FEBRILE APPARITION SIGNES SIGNS',
);
const PREVENTION_WORDS = words(
  'PREVENIR PREVENTION PROPHYLAXIE PREVENCION PREVENT PROPHYLAXIS PROFILAXIS',
);
const MINUTE_UNITS = words('MIN MN MINUTE MINUTES MINUTOS');
const DAY_WORDS = words('JOUR JOURS J JR JRS D DAY DAYS DIA DIAS');
const WEEK_WORDS = words('SEMAINE SEMAINES SEM WEEK WEEKS SEMANA SEMANAS');
const MONTH_WORDS = words('MOIS MONTH MONTHS MES MESES');
const QUARTER_WORDS = words('TRIMESTRE TRIMESTRES TRIMESTER');
const YEAR_WORDS = words('AN ANS ANNEE YEAR YEARS ANO ANOS');
const DATE_BEFORE = words(
  'LE DU AU DE EL DEL ON FROM TO UNTIL HASTA SINCE DESDE JUSQU PARTIR RDV DES',
);
const STEP_WORDS = words(
  'PUIS ENSUITE THEN LUEGO SUIVI SUIVIE FOLLOWED RELAIS SAUF EXCEPT EXCEPTO ' +
    'RENOUVELER RENOUVELABLE REPETER INTERVALLE INTERVALO INTERVAL ESPACE ESPACEE ' +
    'ESPACES ESPACEES SEPARADAS SEPARADOS SPACED APART ENTRE BETWEEN VEILLE',
);
const LOADING_WORDS = words('CHARGE CARGA LOADING');
const COUNT_PREFIXES = words('BI TRI SEMI');
const BEDTIME_WORDS = words('COUCHER BEDTIME ACOSTARSE HS QHS');
const EVENING_ONLY_WORDS = words('SOIR SOIRS SOIREE EVENING EVENINGS TARDE TARDES');
const REASON_OPENERS = words('POUR PARA FOR SELON SEGUN SUIVANT ACCORDING CONTRE CONTRA AGAINST');
const ARTICLES = words('LE LA LES L DE DU DES UN UNE EL LOS LAS THE A AN TO');
const ORDERED_REASONS = words(
  'ORDONNANCE PRESCRIPTION MEDICALE PROTOCOLE PROTOCOLO PROTOCOL SCHEMA ESQUEMA ' +
    'POIDS PESO WEIGHT RECETA MAINTENANCE ENTRETIEN LIFE VIE AVIS MEDICO MEDICAL',
);
const NOT_DAILY_BEFORE_X = words('IM IV SC DOSE DOSES DOSIS');
const SPLIT_WORDS = words('FOIS DOSES DOSIS INJECTIONS PERFUSIONS DIVIDED');
const BEFORE_MORNING = words('LA LAS CADA EVERY');
const AFTER_MORNING = words('Y NOCHE MEDIODIA TARDE');
const GOES_ON_WORDS = words('PARTIR DES PUIS THEN LUEGO DESDE STARTING');
const AWAY_FROM_MEALS = words('DEHORS ENTRE DISTANCE AWAY BETWEEN FUERA');
const ALL_MEALS_BEFORE = words('LES DES AUX CHAQUE CADA EACH EVERY LAS LOS');
const MEALS_WORDS = words('MEALS COMIDAS AC PC');
const A_MEAL_WORDS = words('REPAS MEAL COMIDA FOOD');
const NAMED_MEAL_WORDS = words('DEJEUNER DEJ DINER BREAKFAST LUNCH DINNER DESAYUNO ALMUERZO CENA');
const FASTING_WORDS = words('JEUN FASTING AYUNAS AYUNO');
const INFUSION_WORDS = words(
  'PERFUSION PERFUSIONS INFUSION IV PERF SERINGUE BOMBA POMPE PUMP DRIP',
);
const DROP_WORDS = words('GOUTTE GOUTTES GOTA GOTAS DROP DROPS GTT GTTS GTTE');
const PERIOD_JOINS = words('PAR PER POR AL A P /');
const PERIOD_ARTICLES = words('LA LE EL');
const MEASURE_UNITS = words(
  'MG MCG UG G KG ML CC L UI IU GOUTTE GOUTTES GOTA GOTAS DROP DROPS BOUFFEE ' +
    'BOUFFEES PUFF PUFFS',
);
const EVERY_WORDS = words('CHAQUE CADA EVERY EACH');
const ALL_WORDS = words('TOUS TOUTES TT TTE TTES TODOS TODAS');
const ALL_ARTICLES = words('LES LOS LAS');
const ONCE_A_DAY_WORDS = words(
  'QD OD QAM QPM QHS HS DAILY NIGHTLY QUOTIDIEN QUOTIDIENNE QUOTIDIENNEMENT ' +
    'DIARIO DIARIA DIARIAMENTE JOURNALIER JOURNALIERE',
);
const TWICE_A_DAY_WORDS = words('BID BD');
const THREE_A_DAY_WORDS = words('TID TDS');
const FOUR_A_DAY_WORDS = words('QID QDS');
const HOURLY_WORDS = words('QH HOURLY');
const OTHER_DAY_WORDS = words('QOD');
const WEEKLY_WORDS = words('QW WEEKLY HEBDOMADAIRE HEBDOMADAIRES SEMANAL');
const MONTHLY_WORDS = words('MONTHLY MENSUEL MENSUELLE MENSUELLEMENT MENSUAL');
const YEARLY_WORDS = words('YEARLY ANNUEL ANNUELLE ANUAL');
const ALTERNATE_WORDS = words('ALTERNE ALTERNES ALTERNOS ALTERNATE');
const WEEKDAY_WORDS = words(
  'LUNDI MARDI MERCREDI JEUDI VENDREDI SAMEDI DIMANCHE MONDAY TUESDAY WEDNESDAY ' +
    'THURSDAY FRIDAY SATURDAY SUNDAY LUNES MARTES MIERCOLES JUEVES VIERNES SABADO ' +
    'DOMINGO',
);
const MORNING_WORDS = words(
  'MATIN MATINS MATINEE MORNING MORNINGS MANANA MANANAS MANE REVEIL LEVER ' + 'LEVANTARSE WAKING',
);
const NOON_WORDS = words('MIDI MEDIODIA NOON');
const EVENING_WORDS = words(
  'SOIR SOIRS SOIREE EVENING EVENINGS TARDE TARDES NUIT NUITS NIGHT NIGHTS NOCHE ' +
    'NOCHES NOCTE COUCHER BEDTIME ACOSTARSE',
);
const THIS_WORDS = words('CE CET CETTE THIS ESTA');
const ONE_TIME_WORDS = words(
  'TONIGHT DEMAIN LENDEMAIN TOMORROW IMMEDIATEMENT IMMEDIATELY INMEDIATAMENTE ' +
    'MAINTENANT NOW AHORA',
);
const CONTINUOUS_WORDS = words('CONTINU CONTINUE CONTINUS CONTINUES CONTINUOUS CONTINUO CONTINUA');
const RUNNING_WORDS = words('IVSE PSE DEBIT');
const SINGLE_WORDS = words('UNIQUE UNICA UNICO SINGLE SEUL SEULE SOLO SOLA');
const DOSE_WORDS = words('DOSE DOSIS PRISE TOMA FOIS VEZ TIME');
const INTAKE_WORDS = words('PRISE PRISES TOMA TOMAS');
const PREVENTIVE_DOSE_WORDS = words('TPI TPIP TPIG IPT IPTP IPTI');
const ONCE_EVENT_WORDS = words(
  'NAISSANCE BIRTH NACIMIENTO DELIVRANCE EXPULSION ACCOUCHEMENT DELIVERY PARTO ' +
    'CPN ANC SORTIE DISCHARGE ALTA ADMISSION INTERVENTION CHIRURGIE SURGERY CIRUGIA ' +
    'OPERATOIRE PREOPERATOIRE PREOP PARTUM POSTPARTUM TRAVAIL LABOR LABOUR ' +
    'CONSULTATION',
);
const UNTIL_WORDS = words('JUSQU JUSQUE JUSQUA HASTA UNTIL');
const BOLUS_WORDS = words('BOLUS IVD IVL');
const DURATION_BEFORE = words('SUR PENDANT DURANT DURANTE FOR OVER DANS EN IN');
const BEFORE_CLOCK = words('A AT LAS VERS');
const BEFORE_BARE_CLOCK = words('AT LAS');
const HOUR_WORDS = words('H HR HRS HS HOUR HOURS HEURE HEURES HORA HORAS');
const SINGLE_HOUR_UNIT = words('H');
const TIMES_WORDS = words('FOIS X VECES VEZ TIMES TIME');
const RANGE_TO_WORDS = words('A AU TO');
const LIST_JOINS = words('ET AND Y');
const IN_A_ROW_WORDS = words(
  'SUITE CONSECUTIFS CONSECUTIVES CONSECUTIVOS SEGUIDOS CONSECUTIVE ROW',
);
const LIMIT_WORDS = words(
  'MAX MAXI MAXIMUM MAXIMUN MAXIMAL MAXIMALE MAXIMO MAXIMA UPTO DEPASSER DEPASSE ' +
    'EXCEED EXCEDER SUPERAR LIMITE LIMIT LIMITED MINIMUM MINIMA MINIMO',
);
const LIMIT_BEFORE_NUMBER = words('JUSQU JUSQUE JUSQUA HASTA');
const RANGE_JOINS = words('A TO OU OR O');
const CHOICE_WORDS = words('OU OR O');
const AFTER_RANGE = words(
  'PAR PER POR AL J JOUR JOURS D DAY DAYS DIA DIAS PRISES TOMAS DOSES DOSIS',
);
const BARE_SLASH_HOURS = words('2 3 4 6 8 12');
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
const LIMIT_PHRASES: readonly string[] = [
  'UP TO',
  'AT MOST',
  'AU PLUS',
  'NO MORE THAN',
  'NOT MORE THAN',
  'PAS PLUS DE',
  'NO MAS DE',
  'A LO SUMO',
  'AU MOINS',
  'AT LEAST',
  'AL MENOS',
  'OU PLUS',
  'OR MORE',
  'O MAS',
  'EN TOUT',
  'AU TOTAL',
  'IN TOTAL',
  'EN TOTAL',
];

const MINUTES_PER_HOUR = 60;
const MINUTES_PER_DAY = 1440;
const MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY;
const MINUTES_PER_MONTH = 30 * MINUTES_PER_DAY;
const MINUTES_PER_QUARTER = 90 * MINUTES_PER_DAY;
const MINUTES_PER_YEAR = 365 * MINUTES_PER_DAY;
const MAX_COURSE_ENTRIES = 31;

const HOUR_UNIT = '(?:H|HR|HRS|HS|HOUR|HOURS|HEURE|HEURES|HORA|HORAS)';
const EVERY = '(?:EVERY|TOUTES LES|CHAQUE|CADA)';
const PER_DAY_SLASH_AT = /^\/ ?(?:JOURS?|JRS?|J|DIAS?|DAYS?|D)\b/;
const PER_DAY_WORDS = /^(?:PAR JOUR|PAR 24 ?H|AL DIA|POR DIA|DAILY|A DAY|PER DAY)\b/;
const COMPARISON = /[<>≤≥]/;
const WHO_GETS_IT = /[<>≤≥]=? ?\d{1,3} ?(?:KG|ANS|YEARS?|ANOS|MOIS)\b/g;
const SLASH_SP = /\bS *\/ *P\b/;
const WEIGHT_BAND = /\b(?:POIDS|PESO|WEIGHT|\d+ *KG)\b/;
const NUMBER_THEN_REST = /^(\d{1,4})([A-Z0-9]*)$/;
const EVERY_MINUTES =
  /\b(?:EVERY|TOUTES LES|TTES LES|TTE LES|TT LES|CADA|CHAQUE|Q) ?(\d{1,3}) ?(?:MINUTES|MINUTE|MINUTOS|MIN|MN)\b/;
const TT_LES_HOURS = /\b(?:TTES|TTE|TT) LES (\d{1,2}) ?(?:HEURES|HEURE|HRS|HR|H)\b/;
const PAR_HOURS = /\bPAR (\d{1,2}) ?H\b/;
const BARE_Q = /\bQ(\d{1,2})\b(?![-/])(?! ?[A-Z])/;
const DOSE_PER_HOURS = /(?<![0-9] ?H)(?<=[A-Z]) ?\/ ?(\d{1,2}) ?H\b/;
const BARE_SLASH = /\b(\d{1,2}) ?\/ ?(\d{1,2})\b(?! ?\/)/g;
const COUNT_PER_24 =
  /\b(\d{1,2}) ?(?:FOIS ?|X ?)?(?:\/|EN\b|PAR\b) ?24(?: ?(?:HEURES?|HOURS?|HRS?|HORAS?|HS|H))?\b/;
const EVERY_TWO_NUMBERS =
  /\b(?:EVERY|TOUTES LES|TOUS LES|CADA|Q) ?(\d{1,2}) ?(?:HEURES?|HOURS?|HRS?|HORAS?|HS|H)? ?(?:-|\/|\bA\b|\bTO\b|\bOU\b|\bOR\b) ?Q? ?(\d{1,2})/;
const EVERY_HOURS_AND_PART =
  /\b(?:EVERY|TOUTES LES|CADA|Q) ?(?:\d{1,2} ?)?(?:HEURES?|HOURS?|HORAS?|H) ?(?:(?:ET|AND|Y) )?(?:\d{2}\b|DEMIE|A HALF|MEDIA)/;
const PER_24_HOURS = /\/ ?24 ?(?:HEURES?|HOURS?|HRS?|HORAS?|HS|H)\b/g;
const DAY_ENTRY = /^J\d{1,3}$/;
const MIN_CLOCK_GAP_HOURS = 4;
const Q_LONG =
  /\bQ ?(\d{1,2}) ?(D|DAYS?|J|JOURS?|DIAS?|W|WEEKS?|SEM|SEMAINES?|SEMANAS?|MOIS|MONTHS?|MES|MESES)\b/;
const DASHED_COURSE = /\b([JH]\d{1,3}) ?- ?([JH]\d{1,3})\b/g;
const RANGE_PER_24 = /\b\d{1,2} ?(?:-|A|OU|OR|TO) ?\d{1,2} ?\/ ?24(?!\d)/;
const LAST_WORD = /([A-Z]+)[^A-Z0-9]*$/;
const DOSE_PART = 'DOSEPART';
const NOT_A_RATE =
  '(?! ?\\d)(?! ?/)(?! ?(?:FOIS|X\\b|VECES|TIMES|H\\b|HEURE|HOUR|HORA|J\\b|JOUR|DAY|DIA))';
const MIXED_DOSE = new RegExp(`(?<![\\d/:.,])\\d{1,3} (?:1/[234]|2/[34]|3/4)${NOT_A_RATE}`, 'g');
const FRACTION_DOSE = new RegExp(
  `(?<![\\d/:.,])(?:1 ?/ ?[234]|2 ?/ ?[34]|3 ?/ ?4)${NOT_A_RATE}`,
  'g',
);
const FRACTION_SIGN = /\d{0,3} ?[\u00bd\u00bc\u00be]/g;
const DECIMAL_DOSE = /(?<![\d:.,])\d{1,4}[.,]\d{1,3}(?![\d.,:])/g;
const RATE = /\b(?:ML|CC|MG|MCG|UG|UI|IU|G|L) ?\/ ?(?:H|HR|HEURE|HORA|HOUR)\b/;
const ALL_DAY = /\b24 ?H? ?\/ ?24\b/;
const POSOLOGY_WORD = /(?<![A-Z\d-])\d(?:-\d){2,3}(?![\d-])/;
const GLUED_COUNT = /^[XF]?(\d{1,2})[XF]?$/;
const GLUED_TIMES = /^(\d{1,2})X(\d{1,2})$/;
const GLUED_CLOCK = /^(\d{1,2})(H|AM|PM)(\d{2})?$/;
const COURSE_ENTRY = /^([JH])(\d{1,3})$/;
const ONLY_DIGITS = /^\d{1,4}$/;
const ONLY_LETTERS = /^[A-Z]+$/;
const ONE_OFF_WORDS = words(
  'ONCE ONCEONLY ONETIME SINGLEDOSE IMMEDIATELY NOW 1X 1FOIS UNESEULEFOIS 1FOISSEULEMENT DOSEUNIQUE ' +
    'ENDOSEUNIQUE PRISEUNIQUE IMMEDIATEMENT MAINTENANT 1VEZ UNASOLAVEZ DOSISUNICA AHORA INMEDIATAMENTE',
);
const NAMED_HOURS: ReadonlyMap<string, number> = new Map(
  (
    [
      [
        24,
        'QD OD DAILY ONCEDAILY ONCEADAY QAM QPM QHS HS 1XDAILY EVERYDAY TOUSLESJOURS CHAQUEJOUR QUOTIDIEN ' +
          'QUOTIDIENNE QUOTIDIENNEMENT DIARIO DIARIA CADADIA TODOSLOSDIAS',
      ],
      [12, 'BID BD TWICEDAILY TWICEADAY MATINETSOIR MANANAYNOCHE'],
      [8, 'TID TDS THREETIMESDAILY THREETIMESADAY MATINMIDIETSOIR MANANAMEDIODIAYNOCHE'],
      [6, 'QID QDS FOURTIMESDAILY FOURTIMESADAY'],
      [1, 'QH HOURLY'],
      [48, 'EVERYOTHERDAY QOD UNJOURSURDEUX TOUSLESDEUXJOURS CADADOSDIAS DIASALTERNOS'],
    ] as const
  ).flatMap(([hours, names]) => names.split(' ').map((n) => [n, hours] as [string, number])),
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

/**
 * `MarDoseSchedule.normalize`: separators read as spaces, number words as
 * digits, runs of spaces as one.
 */
function normalize(text: string): string {
  const plain = accentlessUpper(text.trim())
    .replaceAll(/[_.,]/g, ' ')
    .replaceAll(/\s+/g, ' ')
    .trim();
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

function validHours(hours: number): number | null {
  return hours > 0 && hours <= 72 ? hours : null;
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

function timesPerDay(f: string): number | null {
  const compact = f.replaceAll(' ', '');
  if (/^\d(?:-\d){2,3}$/.test(compact)) {
    return [...compact].filter((c) => c >= '1' && c <= '9').length;
  }
  for (const m of f.matchAll(/\b(\d{1,2}) ?(?:X|FOIS|VECES|VEZ|TIMES) ?/g)) {
    const rest = f.slice(m.index + m[0].length);
    if (PER_DAY_SLASH_AT.test(rest) || PER_DAY_WORDS.test(rest)) {
      return Number(m[1]) <= 24 ? Number(m[1]) : null;
    }
  }
  const bare = /\b(\d{1,2}) ?\/ ?(?:JOURS?|JRS?|J|DIAS?|DAYS?|D)\b/.exec(f);
  if (bare) return Number(bare[1]) <= 24 ? Number(bare[1]) : null;
  return null;
}

function readMinutes(text: string): number | null {
  if (!text.trim() || readsRound31Phrase(text)) return null;
  const f = normalize(text);
  const hours = hoursOf(f);
  if (hours !== null) return hours * MINUTES_PER_HOUR;
  const perDay = timesPerDay(f);
  if (perDay !== null) return perDay > 0 ? Math.floor(MINUTES_PER_DAY / perDay) : null;
  const named = NAMED_HOURS.get(f.replaceAll(' ', ''));
  return named === undefined ? null : named * MINUTES_PER_HOUR;
}

function intervalMinutesOf(text: string): number | null {
  const open = text.indexOf('(');
  const close = text.indexOf(')', open + 1);
  if (open >= 0 && close > open) {
    return (
      readMinutes(`${text.slice(0, open)} ${text.slice(close + 1)}`) ??
      readMinutes(text.slice(open + 1, close))
    );
  }
  return readMinutes(text);
}

function numberOf(word: string): number | null {
  if (ONLY_DIGITS.test(word)) return Number(word);
  const digit = COUNT_DIGITS.get(word);
  return digit ? Number(digit) : null;
}

function containsPhrase(list: readonly string[], phrases: readonly string[]): boolean {
  const spaced = ` ${list.join(' ')} `;
  return phrases.some((phrase) => spaced.includes(` ${phrase} `));
}

function isPer(list: readonly string[], at: number): boolean {
  return AFTER_RANGE.has(list[at] ?? '');
}

function endsARange(list: readonly string[], at: number, second: number): boolean {
  const word = list[at];
  if (word === undefined) return false;
  if (HOUR_WORDS.has(word)) return second !== 24;
  return (
    TIMES_WORDS.has(word) || isPer(list, at) || (numberOf(word) === null && isPer(list, at + 1))
  );
}

function rangeStartsAt(list: readonly string[], at: number): boolean {
  const first = list[at] === DOSE_PART ? 0 : numberOf(list[at]);
  if (first === null || (at > 0 && numberOf(list[at - 1]) !== null)) return false;
  if (
    at > 0 &&
    (DURATION_BEFORE.has(list[at - 1]) || list[at - 1] === 'POUR' || list[at - 1] === 'POR')
  ) {
    return false;
  }

  const end = RANGE_JOINS.has(list[at + 1] ?? '') ? at + 2 : at + 1;
  if (end >= list.length) return false;
  const whole = NUMBER_THEN_REST.exec(list[end]);
  if (!whole) {
    const word = numberOf(list[end]);
    return word !== null && word > first && endsARange(list, end + 1, word);
  }
  const second = Number(whole[1]);
  const glued = HOUR_WORDS.has(whole[2]) ? second !== 24 : whole[2] === 'X' || isPer(list, end + 1);
  const counted = whole[2] ? glued : endsARange(list, end + 1, second);
  return second > first && counted;
}

function ceilingBeforeNumber(list: readonly string[], at: number): boolean {
  if (!LIMIT_BEFORE_NUMBER.has(list[at])) return false;
  const next = RANGE_JOINS.has(list[at + 1] ?? '') ? at + 2 : at + 1;
  return next < list.length && numberOf(list[next]) !== null;
}

function isChoiceAt(list: readonly string[], at: number): boolean {
  if (at === 0 || at + 1 >= list.length || !CHOICE_WORDS.has(list[at])) return false;
  return (
    adverbCount(list[at - 1]) !== null ||
    numberOf(list[at - 1]) === null ||
    numberOf(list[at + 1]) === null
  );
}

/** `FrequencyCadence.statesNoFixedSchedule`: a ceiling, a range, a choice. */
function statesNoFixedSchedule(frequency: string): boolean {
  const text = normalize(frequency);
  const list = wordsOf(frequency);
  const two = EVERY_TWO_NUMBERS.exec(text);
  return (
    RANGE_PER_24.test(text) ||
    EVERY_HOURS_AND_PART.test(text) ||
    (two !== null && two[1] !== two[2]) ||
    containsAny(list, LIMIT_WORDS) ||
    containsPhrase(list, LIMIT_PHRASES) ||
    (containsAny(list, STEP_WORDS) && !containsAny(list, LOADING_WORDS)) ||
    mixesCadences(list) ||
    list.some(
      (_, i) =>
        ceilingBeforeNumber(list, i) ||
        isChoiceAt(list, i) ||
        rangeStartsAt(list, i) ||
        oneOfSeveral(list, i) ||
        timesThenAmountOverAPeriod(list, i) ||
        overAPeriod(list, i) ||
        reasonNotOrdered(list, i),
    )
  );
}

function longPeriod(word: string): boolean {
  const period = periodOf(word);
  return period !== null && period !== MINUTES_PER_DAY;
}

function everyMealNamed(t: readonly string[]): boolean {
  return t.some((word, i) => i > 0 && A_MEAL_WORDS.has(word) && ALL_MEALS_BEFORE.has(t[i - 1]));
}

function isTomorrow(list: readonly string[]): boolean {
  return list.some(
    (word, i) =>
      word === 'MANANA' &&
      !(i > 0 && BEFORE_MORNING.has(list[i - 1])) &&
      !(i + 1 < list.length && AFTER_MORNING.has(list[i + 1])),
  );
}

function mixesCadences(list: readonly string[]): boolean {
  const bedtime = containsAny(list, BEDTIME_WORDS);
  const besideMeals = bedtime && (containsAny(list, MEALS_WORDS) || everyMealNamed(list));
  const besideEvening = bedtime && containsAny(list, EVENING_ONLY_WORDS) && partsOfDay(list) > 1;
  const aCycle = containsAny(list, IN_A_ROW_WORDS) && list.some(longPeriod);
  const twiceOnOtherDays = list.includes('SUR') && partsOfDay(list) > 1;
  const spansWeekdays = list.some(
    (word, i) =>
      i + 2 < list.length &&
      WEEKDAY_WORDS.has(word) &&
      RANGE_TO_WORDS.has(list[i + 1]) &&
      WEEKDAY_WORDS.has(list[i + 2]),
  );
  return (
    besideMeals ||
    besideEvening ||
    aCycle ||
    twiceOnOtherDays ||
    spansWeekdays ||
    (containsAny(list, WEEKDAY_WORDS) && partsOfDay(list) > 0) ||
    isTomorrow(list)
  );
}

function oneOfSeveral(w: readonly string[], at: number): boolean {
  const word = w[at];
  const next = w[at + 1] ?? '';
  if (word === 'OTHER') return periodOf(next) === null;
  if (word === 'SUR' && at > 0 && numberOf(next) !== null) {
    const ofADay = w[at - 1] === 'JOUR' || w[at - 1] === 'DAY';
    const unit = w[at + 2] ?? '';
    return !ofADay && !HOUR_WORDS.has(unit) && !MINUTE_UNITS.has(unit);
  }
  const number = numberOf(word);
  return number !== null && number > 1 && isTimeOfDay(next);
}

function timesThenAmountOverAPeriod(w: readonly string[], at: number): boolean {
  if (at === 0 || at + 1 >= w.length || !TIMES_WORDS.has(w[at])) return false;
  const count = numberOf(w[at - 1]);
  const amount = numberOf(w[at + 1]);
  const part = w[at + 1] === DOSE_PART;
  if (count === null || count < 2 || (amount === null && !part)) return false;
  const rest = w.slice(at + 2);
  const aDayToo =
    !part &&
    amount !== 1 &&
    rest.length > 0 &&
    !MEASURE_UNITS.has(rest[0]) &&
    rest.some((word) => DAY_WORDS.has(word));
  return aDayToo || rest.some(longPeriod);
}

function overAPeriod(w: readonly string[], at: number): boolean {
  return (
    w[at] === 'EN' &&
    at + 2 < w.length &&
    numberOf(w[at + 1]) !== null &&
    periodOf(w[at + 2]) !== null
  );
}

function reasonNotOrdered(w: readonly string[], at: number): boolean {
  if (!REASON_OPENERS.has(w[at])) return false;
  let next = at + 1;
  while (next < w.length && ARTICLES.has(w[next])) next++;
  if (next >= w.length) return false;
  return countOf(w[next]) === null && periodOf(w[next]) === null && !ORDERED_REASONS.has(w[next]);
}

/* ── FrequencyCadence: the steps, in its order. ────────────────────────── */

interface Text {
  f: string;
  t: readonly string[];
}

function every(minutes: number): FrequencyReading | null {
  return minutes > 0 ? { kind: 'REPEATING', intervalMinutes: minutes } : null;
}

function countOver(count: number, period: number): FrequencyReading | null {
  const minutes = count > 0 ? Math.floor(period / count) : 0;
  return minutes >= MINUTES_PER_HOUR ? { kind: 'REPEATING', intervalMinutes: minutes } : null;
}

function everyHoursOf(hours: number): FrequencyReading | null {
  return validHours(hours) === null ? null : every(hours * MINUTES_PER_HOUR);
}

function everyMinutes({ f }: Text): FrequencyReading | null {
  const m = EVERY_MINUTES.exec(f);
  return m && Number(m[1]) <= MINUTES_PER_DAY ? every(Number(m[1])) : null;
}

function everyHours({ f }: Text): FrequencyReading | null {
  const perDay = COUNT_PER_24.exec(f);
  if (perDay && Number(perDay[1]) < 24) return countOver(Number(perDay[1]), MINUTES_PER_DAY);
  for (const pattern of [TT_LES_HOURS, PAR_HOURS, BARE_Q, DOSE_PER_HOURS]) {
    const m = pattern.exec(f);
    if (m) return everyHoursOf(Number(m[1]));
  }
  for (const slash of f.matchAll(BARE_SLASH)) {
    const before = LAST_WORD.exec(f.slice(0, slash.index))?.[1] ?? '';
    if (slash[1] === slash[2] && BARE_SLASH_HOURS.has(slash[1]) && !DATE_BEFORE.has(before)) {
      return everyHoursOf(Number(slash[1]));
    }
  }
  return null;
}

function longQ({ f }: Text): FrequencyReading | null {
  const m = Q_LONG.exec(f);
  if (!m) return null;
  const period = m[2] === 'W' ? MINUTES_PER_WEEK : periodOf(m[2]);
  return period === null ? null : every(Number(m[1]) * period);
}

function posology({ f }: Text): FrequencyReading | null {
  const m = POSOLOGY_WORD.exec(f);
  if (!m) return null;
  return countOver([...m[0]].filter((c) => c >= '1' && c <= '9').length, MINUTES_PER_DAY);
}

function periodOf(word: string): number | null {
  if (DAY_WORDS.has(word)) return MINUTES_PER_DAY;
  if (WEEK_WORDS.has(word)) return MINUTES_PER_WEEK;
  if (MONTH_WORDS.has(word)) return MINUTES_PER_MONTH;
  if (QUARTER_WORDS.has(word)) return MINUTES_PER_QUARTER;
  return YEAR_WORDS.has(word) ? MINUTES_PER_YEAR : null;
}

function statesHowLongAfter(t: readonly string[], at: number): boolean {
  return at < t.length && (periodOf(t[at]) !== null || HOUR_WORDS.has(t[at]));
}

function shorthand(left: number | null, right: number, after: string): FrequencyReading | null {
  if (left === null || left === 1) {
    return right > 1 && right <= 4 && after === '' ? countOver(right, MINUTES_PER_DAY) : null;
  }
  return left <= 6 && (right === 1 || MEASURE_UNITS.has(after))
    ? countOver(left, MINUTES_PER_DAY)
    : null;
}

function timesShorthand({ t }: Text): FrequencyReading | null {
  if (namesAnotherCadence(t)) return null;
  for (let i = 0; i < t.length; i++) {
    const glued = GLUED_TIMES.exec(t[i]);
    if (glued && i + 1 === t.length) return shorthand(Number(glued[1]), Number(glued[2]), '');
    if (t[i] === 'X' && i + 1 < t.length && !statesHowLongAfter(t, i + 2)) {
      const right = t[i + 1] === DOSE_PART ? 1 : numberOf(t[i + 1]);
      const notDaily = i > 0 && NOT_DAILY_BEFORE_X.has(t[i - 1]);
      if (right !== null && !notDaily) {
        return shorthand(i > 0 ? numberOf(t[i - 1]) : null, right, t[i + 2] ?? '');
      }
    }
  }
  return null;
}

function timesThenDose({ t }: Text): FrequencyReading | null {
  for (let i = 1; i + 1 < t.length; i++) {
    const count = numberOf(t[i - 1]);
    if (
      count !== null &&
      count > 1 &&
      TIMES_WORDS.has(t[i]) &&
      t[i] !== 'X' &&
      ONLY_DIGITS.test(t[i + 1]) &&
      !statesHowLongAfter(t, i + 2)
    ) {
      return countOver(count, MINUTES_PER_DAY);
    }
  }
  return null;
}

function adverbCount(word: string): number | null {
  if (word === 'ONCE') return 1;
  if (word === 'TWICE') return 2;
  return word === 'THRICE' ? 3 : null;
}

function countOf(word: string): number | null {
  if (word === DOSE_PART) return 1;
  const adverb = adverbCount(word);
  if (adverb !== null) return adverb;
  const glued = GLUED_COUNT.exec(word);
  return glued ? Number(glued[1]) : numberOf(word);
}

function countBefore(t: readonly string[], from: number, period: number): FrequencyReading | null {
  for (let i = from; i >= 0 && i >= from - 2; i--) {
    const count = countOf(t[i]);
    if (count !== null) {
      return MEASURE_UNITS.has(t[from]) ? every(period) : countOver(count, period);
    }
    if (!ONLY_LETTERS.test(t[i]) || DURATION_BEFORE.has(t[i]) || t[i] === 'POUR') return null;
  }
  return null;
}

function perPeriod({ t }: Text): FrequencyReading | null {
  for (let i = 2; i < t.length; i++) {
    const period = periodOf(t[i]);
    const join = i > 2 && PERIOD_ARTICLES.has(t[i - 1]) ? i - 2 : i - 1;
    const aDayNumber =
      i + 1 < t.length &&
      ONLY_DIGITS.test(t[i + 1]) &&
      !(i + 2 < t.length && periodOf(t[i + 2]) !== null);
    if (period !== null && !aDayNumber && PERIOD_JOINS.has(t[join])) {
      const reading = countBefore(t, join - 1, period);
      if (reading) return reading;
    }
  }
  return null;
}

function isEvery(t: readonly string[], at: number): boolean {
  return EVERY_WORDS.has(t[at]) || (at > 0 && ALL_ARTICLES.has(t[at]) && ALL_WORDS.has(t[at - 1]));
}

function everyPeriodAt(t: readonly string[], at: number, period: number): FrequencyReading | null {
  let before = at - 1;
  let times = 1;
  const count = numberOf(t[before]);
  if (count !== null) {
    times = count;
    before--;
  } else if (t[before] === 'OTHER') {
    times = 2;
    before--;
  }
  return before >= 0 && isEvery(t, before) ? every(period * times) : null;
}

function everyPeriod({ t }: Text): FrequencyReading | null {
  for (let i = 1; i < t.length; i++) {
    const period = periodOf(t[i]);
    const reading = period === null ? null : everyPeriodAt(t, i, period);
    if (reading) return reading;
  }
  return null;
}

function offsets(values: readonly number[], unit: number): number[] | null {
  if (values.length < 2 || values.length > MAX_COURSE_ENTRIES) return null;
  const rising = values.every((v, i) => i === 0 || v > values[i - 1]);
  return rising ? values.map((v) => (v - values[0]) * unit) : null;
}

function rangeEnd(t: readonly string[], at: number, value: number, letter: string): number {
  if (at + 2 >= t.length || !RANGE_TO_WORDS.has(t[at + 1])) return value;
  const end = COURSE_ENTRY.exec(t[at + 2]);
  return end && end[1] === letter ? Number(end[2]) - 1 : value;
}

function courseEntries(t: readonly string[]): number[] | null {
  const values: number[] = [];
  let letter: string | null = null;
  for (let i = 0; i < t.length; i++) {
    const m = COURSE_ENTRY.exec(t[i]);
    if (!m) continue;
    if (letter !== null && letter !== m[1]) return null;
    letter = m[1];
    const to = rangeEnd(t, i, Number(m[2]), letter);
    if (to !== Number(m[2]) && letter === 'H') return null;
    for (let v = Number(m[2]); v <= to && values.length <= MAX_COURSE_ENTRIES; v++) values.push(v);
  }
  return offsets(values, letter === 'H' ? MINUTES_PER_HOUR : MINUTES_PER_DAY);
}

function hoursList(t: readonly string[]): number[] | null {
  const unit = t.findIndex((word) => HOUR_WORDS.has(word));
  if (unit < 0) return null;
  const values: number[] = [];
  for (let i = unit - 1; i >= 0; i--) {
    if (ONLY_DIGITS.test(t[i])) values.unshift(Number(t[i]));
    else if (!LIST_JOINS.has(t[i])) break;
  }
  return values.length > 2 && values[0] === 0 ? offsets(values, MINUTES_PER_HOUR) : null;
}

function daysInARow(t: readonly string[]): number[] | null {
  if (!t.some((word) => IN_A_ROW_WORDS.has(word))) return null;
  for (let i = 0; i + 1 < t.length; i++) {
    const days = numberOf(t[i]);
    if (days !== null && DAY_WORDS.has(t[i + 1])) {
      return offsets(
        Array.from({ length: days }, (_, d) => d),
        MINUTES_PER_DAY,
      );
    }
  }
  return null;
}

function course({ t }: Text): FrequencyReading | null {
  const entries = courseEntries(t) ?? hoursList(t) ?? daysInARow(t);
  return entries ? { kind: 'COURSE', courseOffsets: entries } : null;
}

function hourOf(number: number, mark: string): number {
  if (mark === 'PM' && number < 12) return number + 12;
  return mark === 'AM' && number === 12 ? 0 : number;
}

function validHour(hour: number): number | null {
  return hour <= 24 ? hour : null;
}

function clockHour(t: readonly string[], at: number): number | null {
  const before = at > 0 ? t[at - 1] : '';
  if (DURATION_BEFORE.has(before)) return null;
  const glued = GLUED_CLOCK.exec(t[at]);
  if (glued) return validHour(hourOf(Number(glued[1]), glued[2]));
  if (!ONLY_DIGITS.test(t[at])) return null;
  const after = at + 1 < t.length ? t[at + 1] : '';
  const number = Number(t[at]);
  if (after === 'AM' || after === 'PM') return validHour(hourOf(number, after));
  const hourAfter =
    SINGLE_HOUR_UNIT.has(after) || (HOUR_WORDS.has(after) && BEFORE_CLOCK.has(before));
  return hourAfter || BEFORE_BARE_CLOCK.has(before) ? validHour(number) : null;
}

function hasClockMark(t: readonly string[], at: number): boolean {
  const glued = GLUED_CLOCK.exec(t[at]);
  const withMinutesOrAm = !!glued && (glued[3] !== undefined || glued[2] !== 'H');
  const after = at + 1 < t.length ? t[at + 1] : '';
  return (
    withMinutesOrAm || after === 'AM' || after === 'PM' || (at > 0 && BEFORE_CLOCK.has(t[at - 1]))
  );
}

function saysOneDose(t: readonly string[]): boolean {
  return t.some(
    (word, i) => i > 0 && numberOf(t[i - 1]) === 1 && (word === 'DOSE' || word === 'DOSIS'),
  );
}

function clockTimes({ t }: Text): FrequencyReading | null {
  if (
    t.includes('ONCE') ||
    saysOneDose(t) ||
    t.includes('DE') ||
    t.includes('FROM') ||
    t.includes('DESDE')
  ) {
    return null;
  }
  const hours: number[] = [];
  let marked = false;
  t.forEach((_, i) => {
    const hour = clockHour(t, i);
    if (hour !== null) {
      hours.push(hour);
      marked = marked || hasClockMark(t, i);
    }
  });
  if (hours.length === 0 || (hours.length === 1 && !marked)) return null;
  if (hours.length > 1 && offsets(hours, 1) === null) return null;
  if (hours.some((hour, i) => i > 0 && hour - hours[i - 1] < MIN_CLOCK_GAP_HOURS)) return null;

  return countOver(hours.length, MINUTES_PER_DAY);
}

function oneDayIn(t: readonly string[], day: number): FrequencyReading | null {
  if (day === 0 || day + 2 >= t.length || t[day + 1] !== 'SUR') return null;
  const one = numberOf(t[day - 1]);
  const days = numberOf(t[day + 2]);
  return one === 1 && days !== null ? every(days * MINUTES_PER_DAY) : null;
}

function alternateDays({ t }: Text): FrequencyReading | null {
  for (let i = 0; i < t.length; i++) {
    if (DAY_WORDS.has(t[i])) {
      if (i + 1 < t.length && ALTERNATE_WORDS.has(t[i + 1])) return every(2 * MINUTES_PER_DAY);
      const reading = oneDayIn(t, i);
      if (reading) return reading;
    }
  }
  return null;
}

function namedMinutes(word: string): number | null {
  if (ONCE_A_DAY_WORDS.has(word)) return MINUTES_PER_DAY;
  if (TWICE_A_DAY_WORDS.has(word)) return MINUTES_PER_DAY / 2;
  if (THREE_A_DAY_WORDS.has(word)) return MINUTES_PER_DAY / 3;
  if (FOUR_A_DAY_WORDS.has(word)) return MINUTES_PER_DAY / 4;
  if (HOURLY_WORDS.has(word)) return MINUTES_PER_HOUR;
  if (OTHER_DAY_WORDS.has(word)) return 2 * MINUTES_PER_DAY;
  if (WEEKLY_WORDS.has(word)) return MINUTES_PER_WEEK;
  if (MONTHLY_WORDS.has(word)) return MINUTES_PER_MONTH;
  return YEARLY_WORDS.has(word) ? MINUTES_PER_YEAR : null;
}

function namedAt(t: readonly string[], at: number, minutes: number): FrequencyReading | null {
  const before = at > 0 ? t[at - 1] : '';
  const twoBefore = at > 1 ? numberOf(t[at - 2]) : null;
  const countedLonger =
    minutes > MINUTES_PER_DAY &&
    ONLY_LETTERS.test(before) &&
    !TIMES_WORDS.has(before) &&
    twoBefore !== null &&
    twoBefore > 1;
  const hoursNotBedtime = t[at] === 'HS' && numberOf(before) !== null;
  if (COUNT_PREFIXES.has(before) || countedLonger || hoursNotBedtime) return null;
  let count = adverbCount(before);
  if (count === null && TIMES_WORDS.has(before) && at > 1) count = twoBefore;
  if (count === null && GLUED_COUNT.test(before) && !ONLY_DIGITS.test(before))
    count = countOf(before);
  return count === null ? every(minutes) : countOver(count, minutes);
}

function named({ t }: Text): FrequencyReading | null {
  for (let i = 0; i < t.length; i++) {
    const minutes = namedMinutes(t[i]);
    const rightEye = t[i] === 'OD' && containsAny(t, DROP_WORDS);
    if (minutes !== null && !rightEye) return namedAt(t, i, minutes);
  }
  return null;
}

function weekdays({ t }: Text): FrequencyReading | null {
  const days = new Set(t.filter((word) => WEEKDAY_WORDS.has(word)));
  return days.size === 0 ? null : countOver(days.size, MINUTES_PER_WEEK);
}

function perLongerPeriodAfter(t: readonly string[], at: number): boolean {
  if (at + 1 >= t.length || !PERIOD_JOINS.has(t[at])) return false;
  const period = periodOf(t[at + 1]);
  return period !== null && period !== MINUTES_PER_DAY;
}

function intakes({ t }: Text): FrequencyReading | null {
  for (let i = 1; i < t.length; i++) {
    const splitIn = SPLIT_WORDS.has(t[i]) && i > 1 && (t[i - 2] === 'EN' || t[i - 2] === 'IN');
    const count = numberOf(t[i - 1]);
    if (
      count !== null &&
      count > 1 &&
      (INTAKE_WORDS.has(t[i]) || splitIn) &&
      !perLongerPeriodAfter(t, i + 1)
    ) {
      return countOver(count, MINUTES_PER_DAY);
    }
  }
  return null;
}

function isTimeOfDay(word: string): boolean {
  return MORNING_WORDS.has(word) || NOON_WORDS.has(word) || EVENING_WORDS.has(word);
}

function oneTime({ t }: Text): FrequencyReading | null {
  const found = t.some(
    (word, i) =>
      ONE_TIME_WORDS.has(word) ||
      (i + 1 < t.length && THIS_WORDS.has(word) && isTimeOfDay(t[i + 1])),
  );
  return found ? ONE_OFF : null;
}

function containsAny(t: readonly string[], list: ReadonlySet<string>): boolean {
  return t.some((word) => list.has(word));
}

function partsOfDay(t: readonly string[]): number {
  return [MORNING_WORDS, NOON_WORDS, EVENING_WORDS].filter((l) => containsAny(t, l)).length;
}

function timeOfDay({ t }: Text): FrequencyReading | null {
  const parts = partsOfDay(t);
  return parts === 0 ? null : countOver(parts, MINUTES_PER_DAY);
}

function namesAnotherCadence(t: readonly string[]): boolean {
  return (
    partsOfDay(t) > 0 ||
    containsAny(t, MEALS_WORDS) ||
    containsAny(t, A_MEAL_WORDS) ||
    containsAny(t, NAMED_MEAL_WORDS) ||
    containsAny(t, WEEKDAY_WORDS) ||
    t.some((word) => namedMinutes(word) !== null)
  );
}

function meals({ t }: Text): FrequencyReading | null {
  if (containsAny(t, AWAY_FROM_MEALS)) return null;
  const named = t.filter((word) => NAMED_MEAL_WORDS.has(word)).length;
  if (named > 0) return countOver(named, MINUTES_PER_DAY);
  const counted = t.findIndex(
    (word, i) => i > 0 && A_MEAL_WORDS.has(word) && numberOf(t[i - 1]) !== null,
  );
  if (counted > 0) return countOver(numberOf(t[counted - 1]) ?? 0, MINUTES_PER_DAY);

  if (containsAny(t, FASTING_WORDS)) return every(MINUTES_PER_DAY);
  const lettered = t.length === 2 && t[1] === 'C' && (t[0] === 'A' || t[0] === 'P');
  if (lettered || containsAny(t, MEALS_WORDS) || everyMealNamed(t))
    return countOver(3, MINUTES_PER_DAY);
  return containsAny(t, A_MEAL_WORDS) ? every(MINUTES_PER_DAY) : null;
}

function continuousInfusionNamed(t: readonly string[]): boolean {
  if (!containsAny(t, CONTINUOUS_WORDS)) return false;
  const alone = t.length === 1 && t[0] !== 'CONTINUE';
  const enContinu = t.length === 2 && t[0] === 'EN';
  return alone || enContinu || containsAny(t, INFUSION_WORDS);
}

function continuous({ f, t }: Text): FrequencyReading | null {
  const electric = t.includes('SERINGUE') && t.includes('ELECTRIQUE');
  const running = containsAny(t, RUNNING_WORDS) || RATE.test(f) || ALL_DAY.test(f);
  return electric || running || continuousInfusionNamed(t) ? CONTINUOUS : null;
}

function namedSingleDose({ t }: Text): FrequencyReading | null {
  if (containsAny(t, PREVENTIVE_DOSE_WORDS)) return ONE_OFF;
  const single = t.some(
    (word, i) =>
      i + 1 < t.length &&
      ((DOSE_WORDS.has(word) && SINGLE_WORDS.has(t[i + 1])) ||
        (SINGLE_WORDS.has(word) && DOSE_WORDS.has(t[i + 1]))),
  );
  return single ? ONE_OFF : null;
}

function singleDose({ t }: Text): FrequencyReading | null {
  const single = t.some((word, i) => DOSE_WORDS.has(word) && i > 0 && numberOf(t[i - 1]) === 1);
  if (single) return ONE_OFF;
  const oneX = t.length === 2 && t[0] === '1' && t[1] === 'X';
  return t.includes('ONCE') || t.includes('1X') || oneX ? ONE_OFF : null;
}

function onceEvent({ t }: Text): FrequencyReading | null {
  const firstHour =
    (t.includes('PREMIERE') && t.includes('HEURE')) ||
    (t.includes('PRIMERA') && t.includes('HORA')) ||
    (t.includes('FIRST') && t.includes('HOUR'));
  const event = firstHour || containsAny(t, ONCE_EVENT_WORDS);
  return event && !containsAny(t, EVERY_WORDS) && !containsAny(t, UNTIL_WORDS) ? ONE_OFF : null;
}

function bolus({ t }: Text): FrequencyReading | null {
  const slowIv =
    t.includes('IV') && (t.includes('LENTE') || t.includes('SLOW') || t.includes('LENTA'));
  const loading =
    (t.includes('CHARGE') || t.includes('CARGA') || t.includes('LOADING')) &&
    (t.includes('DOSE') || t.includes('DOSIS'));
  return slowIv || loading || containsAny(t, BOLUS_WORDS) ? ONE_OFF : null;
}

const STEPS: readonly ((text: Text) => FrequencyReading | null)[] = [
  intakes,
  everyMinutes,
  longQ,
  everyHours,
  posology,
  timesShorthand,
  timesThenDose,
  perPeriod,
  everyPeriod,
  course,
  oneTime,
  namedSingleDose,
  clockTimes,
  alternateDays,
  named,
  weekdays,
  timeOfDay,
  meals,
  continuous,
  singleDose,
  onceEvent,
  bolus,
];

function goesOn(t: readonly string[]): boolean {
  return (containsAny(t, GOES_ON_WORDS) && !t.includes('NAISSANCE')) || containsAny(t, STEP_WORDS);
}

function statesADuration(t: readonly string[]): boolean {
  return t.some((word, i) => {
    const number = i + 1 < t.length ? numberOf(word) : null;
    if (number === null) return false;
    return periodOf(t[i + 1]) !== null || (HOUR_WORDS.has(t[i + 1]) && number >= 12);
  });
}

/** `FrequencyCadence.saysMoreThan`: the rest of the sentence has its say. */
function saysMoreThan(reading: FrequencyReading, t: readonly string[]): boolean {
  if (reading.kind === 'COURSE') return namesAnotherCadence(t);
  if (reading.kind === 'REPEATING') return t.some((word) => DAY_ENTRY.test(word));
  return (
    reading.kind === 'ONE_OFF' &&
    (goesOn(t) || statesADuration(t) || partsOfDay(t) > 1 || isRepeated(t) || namesMoreDoses(t))
  );
}

function isRepeated(t: readonly string[]): boolean {
  return (
    containsAny(t, EVERY_WORDS) ||
    containsAny(t, ALL_WORDS) ||
    t.includes('AUX') ||
    containsAny(t, UNTIL_WORDS) ||
    (t.includes('POST') && t.includes('OPERATOIRE'))
  );
}

function namesMoreDoses(t: readonly string[]): boolean {
  const joined = t.includes('ET') || t.includes('AND') || t.includes('Y');
  const moments = t.filter(
    (word) =>
      ONE_TIME_WORDS.has(word) ||
      ONCE_EVENT_WORDS.has(word) ||
      THIS_WORDS.has(word) ||
      word === 'AUJOURD' ||
      word === 'TODAY' ||
      word === 'HOY',
  ).length;
  const doses = t.filter((word) => word === 'DOSE' || word === 'DOSIS').length;
  const aFirst =
    doses > 0 && (t.includes('PREMIERE') || t.includes('PRIMERA') || t.includes('FIRST'));
  if ((joined && moments > 1) || doses > 1 || aFirst) return true;
  return t.some((word, i) => {
    if (i + 1 >= t.length) return false;
    if ((word === 'DANS' || word === 'IN' || word === 'X') && numberOf(t[i + 1]) !== null)
      return true;
    const number = numberOf(word);
    return (
      number !== null &&
      number > 1 &&
      (TIMES_WORDS.has(t[i + 1]) || t[i + 1] === 'DOSES' || t[i + 1] === 'DOSIS')
    );
  });
}

/** `FrequencyCadence.withoutDoseParts`: the digits of a decimal or a fraction are part of a dose. */
function withoutDoseParts(upper: string): string {
  const part = ` ${DOSE_PART} `;
  return upper
    .replaceAll(MIXED_DOSE, part)
    .replaceAll(FRACTION_DOSE, part)
    .replaceAll(FRACTION_SIGN, part)
    .replaceAll(DECIMAL_DOSE, part);
}

function readCadence(f: string): FrequencyReading | null {
  const perDay = f.replaceAll(PER_24_HOURS, '/J');
  const spaced = perDay
    .replaceAll(DASHED_COURSE, '$1 A $2')
    .replaceAll(/\b(\d{1,2}):(\d{2})\b/g, '$1H$2')
    .replaceAll('/', ' / ')
    .replaceAll(/[^A-Z0-9/]+/g, ' ')
    .trim();
  const text: Text = { f: perDay, t: spaced ? spaced.split(' ') : [] };
  for (const step of STEPS) {
    const reading = step(text);
    if (reading) return saysMoreThan(reading, text.t) ? null : reading;
  }
  return null;
}

/** `MarDoseSchedule.read(String)`: the one reader of a frequency. */
export function frequencyReading(frequency: string | null | undefined): FrequencyReading {
  if (!frequency?.trim()) return ONE_OFF;
  const upper = normalize(frequency);
  const written = withoutDoseParts(accentlessUpper(frequency));
  if (statesNoFixedSchedule(written)) return UNREAD;
  if (/\bSTAT\b/.test(upper)) return ONE_OFF;
  if (readsRound31Phrase(frequency)) return UNREAD;

  const minutes = intervalMinutesOf(written);
  if (minutes !== null) return { kind: 'REPEATING', intervalMinutes: minutes };
  if (ONE_OFF_WORDS.has(upper.replaceAll(' ', ''))) return ONE_OFF;
  return readCadence(normalize(written)) ?? UNREAD;
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

function withoutNames(all: readonly string[]): string[] {
  const prevention = all.findIndex((word) => PREVENTION_WORDS.has(word));
  const list = prevention < 0 ? all : all.slice(0, prevention);
  let spaced = ` ${list.join(' ')} `;
  for (const name of NOT_A_CONDITION) spaced = spaced.replaceAll(` ${name} `, ' ');
  const kept = spaced.trim();
  return kept ? kept.split(' ') : [];
}

function wordsOf(text: string): string[] {
  const normal = normalize(text)
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
  const text = accentlessUpper(frequency)
    .replaceAll(/\([^()]*\)/g, (part) => (isWeightBand(part) ? ' ' : part))
    .replaceAll(/[_.,\s]+/g, ' ')
    .trim();
  if (readsConditional(text, wordsOf(text))) return 'CONDITIONAL';
  return frequencyReading(frequency).kind === 'UNREAD' ? 'UNREADABLE' : 'FIXED';
}
