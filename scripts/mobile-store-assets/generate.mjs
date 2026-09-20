#!/usr/bin/env node
/**
 * e-Keneya store assets for both patient apps, rendered from ONE definition
 * of the brand mark (the woven cross in hospital-portal/src/app/shared/
 * brand-mark) so the icon on a phone matches the mark on the web.
 *
 * Run from hospital-portal/ so its Playwright is on the module path:
 *   cd hospital-portal && node ../scripts/mobile-store-assets/generate.mjs
 *
 * Writes:
 *   patient-ios-app/MediHubPatient/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png
 *   patient-ios-app/appstore-assets/screenshots/{6.5-inch,6.7-inch,6.9-inch,ipad-13}/
 *   patient-android-app/store-assets/{app-icon,developer-icon}-512x512.png
 *   patient-android-app/store-assets/feature-graphic-1024x500.png
 *   patient-android-app/store-assets/{phone,tablet-7inch,tablet-10inch}/
 *   patient-android-app/app/src/main/res/mipmap-*\/ic_launcher*.png
 *   patient-android-app/app/src/main/res/drawable/ic_launcher_{background,foreground}.xml
 *
 * Playwright's bundled Chromium revision may not be on disk; point
 * PW_CHROMIUM at any installed chrome.exe to skip the download.
 */
