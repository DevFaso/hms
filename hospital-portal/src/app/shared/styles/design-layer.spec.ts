/**
 * QA r65 design audit (D2790 / D2791 / D2793 / D2797 / D2798 / D2804): the
 * global design layer, measured as the browser computes it. This reads the
 * GLOBAL stylesheet the app loads (angular.json test styles), like
 * overflow-class.spec.ts; scripts/check-design-tokens.mjs reads the sources
 * (no component restates a shared button or a pre-rebrand colour), and
 * e2e/layout-overflow.spec.ts measures the real pages.
 *
 * The responsive rules depend on the width of the SCREEN, which a Karma page
 * cannot change: they are measured inside an iframe of that width holding a
 * copy of the page's stylesheets (testing/screen-frame.ts).
 */
import { closeScreens, openScreen } from '../../testing/screen-frame';

function luminance(rgb: string): number {
  const [r, g, b] = (rgb.match(/[\d.]+/g) ?? []).slice(0, 3).map((v) => {
    const c = Number(v) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** The computed colour of a CSS custom property. */
function tokenColour(host: HTMLElement, name: string): string {
  const probe = document.createElement('span');
  probe.style.color = `var(${name})`;
  host.appendChild(probe);
  const colour = getComputedStyle(probe).color;
  probe.remove();
  return colour;
}

const WHITE = 'rgb(255, 255, 255)';
const BRAND = 'rgb(14, 124, 107)';

describe('global design layer (QA r65)', () => {
  let box: HTMLDivElement;

  beforeEach(() => {
    box = document.createElement('div');
    document.body.appendChild(box);
  });
  afterEach(() => box.remove());

  describe('contrast tokens (D2793)', () => {
    it('keeps secondary and muted text at 4.5:1 or more on white and on the page background', () => {
      const page = tokenColour(box, '--surface-page');
      expect(page).toBe('rgb(241, 245, 249)');
      for (const token of ['--text-primary', '--text-secondary', '--text-muted']) {
        const colour = tokenColour(box, token);
        expect(contrast(colour, WHITE))
          .withContext(`${token} on white`)
          .toBeGreaterThanOrEqual(4.5);
        expect(contrast(colour, page))
          .withContext(`${token} on the page`)
          .toBeGreaterThanOrEqual(4.5);
      }
    });

    it('keeps white text readable on the brand and the danger fills', () => {
      for (const token of ['--primary', '--primary-dark', '--danger', '--danger-dark']) {
        expect(contrast(WHITE, tokenColour(box, token)))
          .withContext(`white on ${token}`)
          .toBeGreaterThanOrEqual(4.5);
      }
      expect(contrast(tokenColour(box, '--accent-violet'), WHITE)).toBeGreaterThanOrEqual(4.5);
    });

    it('prints an empty state and a page subtitle in the secondary token', () => {
      box.innerHTML = `<div class="content"><p class="page-subtitle">x</p></div>
        <div class="empty-state"><p>Aucun résultat</p></div>`;
      const secondary = tokenColour(box, '--text-secondary');
      expect(getComputedStyle(box.querySelector('.page-subtitle')!).color).toBe(secondary);
      expect(getComputedStyle(box.querySelector('.empty-state p')!).color).toBe(secondary);
    });
  });

  describe('one button system (D2790 / D2804)', () => {
    it('gives the three shared buttons one shape and the brand colours', () => {
      box.innerHTML = `<button class="btn-primary">Créer</button>
        <button class="btn-secondary">Annuler</button>
        <button class="btn-danger">Supprimer</button>`;
      const [primary, secondary, danger] = Array.from(box.querySelectorAll('button')).map((b) =>
        getComputedStyle(b),
      );
      for (const style of [primary, secondary, danger]) {
        expect(style.minHeight).toBe('40px');
        expect(style.borderTopLeftRadius).toBe('8px');
        expect(style.fontSize).toBe('14px');
        expect(style.fontWeight).toBe('600');
        expect(style.display).toBe('inline-flex');
      }
      expect(primary.backgroundColor).toBe(BRAND);
      expect(primary.color).toBe(WHITE);
      expect(secondary.backgroundColor).toBe(WHITE);
      expect(secondary.borderTopWidth).toBe('1px');
      expect(secondary.borderTopColor).not.toBe(WHITE);
      expect(danger.backgroundColor).toBe('rgb(185, 28, 28)');
      expect(danger.color).toBe(WHITE);
    });

    it('has one small size, and a disabled look', () => {
      box.innerHTML = `<button class="btn-primary btn-sm">x</button>
        <button class="btn-primary" disabled>x</button>`;
      const [small, disabled] = Array.from(box.querySelectorAll('button')).map((b) =>
        getComputedStyle(b),
      );
      expect(small.minHeight).toBe('32px');
      expect(Number(disabled.opacity)).toBeLessThan(1);
      expect(disabled.cursor).toBe('not-allowed');
    });

    it('a primary button in its destructive state is the danger button, border included', () => {
      // /roi binds `.danger` on its confirm button for « Refuser »; /panels and
      // /webhooks write it. A red fill with the primary's teal border is two buttons at once.
      box.innerHTML = `<button class="btn-primary danger">Refuser</button>
        <button class="btn-danger">Supprimer</button>`;
      const [stated, danger] = Array.from(box.querySelectorAll('button')).map((b) =>
        getComputedStyle(b),
      );
      expect(stated.backgroundColor).toBe('rgb(185, 28, 28)');
      expect(stated.borderTopColor).toBe('rgb(185, 28, 28)');
      expect(stated.borderTopColor).toBe(danger.borderTopColor);
      expect(stated.backgroundColor).toBe(danger.backgroundColor);
      expect(stated.color).toBe(WHITE);
    });

    it('styles a button built at run time, outside any component (the Escape question)', () => {
      box.innerHTML = `<div class="dialog-discard"><button class="btn-secondary">Rester</button>
        <button class="btn-danger">Abandonner</button></div>`;
      expect(getComputedStyle(box.querySelector('.btn-danger')!).backgroundColor).toBe(
        'rgb(185, 28, 28)',
      );
    });
  });

  describe('one page title, one table header (D2791 / D2797)', () => {
    it('fixes the h1 of a page at one size and weight, whatever the page wrote', () => {
      box.innerHTML = `<main class="content">
        <h1 style="font-size: 32px; font-weight: 800">Consultations</h1>
        <h1 class="page-title" style="font-size: 22px; font-weight: 600">Utilisateurs</h1></main>`;
      for (const h1 of Array.from(box.querySelectorAll('h1'))) {
        const style = getComputedStyle(h1);
        // 24 px on a desktop, 20 px on a phone-wide test window: one size either way.
        expect(style.fontSize).toBe(window.innerWidth < 600 ? '20px' : '24px');
        expect(style.fontWeight).toBe('700');
      }
    });

    it('writes every column header in sentence case at one size', () => {
      box.innerHTML = `<table><thead><tr>
          <th style="text-transform: uppercase; font-size: 10px; letter-spacing: 1px">Patient</th>
          <th style="font-size: 14.4px; font-weight: 500">Statut</th>
        </tr></thead><tbody><tr><td>x</td><td>y</td></tr></tbody></table>`;
      for (const th of Array.from(box.querySelectorAll('th'))) {
        const style = getComputedStyle(th);
        expect(style.textTransform).toBe('none');
        expect(style.fontSize).toBe('12px');
        expect(style.fontWeight).toBe('600');
        expect(style.letterSpacing).toBe('normal');
      }
    });
  });

  describe('responsive tables (D2798)', () => {
    const screen = openScreen;

    afterEach(closeScreens);

    const TABLE = `<div style="overflow-x:auto;width:100%"><table data-cards style="min-width:900px;width:100%">
        <thead><tr><th>Numéro</th><th>Patient</th><th>Total</th><th>Statut</th></tr></thead>
        <tbody>
          <tr><td data-label="Numéro">CHWF2396-2026-000001</td><td data-label="Patient">Évelyne Démo-Ouédraogo</td>
              <td data-label="Total" style="white-space:nowrap">13 550 F CFA</td><td data-label="Statut">Payée</td></tr>
          <tr><td colspan="4">Aucune autre facture</td></tr>
        </tbody></table></div>`;

    it('sticks the last column of ANY table below the desktop width, not only a column of buttons', async () => {
      const doc = await screen(800);
      doc.body.innerHTML = TABLE;
      const view = doc.defaultView!;
      const last = doc.querySelector<HTMLElement>('tbody tr:first-child td:last-child')!;
      const head = doc.querySelector<HTMLElement>('thead th:last-child')!;
      const spanning = doc.querySelector<HTMLElement>('td[colspan]')!;
      expect(view.getComputedStyle(last).position).toBe('sticky');
      expect(view.getComputedStyle(last).right).toBe('0px');
      expect(view.getComputedStyle(head).position).toBe('sticky');
      // The sticky cell is opaque: it takes its row's background.
      expect(view.getComputedStyle(last).backgroundColor).toBe(WHITE);
      // A full-width row is not a column.
      expect(view.getComputedStyle(spanning).position).toBe('static');
      // And the last column really is on screen while the table scrolls under it.
      const scroller = doc.body.firstElementChild as HTMLElement;
      expect(scroller.scrollWidth).toBeGreaterThan(scroller.clientWidth);
      expect(last.getBoundingClientRect().right).toBeLessThanOrEqual(801);
    });

    it('leaves a desktop table alone', async () => {
      const doc = await screen(1200);
      doc.body.innerHTML = TABLE;
      const last = doc.querySelector<HTMLElement>('tbody tr:first-child td:last-child')!;
      expect(doc.defaultView!.getComputedStyle(last).position).toBe('static');
      expect(doc.defaultView!.getComputedStyle(last).display).toBe('table-cell');
    });

    it('shows a marked table as cards under 600 px: one card per row, a label per cell, no sideways scroll', async () => {
      const doc = await screen(390);
      doc.body.innerHTML = TABLE;
      const view = doc.defaultView!;
      const table = doc.querySelector('table')!;
      const row = doc.querySelector<HTMLElement>('tbody tr')!;
      const cell = row.querySelector<HTMLElement>('td:last-child')!;
      expect(view.getComputedStyle(table).display).toBe('block');
      expect(view.getComputedStyle(row).display).toBe('table');
      expect(view.getComputedStyle(cell).display).toBe('table-row');
      expect(view.getComputedStyle(cell).position).toBe('static');
      expect(view.getComputedStyle(cell, '::before').content).toBe('"Statut"');
      // The header row is gone (its sort buttons would stay in the Tab order,
      // invisible): each cell carries its header as its label.
      const head = doc.querySelector<HTMLElement>('thead')!;
      expect(view.getComputedStyle(head).display).toBe('none');
      for (const labelled of Array.from(row.querySelectorAll<HTMLElement>('td'))) {
        expect(view.getComputedStyle(labelled, '::before').content).toBe(
          `"${labelled.dataset['label']}"`,
        );
      }
      // Nothing scrolls sideways, the long invoice number included.
      const scroller = doc.body.firstElementChild as HTMLElement;
      expect(scroller.scrollWidth).toBeLessThanOrEqual(scroller.clientWidth + 1);
      expect(row.getBoundingClientRect().right).toBeLessThanOrEqual(391);
      // A full-width row is a plain block with no label.
      const spanning = doc.querySelector<HTMLElement>('td[colspan]')!;
      expect(view.getComputedStyle(spanning).display).toBe('block');
    });

    it('leaves an unmarked table (a matrix, a calendar) as a table on a phone', async () => {
      const doc = await screen(390);
      doc.body.innerHTML = TABLE.replace(' data-cards', '');
      const row = doc.querySelector<HTMLElement>('tbody tr')!;
      expect(doc.defaultView!.getComputedStyle(row).display).toBe('table-row');
    });

    it('keeps hidden what a page hid with the hidden attribute: a row, a cell, the table', async () => {
      const doc = await screen(390);
      doc.body.innerHTML = `<table data-cards>
          <thead><tr><th>Examen</th><th>Résultat</th></tr></thead>
          <tbody>
            <tr><td data-label="Examen">NFS</td><td data-label="Résultat" hidden>12</td></tr>
            <tr hidden><td data-label="Examen">Détail replié</td><td data-label="Résultat">x</td></tr>
          </tbody></table>
        <table data-cards hidden>
          <thead><tr><th>A</th><th>B</th></tr></thead>
          <tbody><tr><td data-label="A">1</td><td data-label="B">2</td></tr></tbody></table>`;
      const view = doc.defaultView!;
      const [list, kept] = Array.from(doc.querySelectorAll('table'));
      expect(view.getComputedStyle(list).display).toBe('block');
      expect(view.getComputedStyle(list.querySelector('td[hidden]')!).display).toBe('none');
      expect(view.getComputedStyle(list.querySelector('tr[hidden]')!).display).toBe('none');
      expect(view.getComputedStyle(kept).display).toBe('none');
    });

    it('keeps the tint a page gives a row or a cell: an abnormal result is still red on a phone', async () => {
      const doc = await screen(390);
      // What a page's own (scoped) sheet writes: a tinted row, a tinted cell.
      const page = doc.createElement('style');
      page.textContent = `
        tr.overdue[data-page] { background: #fef2f2; }
        tr.abnormal[data-page] td[data-page] { background: #fff7ed; }`;
      doc.head.appendChild(page);
      doc.body.innerHTML = `<table data-cards>
          <thead><tr><th>Examen</th><th>Résultat</th></tr></thead>
          <tbody>
            <tr><td data-label="Examen">NFS</td><td data-label="Résultat">12</td></tr>
            <tr class="overdue" data-page><td data-label="Examen">Glycémie</td><td data-label="Résultat">—</td></tr>
            <tr class="abnormal" data-page><td data-page data-label="Examen">Kaliémie</td><td data-page data-label="Résultat">6,8</td></tr>
          </tbody></table>`;
      const view = doc.defaultView!;
      const [plain, overdue, abnormal] = Array.from(doc.querySelectorAll('tbody tr'));
      // It IS a card (so the tint is the page's, not a table that never switched).
      expect(view.getComputedStyle(overdue).display).toBe('table');
      expect(view.getComputedStyle(plain).backgroundColor).toBe(WHITE);
      expect(view.getComputedStyle(overdue).backgroundColor).toBe('rgb(254, 242, 242)');
      const cell = abnormal.querySelector('td:last-child')!;
      expect(view.getComputedStyle(cell).backgroundColor).toBe('rgb(255, 247, 237)');
      // The hairline between two lines of a card is still drawn, over the tint.
      expect(view.getComputedStyle(cell).backgroundImage).toContain('linear-gradient');
    });
  });

  describe('print (D2805)', () => {
    /** The declarations of the global sheet's `@media print` rules that hide `selector`. */
    function hiddenOnPaper(selector: string): boolean {
      return Array.from(document.styleSheets).some((sheet) => {
        let rules: CSSRule[];
        try {
          rules = Array.from(sheet.cssRules);
        } catch {
          return false;
        }
        return rules.some(
          (rule) =>
            rule instanceof CSSMediaRule &&
            rule.conditionText.trim() === 'print' &&
            Array.from(rule.cssRules).some(
              (inner) =>
                inner instanceof CSSStyleRule &&
                inner.style.display === 'none' &&
                inner.selectorText.split(',').some((s) => s.trim() === selector),
            ),
        );
      });
    }

    it('takes the filter controls off paper but leaves the chips that name the active filters', () => {
      for (const chrome of ['.filter-row', '.filter-toolbar', '.search-bar', '.pagination-row']) {
        expect(hiddenOnPaper(chrome)).withContext(chrome).toBeTrue();
      }
      // A printed list that is a subset says so.
      expect(hiddenOnPaper('.active-chips')).toBeFalse();
    });

    it('keeps the break-glass notice on a printed record, less its buttons', () => {
      // A record printed under emergency access said so before r65: whether it
      // may stop saying so is not a design decision.
      expect(hiddenOnPaper('app-break-glass-banner')).toBeFalse();
      expect(hiddenOnPaper('app-break-glass-banner button')).toBeTrue();
    });
  });
});