import { createRequire } from 'node:module';
// Resolved from the CWD, not from this file: Playwright lives in
// hospital-portal/node_modules and this script sits outside it.
const { chromium } = createRequire(process.cwd() + '/')('playwright');
import { mkdirSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const IOS = join(ROOT, 'patient-ios-app');
const AND = join(ROOT, 'patient-android-app');

// ── Brand ──────────────────────────────────────────────────────────────
const INK = '#0A1614'; // the mark's ground
const TEAL = '#23B79C'; // warp on dark grounds
const TEAL_DEEP = '#0E7C6B'; // --primary on the web, app bars here
const TEAL_DARK = '#0A5F52';
const TINT = '#CCEBE4';
const OCHRE = '#E39A2B'; // weft
const SURFACE = '#F3F7F6';

/** The mark, exactly as brand-mark.component.ts draws it (64-unit box). */
function markSvg(size, warp = TEAL, weft = OCHRE) {
  return `<svg width="${size}" height="${size}" viewBox="0 0 64 64" aria-hidden="true">
    <rect x="4" y="24" width="56" height="7" rx="1.5" fill="${weft}"/>
    <rect x="4" y="33" width="56" height="7" rx="1.5" fill="${weft}"/>
    <rect x="24" y="4" width="7" height="56" rx="1.5" fill="${warp}"/>
    <rect x="33" y="4" width="7" height="56" rx="1.5" fill="${warp}"/>
    <rect x="24" y="33" width="7" height="7" fill="${weft}"/>
    <rect x="33" y="24" width="7" height="7" fill="${weft}"/>
  </svg>`;
}

const FONT = `<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">`;

const BASE_CSS = `
  * { box-sizing: border-box; margin: 0; }
  html, body { width: 100%; height: 100%; }
  body { font-family: Inter, 'Segoe UI', system-ui, sans-serif; background: ${INK}; color: #fff;
         -webkit-font-smoothing: antialiased; overflow: hidden; }
`;

// ── Icon ───────────────────────────────────────────────────────────────
/**
 * Square, full-bleed, no alpha: Apple and Play both apply their own mask.
 * `shape` adds a transparent mask for the legacy Android launcher PNGs.
 * `markRatio` is the mark's width over the canvas; the adaptive foreground
 * must keep the mark inside the 66/108 safe circle, hence 70/108 there.
 */
function iconHtml(size, { shape = 'square', markRatio = 0.62, ground = true } = {}) {
  const radius = shape === 'rounded' ? `${Math.round(size * 0.2)}px` : shape === 'circle' ? '50%' : '0';
  const inset = shape === 'square' ? 0 : Math.round(size * 0.05);
  const box = size - 2 * inset;
  const mark = Math.round(size * markRatio);
  const groundCss = ground ? `radial-gradient(circle at 50% 42%, #14302A 0%, ${INK} 70%)` : 'transparent';
  return `<!doctype html><html><head>${FONT}<style>${BASE_CSS}
    body { background: transparent; }
    .tile { position: absolute; left: ${inset}px; top: ${inset}px; width: ${box}px; height: ${box}px;
            border-radius: ${radius}; overflow: hidden; background: ${groundCss};
            display: grid; place-items: center; }
  </style></head><body><div class="tile">${markSvg(mark)}</div></body></html>`;
}

// ── Feature graphic / header ───────────────────────────────────────────
function bannerHtml(w, h) {
  const markSize = Math.round(h * 0.56);
  const title = Math.round(h * 0.19);
  const sub = Math.round(h * 0.075);
  const stripe = Math.round(h * 0.03);
  const gap = Math.round(h * 0.09);
  return `<!doctype html><html><head>${FONT}<style>${BASE_CSS}
    body { background: radial-gradient(ellipse at 22% 50%, #14302A 0%, ${INK} 55%); }
    .row { position: absolute; inset: 0; display: flex; align-items: center; gap: ${Math.round(h * 0.12)}px;
           padding-left: ${Math.round(w * 0.09)}px; }
    h1 { font-size: ${title}px; font-weight: 800; letter-spacing: -0.02em; line-height: 1; }
    h1 span { color: ${TEAL}; }
    p { margin-top: ${Math.round(h * 0.04)}px; font-size: ${sub}px; font-weight: 500; color: ${TINT}; }
    .weave { position: absolute; right: 0; top: 0; bottom: 0; width: ${Math.round(w * 0.22)}px;
             background: repeating-linear-gradient(90deg, ${OCHRE}22 0 ${stripe}px, transparent ${stripe}px ${gap}px); }
  </style></head><body>
    <div class="weave"></div>
    <div class="row">${markSvg(markSize)}<div><h1>e-<span>Keneya</span></h1><p>Votre santé, entre vos mains</p></div></div>
  </body></html>`;
}

// ── Screenshots ────────────────────────────────────────────────────────
// Copy comes from the apps' own French string tables where a string exists.
const SCREENS = [
  { file: '01_login', headline: 'Bienvenue sur e-Keneya', sub: 'Votre santé, entre vos mains', body: loginScreen },
  { file: '02_dashboard', headline: 'Votre santé en un coup d’œil', sub: 'Tableau de bord et accès rapide', body: dashboardScreen },
  { file: '03_appointments', headline: 'Vos rendez-vous', sub: 'Prenez et suivez vos rendez-vous', body: appointmentsScreen },
  { file: '04_lab_results', headline: 'Résultats de laboratoire', sub: 'Disponibles dès leur validation', body: labScreen },
  { file: '05_medications', headline: 'Vos médicaments', sub: 'Ordonnances et posologie à jour', body: medsScreen },
  { file: '06_messages', headline: 'Messagerie sécurisée', sub: 'Échangez avec votre équipe de soins', body: messagesScreen },
  { file: '07_vitals', headline: 'Signes vitaux', sub: 'Suivez votre tension au fil du temps', body: vitalsScreen },
  { file: '08_billing', headline: 'Facturation', sub: 'Vos factures et vos paiements', body: billingScreen },
];

const APP_CSS = `
  .app { background: ${SURFACE}; color: #10201C; height: 100%; display: flex; flex-direction: column; font-size: 15px; }
  .bar { background: ${TEAL_DEEP}; color: #fff; padding: 52px 20px 18px; }
  .bar h2 { font-size: 22px; font-weight: 700; }
  .bar small { display: block; opacity: .85; font-size: 13px; margin-top: 2px; }
  /* min(300px, 100%): a track can never be wider than the device, so a
     narrow Play phone wraps instead of clipping. align-items: start keeps
     a button or tile row from stretching to its neighbour's height when
     a tablet is wide enough for two columns. */
  .content { padding: 16px 16px 0; display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr)); align-content: start; align-items: start; }
  .content > .btn, .content > .tiles { grid-column: 1 / -1; }
  .card { background: #fff; border-radius: 14px; padding: 14px 16px; box-shadow: 0 1px 2px #0A161414; }
  .card h3 { font-size: 13px; font-weight: 600; color: #4B5F5A; text-transform: uppercase; letter-spacing: .04em; margin-bottom: 10px; }
  .row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 10px 0; border-top: 1px solid #E6EEEC; }
  .row:first-of-type { border-top: 0; }
  .row b { display: block; font-weight: 600; }
  .row span { font-size: 13px; color: #5C6F6A; }
  .chip { font-size: 12px; font-weight: 600; padding: 4px 10px; border-radius: 999px; background: ${TINT}; color: ${TEAL_DARK}; white-space: nowrap; }
  .chip.warn { background: #FBEBD0; color: #8A5A0E; }
  .chip.paid { background: #E4F4EE; color: #1E6B4E; }
  .tiles { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
  .tile { background: #fff; border-radius: 14px; padding: 12px; box-shadow: 0 1px 2px #0A161414; }
  .tile small { font-size: 12px; color: #5C6F6A; }
  .tile strong { display: block; font-size: 22px; color: ${TEAL_DEEP}; margin-top: 2px; }
  .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px 6px; text-align: center; font-size: 11.5px; color: #34484A; }
  .grid i { display: block; width: 44px; height: 44px; margin: 0 auto 6px; border-radius: 12px; background: ${TINT}; }
  .grid i.o { background: #FBEBD0; }
  .tabs { display: flex; gap: 18px; padding: 0 16px; margin-top: 4px; font-weight: 600; color: #5C6F6A; }
  .tabs .on { color: ${TEAL_DEEP}; border-bottom: 3px solid ${TEAL_DEEP}; padding-bottom: 6px; }
  .nav { margin-top: auto; background: #fff; border-top: 1px solid #E6EEEC; display: flex; justify-content: space-around; padding: 10px 0 24px; font-size: 11px; color: #5C6F6A; }
  .nav div { text-align: center; } .nav i { display: block; width: 22px; height: 22px; margin: 0 auto 3px; border-radius: 6px; background: #C9D6D2; }
  .nav .on { color: ${TEAL_DEEP}; } .nav .on i { background: ${TEAL_DEEP}; }
  .btn { display: block; text-align: center; background: ${TEAL_DEEP}; color: #fff; font-weight: 600; padding: 14px; border-radius: 12px; }
  .btn.ghost { background: transparent; color: ${TEAL_DEEP}; border: 1.5px solid ${TEAL_DEEP}; }
  .field { border: 1.5px solid #C9D6D2; border-radius: 12px; padding: 14px; color: #5C6F6A; background: #fff; }
  .login { padding: 56px 24px 24px; display: grid; gap: 14px; align-content: start; }
  .login h1 { font-size: 26px; font-weight: 800; margin-top: 18px; }
  .login p { color: #5C6F6A; margin-bottom: 8px; }
  .link { text-align: center; margin-top: 6px; color: ${TEAL_DEEP}; font-weight: 600; }
  .msg { padding: 10px 0; border-top: 1px solid #E6EEEC; display: flex; gap: 12px; align-items: center; }
  .msg:first-child { border-top: 0; }
  .av { width: 40px; height: 40px; border-radius: 50%; background: ${TINT}; color: ${TEAL_DARK}; display: grid; place-items: center; font-weight: 700; flex: none; }
  .msg b { display: block; } .msg span { font-size: 13px; color: #5C6F6A; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: ${OCHRE}; margin-left: auto; flex: none; }
`;

const NAV_LABELS = ['Tableau de bord', 'Rendez-vous', 'Messages', 'Profil'];
const nav = (on) => `<div class="nav">${NAV_LABELS.map((l) => `<div class="${l === on ? 'on' : ''}"><i></i>${l}</div>`).join('')}</div>`;

// The two login forms differ: Android's username field also takes an
// e-mail and its biometric button is generic; iOS names Face ID. Each
// listing shows the controls its own build has (App Review 2.3.3).
const LOGIN = {
  ios: { user: 'Nom d’utilisateur', bio: 'Connexion avec Face ID' },
  android: { user: 'Nom d’utilisateur ou e-mail', bio: 'Connexion biométrique' },
};
function loginScreen(platform) {
  const s = LOGIN[platform];
  return `<div class="app"><div class="login">${markSvg(72, TEAL_DEEP, OCHRE)}
    <h1>Bienvenue sur e-Keneya</h1><p>Votre santé, entre vos mains</p>
    <div class="field">${s.user}</div><div class="field">Mot de passe</div>
    <div class="btn">Se connecter</div><div class="btn ghost">${s.bio}</div>
    <p class="link">Mot de passe oublié ?</p></div></div>`;
}
function dashboardScreen() {
  return `<div class="app"><div class="bar"><h2>Bienvenue, Aminata !</h2><small>Dossier n° P-2026-04817</small></div>
    <div class="content">
      <div class="tiles"><div class="tile"><small>Tension</small><strong>12/8</strong></div>
        <div class="tile"><small>Pouls</small><strong>72</strong></div><div class="tile"><small>IMC</small><strong>22,4</strong></div></div>
      <div class="card"><h3>Accès rapide</h3><div class="grid">
        <div><i></i>Rendez-vous</div><div><i></i>Résultats</div><div><i class="o"></i>Médicaments</div><div><i></i>Facturation</div>
        <div><i></i>Signes vitaux</div><div><i class="o"></i>Équipe de soins</div><div><i></i>Documents</div><div><i></i>Messages</div></div></div>
      <div class="card"><h3>Prochains rendez-vous</h3>
        <div class="row"><div><b>Dr Sawadogo · Cardiologie</b><span>Mar. 14 oct. 2026 · 10:00</span></div><span class="chip">Confirmé</span></div></div>
    </div>${nav('Tableau de bord')}</div>`;
}
function appointmentsScreen() {
  return `<div class="app"><div class="bar"><h2>Rendez-vous</h2></div>
    <div class="tabs"><span class="on">À venir</span><span>Passés</span></div>
    <div class="content">
      <div class="card">
        <div class="row"><div><b>Dr Sawadogo · Cardiologie</b><span>Mar. 14 oct. 2026 · 10:00 · Centre médical de Bogodogo</span></div><span class="chip">Confirmé</span></div>
        <div class="row"><div><b>Dr Kaboré · Médecine générale</b><span>Jeu. 23 oct. 2026 · 08:30 · Centre médical de Bogodogo</span></div><span class="chip warn">En attente</span></div>
        <div class="row"><div><b>Laboratoire · Prélèvement</b><span>Lun. 3 nov. 2026 · 07:15</span></div><span class="chip">Confirmé</span></div></div>
      <div class="btn">Prendre un rendez-vous</div>
    </div>${nav('Rendez-vous')}</div>`;
}
function labScreen() {
  return `<div class="app"><div class="bar"><h2>Résultats de laboratoire</h2><small>Bilan du 2 sept. 2026</small></div>
    <div class="content"><div class="card"><h3>Résultats récents</h3>
      <div class="row"><div><b>Hémoglobine</b><span>13,2 g/dL · 12,0 – 16,0</span></div><span class="chip">Normal</span></div>
      <div class="row"><div><b>Glycémie à jeun</b><span>0,95 g/L · 0,70 – 1,10</span></div><span class="chip">Normal</span></div>
      <div class="row"><div><b>Créatinine</b><span>9 mg/L · 6 – 12</span></div><span class="chip">Normal</span></div>
      <div class="row"><div><b>Cholestérol total</b><span>2,25 g/L · &lt; 2,00</span></div><span class="chip warn">À surveiller</span></div></div>
      <div class="card"><h3>Bilans précédents</h3>
      <div class="row"><div><b>Bilan du 12 mars 2026</b><span>6 analyses</span></div><span>›</span></div></div>
    </div>${nav('')}</div>`;
}
function medsScreen() {
  return `<div class="app"><div class="bar"><h2>Médicaments</h2></div>
    <div class="tabs"><span class="on">En cours</span><span>Ordonnances</span></div>
    <div class="content"><div class="card">
      <div class="row"><div><b>Amlodipine 5 mg</b><span>1 comprimé le matin</span></div><span class="chip">Actif</span></div>
      <div class="row"><div><b>Metformine 500 mg</b><span>1 comprimé matin et soir</span></div><span class="chip">Actif</span></div>
      <div class="row"><div><b>Paracétamol 500 mg</b><span>Si besoin, 6 h d’intervalle</span></div><span class="chip">Actif</span></div></div>
      <div class="card"><h3>Allergies</h3><div class="row"><div><b>Pénicilline</b><span>Réaction cutanée</span></div><span class="chip warn">Sévère</span></div></div>
    </div>${nav('')}</div>`;
}
function messagesScreen() {
  return `<div class="app"><div class="bar"><h2>Messages</h2></div>
    <div class="content"><div class="card">
      <div class="msg"><div class="av">DS</div><div><b>Dr Sawadogo</b><span>Vos résultats sont rassurants. À mardi.</span></div><div class="dot"></div></div>
      <div class="msg"><div class="av">SC</div><div><b>Secrétariat · Cardiologie</b><span>Rendez-vous confirmé pour le 14 octobre.</span></div></div>
      <div class="msg"><div class="av">PH</div><div><b>Pharmacie</b><span>Votre ordonnance est prête.</span></div></div>
      <div class="msg"><div class="av">LB</div><div><b>Laboratoire</b><span>Bilan du 2 septembre disponible.</span></div></div></div>
      <div class="btn">Nouveau message</div>
    </div>${nav('Messages')}</div>`;
}
function vitalsScreen() {
  const pts = [[0, 52], [40, 48], [80, 55], [120, 46], [160, 44], [200, 47], [240, 41], [280, 43]];
  const line = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x + 10},${y}`).join(' ');
  const dots = pts.map(([x, y]) => `<circle cx="${x + 10}" cy="${y}" r="4" fill="${TEAL}"/>`).join('');
  return `<div class="app"><div class="bar"><h2>Signes vitaux</h2><small>Derniers 30 jours</small></div>
    <div class="content"><div class="card"><h3>Tension artérielle</h3>
      <svg viewBox="0 0 300 80" width="100%" height="110"><path d="${line}" fill="none" stroke="${TEAL_DEEP}" stroke-width="3" stroke-linecap="round"/>
      ${dots}<line x1="10" y1="70" x2="290" y2="70" stroke="#E6EEEC"/></svg>
      <div class="row"><div><b>12/8</b><span>Aujourd’hui · 07:40</span></div><span class="chip">Normale</span></div></div>
      <div class="tiles"><div class="tile"><small>Pouls</small><strong>72</strong></div><div class="tile"><small>Poids</small><strong>64 kg</strong></div><div class="tile"><small>Temp.</small><strong>36,8°</strong></div></div>
      <div class="btn ghost">Ajouter une mesure</div>
    </div>${nav('')}</div>`;
}
function billingScreen() {
  return `<div class="app"><div class="bar"><h2>Facturation</h2><small>Solde : 0 FCFA</small></div>
    <div class="content"><div class="card"><h3>Factures</h3>
      <div class="row"><div><b>Consultation · Cardiologie</b><span>2 sept. 2026 · 7 500 FCFA</span></div><span class="chip paid">Payée</span></div>
      <div class="row"><div><b>Analyses de laboratoire</b><span>2 sept. 2026 · 12 000 FCFA</span></div><span class="chip paid">Payée</span></div>
      <div class="row"><div><b>Pharmacie</b><span>3 sept. 2026 · 4 250 FCFA</span></div><span class="chip paid">Payée</span></div></div>
      <div class="card"><h3>Moyens de paiement</h3><div class="row"><div><b>Mobile money</b><span>•••• 43 18</span></div><span>›</span></div></div>
    </div>${nav('')}</div>`;
}

/** Marketing frame around the app mock. Portrait stacks; landscape splits. */
function shotHtml(screen, vw, vh, platform) {
  const landscape = vw > vh;
  const hl = Math.round(Math.min(vw, vh) * (landscape ? 0.075 : 0.085));
  // Portrait: the device is sized from the height left under the copy block
  // (top padding, eyebrow, a two-line headline, the subline), so its bottom
  // edge — tab bar, sign-in buttons — is always inside the frame. A tablet
  // gets a 3:4 frame, a phone 9:17.5. The mock then zooms to the frame's
  // width rather than overflowing it: on Play's 360-pt phone that is about
  // half size, and still 25 px type in the 1080-px file.
  const gap = Math.round(vh * 0.05);
  const copyBudget = Math.round(vh * 0.09 + hl * 4.3) + gap;
  const tablet = vw >= 700;
  const ratio = tablet ? 3 / 4 : 9 / 17.5;
  const devW = Math.min(Math.round(vw * 0.78), Math.round((vh - copyBudget) * ratio));
  // Landscape: the tallest mock (dashboard) is about 820 CSS px, so the
  // mock zooms to the frame's height and the tab bar stays in shot.
  const zoom = landscape ? Math.min(1, (vh * 0.92) / 820) : Math.min(1, devW / 380);
  const deviceCss = landscape
    ? 'width: 46%; height: 92%; margin-left: 4%; align-self: center;' // no top margin: a % margin resolves against WIDTH and pushed the tab bar out of the 600px frame
    : `width: ${devW}px; aspect-ratio: ${tablet ? '3 / 4' : '9 / 17.5'}; margin-top: ${gap}px; align-self: center;`;
  return `<!doctype html><html><head>${FONT}<style>${BASE_CSS}${APP_CSS}
    body { background: radial-gradient(ellipse at 50% 0%, #14302A 0%, ${INK} 60%); }
    .page { position: absolute; inset: 0; display: flex; flex-direction: ${landscape ? 'row' : 'column'}; align-items: center; }
    .copy { padding: ${landscape ? '0 0 0 7%' : '9% 8% 0'}; text-align: ${landscape ? 'left' : 'center'}; flex: ${landscape ? '0 0 44%' : 'none'}; }
    .eyebrow { display: inline-flex; align-items: center; gap: 10px; font-weight: 600; color: ${TINT}; font-size: ${Math.round(hl * 0.45)}px; margin-bottom: ${Math.round(hl * 0.7)}px; }
    h1 { font-size: ${hl}px; font-weight: 800; letter-spacing: -0.02em; line-height: 1.08; text-wrap: balance; }
    .sub { margin-top: ${Math.round(hl * 0.35)}px; font-size: ${Math.round(hl * 0.5)}px; color: ${TINT}; font-weight: 500; }
    .device { ${deviceCss} background: #fff; border-radius: 40px; border: 10px solid #1B2B27; overflow: hidden; box-shadow: 0 30px 80px #00000080; }
    .device .app { zoom: ${zoom}; }
  </style></head><body><div class="page">
    <div class="copy"><div class="eyebrow">${markSvg(Math.round(hl * 0.7))} e-Keneya</div><h1>${screen.headline}</h1><div class="sub">${screen.sub}</div></div>
    <div class="device">${screen.body(platform)}</div>
  </div></body></html>`;
}

// ── Targets ────────────────────────────────────────────────────────────
// Store pixel sizes expressed as CSS viewport × device scale, which keeps
// type and spacing the size the store's own device would show.
const SHOT_TARGETS = [
  { dir: join(IOS, 'appstore-assets/screenshots/6.9-inch'), vw: 440, vh: 956, dpr: 3 }, // 1320×2868
  { dir: join(IOS, 'appstore-assets/screenshots/6.7-inch'), vw: 428, vh: 926, dpr: 3 }, // 1284×2778
  { dir: join(IOS, 'appstore-assets/screenshots/6.5-inch'), vw: 414, vh: 896, dpr: 3 }, // 1242×2688
  { dir: join(IOS, 'appstore-assets/screenshots/ipad-13'), vw: 1024, vh: 1366, dpr: 2 }, // 2048×2732
  { dir: join(AND, 'store-assets/phone'), vw: 360, vh: 640, dpr: 3, dash: true }, // 1080×1920
  { dir: join(AND, 'store-assets/tablet-7inch'), vw: 600, vh: 960, dpr: 2, dash: true }, // 1200×1920
  { dir: join(AND, 'store-assets/tablet-10inch'), vw: 960, vh: 600, dpr: 2, dash: true }, // 1920×1200
];

const MIPMAP = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

async function main() {
  const launch = process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {};
  const browser = await chromium.launch(launch);
  const shoot = async (html, w, h, dpr, path, omitBackground = false) => {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: dpr });
    const page = await ctx.newPage();
    await page.setContent(html, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    // Inter comes from Google Fonts at run time. Without this check a
    // failed fetch would regenerate every asset in Segoe UI and exit 0.
    const inter = await page.evaluate(() => document.fonts.load('700 16px Inter').then((faces) => faces.length > 0));
    if (!inter) throw new Error(`Inter did not load for ${path}; check network access to fonts.googleapis.com`);
    mkdirSync(dirname(path), { recursive: true });
    await page.screenshot({ path, omitBackground, type: 'png' });
    await ctx.close();
    console.log('wrote', path.slice(ROOT.length + 1));
  };

  // Store icons: square, opaque.
  await shoot(iconHtml(1024), 1024, 1024, 1, join(IOS, 'MediHubPatient/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png'));
  await shoot(iconHtml(512), 512, 512, 1, join(AND, 'store-assets/app-icon-512x512.png'));
  await shoot(iconHtml(512), 512, 512, 1, join(AND, 'store-assets/developer-icon-512x512.png'));

  // Android launcher rasters for API 23–25 (adaptive icons take over at 26).
  for (const [density, scale] of Object.entries(MIPMAP)) {
    const dir = join(AND, `app/src/main/res/mipmap-${density}`);
    const px = Math.round(48 * scale);
    await shoot(iconHtml(px, { shape: 'rounded' }), px, px, 1, join(dir, 'ic_launcher.png'), true);
    await shoot(iconHtml(px, { shape: 'circle' }), px, px, 1, join(dir, 'ic_launcher_round.png'), true);
    const fg = Math.round(108 * scale);
    await shoot(iconHtml(fg, { markRatio: 70 / 108, ground: false }), fg, fg, 1, join(dir, 'ic_launcher_foreground.png'), true);
  }
  writeAdaptiveVectors();

  await shoot(bannerHtml(1024, 500), 1024, 500, 1, join(AND, 'store-assets/feature-graphic-1024x500.png'));
  await shoot(bannerHtml(2048, 1152), 2048, 1152, 2, join(AND, 'store-assets/header-image-4096x2304.png'));

  for (const t of SHOT_TARGETS) {
    if (existsSync(t.dir)) rmSync(t.dir, { recursive: true });
    for (const s of SCREENS) {
      const name = t.dash ? s.file.replace(/_/g, '-') : s.file;
      await shoot(shotHtml(s, t.vw, t.vh, t.dash ? 'android' : 'ios'), t.vw, t.vh, t.dpr, join(t.dir, `${name}.png`));
    }
  }
  await browser.close();
}

/**
 * The adaptive foreground, as a vector so it stays crisp on every density.
 * The mark's 64-unit box maps onto 70dp centred in the 108dp canvas, which
 * keeps its arm tips inside the 66dp safe circle launchers may clip to.
 */
function writeAdaptiveVectors() {
  const S = 70 / 64;
  const O = (108 - 70) / 2;
  const r = (x, y, w, h, fill) => {
    const X = (O + x * S).toFixed(3);
    const Y = (O + y * S).toFixed(3);
    const W = (w * S).toFixed(3);
    const H = (h * S).toFixed(3);
    return `    <path android:fillColor="${fill}" android:pathData="M${X},${Y}h${W}v${H}h-${W}z" />`;
  };
  const fg = `<?xml version="1.0" encoding="utf-8"?>
<!-- The e-Keneya mark: the woven cross from hospital-portal's brand-mark
     component, scaled so its 64-unit box fills 70dp of the 108dp canvas and
     stays inside the 66dp safe zone. Regenerated by
     scripts/mobile-store-assets/generate.mjs; edit that, not this. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
${r(4, 24, 56, 7, OCHRE)}
${r(4, 33, 56, 7, OCHRE)}
${r(24, 4, 7, 56, TEAL)}
${r(33, 4, 7, 56, TEAL)}
${r(24, 33, 7, 7, OCHRE)}
${r(33, 24, 7, 7, OCHRE)}
</vector>
`;
  const bg = `<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/brand_ink" />
</shape>
`;
  const res = join(AND, 'app/src/main/res/drawable');
  writeFileSync(join(res, 'ic_launcher_foreground.xml'), fg);
  writeFileSync(join(res, 'ic_launcher_background.xml'), bg);
  console.log('wrote adaptive icon vectors');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
