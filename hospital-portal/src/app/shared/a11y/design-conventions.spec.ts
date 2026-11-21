import {
  applyDesignConventions,
  hideIcon,
  installDesignConventions,
  isCardTable,
  labelTable,
  titleIconButton,
  uninstallDesignConventions,
} from './design-conventions';

/**
 * QA r65 design audit: D2798 (a table reads as cards on a phone), D2792 (icon
 * ligature names read aloud), D2807 (icon-only buttons without a tooltip).
 * The conventions are applied to the rendered page, so the specs build pages.
 */
describe('design conventions (QA D2798 / D2792 / D2807)', () => {
  let page: HTMLElement;

  const frame = () => new Promise((resolve) => requestAnimationFrame(() => resolve(undefined)));
  const settle = async () => {
    await frame();
    await frame();
  };

  beforeEach(() => {
    page = document.createElement('div');
    document.body.appendChild(page);
  });

  afterEach(() => {
    uninstallDesignConventions();
    page.remove();
  });

  describe('card labels (D2798)', () => {
    it('copies each column header onto the cells under it and marks the table', () => {
      page.innerHTML = `<table><thead><tr>
          <th>Médicament</th>
          <th class="sortable-th">État <span class="material-symbols-outlined">unfold_more</span></th>
          <th><span class="sr-only">Actions</span></th>
        </tr></thead>
        <tbody><tr><td>Amoxicilline</td><td>Actif</td><td><button>Modifier</button></td></tr></tbody></table>`;
      const table = page.querySelector('table')!;
      expect(labelTable(table)).toBeTrue();
      expect(table.hasAttribute('data-cards')).toBeTrue();
      const cells = table.querySelectorAll('tbody td');
      expect(cells[0].getAttribute('data-label')).toBe('Médicament');
      // The sort icon's ligature name is not part of the label.
      expect(cells[1].getAttribute('data-label')).toBe('État');
      expect(cells[2].getAttribute('data-label')).toBe('Actions');
    });

    it('names a column by its text, not by the command its header carries', () => {
      page.innerHTML = `<table><thead><tr>
          <th aria-label="Trier par nom du patient"><button>Nom du patient</button></th>
          <th aria-label="Actions"></th>
          <th data-label="Solde" aria-label="Trier par solde">Reste à payer</th>
        </tr></thead>
        <tbody><tr><td>Awa</td><td><button>Ouvrir</button></td><td>0</td></tr></tbody></table>`;
      labelTable(page.querySelector('table')!);
      const cells = page.querySelectorAll('tbody td');
      expect(cells[0].getAttribute('data-label')).toBe('Nom du patient');
      // A header with no text at all falls back to its accessible name…
      expect(cells[1].getAttribute('data-label')).toBe('Actions');
      // …and an explicit data-label on the header always wins.
      expect(cells[2].getAttribute('data-label')).toBe('Solde');
    });

    it('leaves a full-width row (an empty state, an expanded detail) without a label', () => {
      page.innerHTML = `<table><thead><tr><th>A</th><th>B</th><th>C</th></tr></thead>
        <tbody>
          <tr><td>1</td><td>2</td><td>3</td></tr>
          <tr><td colspan="3">Aucun résultat</td></tr>
          <tr><td colspan="2">Groupe</td><td>9</td></tr>
        </tbody></table>`;
      labelTable(page.querySelector('table')!);
      const rows = page.querySelectorAll('tbody tr');
      expect(rows[1].querySelector('td')!.hasAttribute('data-label')).toBeFalse();
      // After a spanning cell the next cell still answers to ITS column.
      expect(rows[2].querySelectorAll('td')[1].getAttribute('data-label')).toBe('C');
    });

    it('never labels a table with a cell that spans rows: the next row would read under the wrong headers', () => {
      // A medication chart: the drug spans its two doses. Counted from column 0,
      // the second row's « 500 mg » would be labelled « Médicament » and
      // « 20:00 » would be labelled « Dose ».
      page.innerHTML = `<table><thead><tr><th>Médicament</th><th>Dose</th><th>Heure</th></tr></thead>
        <tbody>
          <tr><td rowspan="2">Amoxicilline</td><td>500 mg</td><td>08:00</td></tr>
          <tr><td>500 mg</td><td>20:00</td></tr>
        </tbody></table>`;
      const table = page.querySelector('table')!;
      expect(isCardTable(table)).toBeFalse();
      expect(labelTable(table)).toBeFalse();
      expect(table.hasAttribute('data-cards')).toBeFalse();
      expect(page.querySelectorAll('[data-label]').length).toBe(0);
    });

    it('takes its labels back when a spanning cell is rendered later', () => {
      page.innerHTML = `<table><thead><tr><th>Médicament</th><th>Dose</th></tr></thead>
        <tbody><tr><td>Amoxicilline</td><td>500 mg</td></tr></tbody></table>`;
      const table = page.querySelector('table')!;
      expect(labelTable(table)).toBeTrue();
      table.tBodies[0].insertAdjacentHTML(
        'beforeend',
        `<tr><td rowspan="2">Paracétamol</td><td>1 g</td></tr><tr><td>1 g</td></tr>`,
      );
      expect(labelTable(table)).toBeFalse();
      expect(table.hasAttribute('data-cards')).toBeFalse();
      expect(page.querySelectorAll('[data-label]').length).toBe(0);
    });

    it("leaves a page's own card layout alone: its labels are never overwritten", () => {
      // The till closing writes its own data-label on each cell and styles its
      // own phone cards; the header says « Attendu », the cell says « Attendu en caisse ».
      page.innerHTML = `<table class="cash-methods"><thead><tr><th>Mode</th><th>Attendu</th></tr></thead>
        <tbody><tr><td>Espèces</td><td data-label="Attendu en caisse">12 000 F CFA</td></tr></tbody></table>`;
      const table = page.querySelector('table')!;
      expect(isCardTable(table)).toBeFalse();
      expect(labelTable(table)).toBeFalse();
      expect(table.hasAttribute('data-cards')).toBeFalse();
      expect(page.querySelector('td:last-child')!.getAttribute('data-label')).toBe(
        'Attendu en caisse',
      );
      expect(page.querySelector('td:first-child')!.hasAttribute('data-label')).toBeFalse();
    });

    it('gives the table back when rows with their own labels are rendered into it later', () => {
      page.innerHTML = `<table><thead><tr><th>Mode</th><th>Attendu</th></tr></thead>
        <tbody><tr><td>Chèque</td><td>0</td></tr></tbody></table>`;
      const table = page.querySelector('table')!;
      expect(labelTable(table)).toBeTrue();
      table.tBodies[0].insertAdjacentHTML(
        'beforeend',
        `<tr><td data-label="Mode de paiement">Espèces</td><td data-label="Attendu en caisse">12 000</td></tr>`,
      );
      expect(labelTable(table)).toBeFalse();
      expect(table.hasAttribute('data-cards')).toBeFalse();
      // Ours are taken back; the template's stay.
      const rows = table.tBodies[0].rows;
      expect(rows[0].cells[0].hasAttribute('data-label')).toBeFalse();
      expect(rows[1].cells[0].getAttribute('data-label')).toBe('Mode de paiement');
      expect(rows[1].cells[1].getAttribute('data-label')).toBe('Attendu en caisse');
    });

    it('leaves a table without one plain header row as a table', () => {
      for (const html of [
        `<table><tbody><tr><td>x</td><td>y</td></tr></tbody></table>`,
        `<table><thead><tr><th colspan="2">Lundi</th></tr><tr><th>M</th><th>S</th></tr></thead>
           <tbody><tr><td>x</td><td>y</td></tr></tbody></table>`,
        `<table><thead><tr><th colspan="2">Lundi</th><th>Mardi</th></tr></thead>
           <tbody><tr><td>x</td><td>y</td><td>z</td></tr></tbody></table>`,
        `<div class="fc"><table><thead><tr><th>L</th><th>M</th></tr></thead>
           <tbody><tr><td>1</td><td>2</td></tr></tbody></table></div>`,
        `<table data-no-cards><thead><tr><th>L</th><th>M</th></tr></thead>
           <tbody><tr><td>1</td><td>2</td></tr></tbody></table>`,
      ]) {
        page.innerHTML = html;
        const table = page.querySelector('table')!;
        expect(labelTable(table)).withContext(html).toBeFalse();
        expect(table.hasAttribute('data-cards')).withContext(html).toBeFalse();
      }
    });

    it('labels the rows Angular renders later, and relabels when a header changes', async () => {
      page.innerHTML = `<table><thead><tr><th>Nom</th><th>Statut</th></tr></thead><tbody></tbody></table>`;
      installDesignConventions(document);
      await frame();
      const body = page.querySelector('tbody')!;
      body.innerHTML = `<tr><td>Awa</td><td>Actif</td></tr>`;
      await settle();
      expect(body.querySelector('td:last-child')!.getAttribute('data-label')).toBe('Statut');
      page.querySelector('th:last-child')!.textContent = 'État';
      await settle();
      expect(body.querySelector('td:last-child')!.getAttribute('data-label')).toBe('État');
    });
  });

  describe('icons are not text (D2792)', () => {
    it('hides an icon beside a name: in a heading, a link, a labelled button, a sortable header', () => {
      page.innerHTML = `
        <h1><span class="material-symbols-outlined">calendar_month</span> Mes rendez-vous</h1>
        <a href="#"><span class="material-symbols-outlined nav-icon">dashboard</span><span>Tableau de bord</span></a>
        <button aria-label="Modifier"><span class="material-icons">edit</span></button>
        <table><thead><tr>
          <th>Médecin <span class="material-symbols-outlined sort-icon">unfold_more</span></th>
        </tr></thead></table>`;
      applyDesignConventions(document);
      const icons = Array.from(
        page.querySelectorAll('.material-symbols-outlined, .material-icons'),
      );
      expect(icons.length).toBe(4);
      for (const icon of icons) {
        expect(icon.getAttribute('aria-hidden')).withContext(icon.textContent!).toBe('true');
      }
    });

    it('keeps the icon when it is the only name its control has', () => {
      page.innerHTML = `<button><span class="material-symbols-outlined">delete</span></button>`;
      const icon = page.querySelector('span')!;
      hideIcon(icon);
      expect(icon.hasAttribute('aria-hidden')).toBeFalse();
    });

    it('leaves a free-standing icon alone: it may be the only signal of its cell', () => {
      // An abnormal-result flag beside a value, a « payé » mark in a row: the
      // text next to them does not say what the icon says.
      page.innerHTML = `<table><tbody><tr>
          <td><span class="material-symbols-outlined abnormal-flag">warning</span> 12,5 g/dL</td>
          <td><span class="material-symbols-outlined">check_circle</span></td>
        </tr></tbody></table>
        <p><span class="material-icons">priority_high</span></p>`;
      applyDesignConventions(document);
      for (const icon of Array.from(
        page.querySelectorAll('.material-symbols-outlined, .material-icons'),
      )) {
        expect(icon.hasAttribute('aria-hidden')).withContext(icon.textContent!).toBeFalse();
      }
    });

    it('gives the icon back when the label beside it is not rendered (a tab label hidden on a phone)', () => {
      page.innerHTML = `<button class="tab-btn">
          <span class="material-symbols-outlined">person</span>
          <span class="tab-label">Aperçu</span>
        </button>`;
      const icon = page.querySelector('.material-symbols-outlined')!;
      const label = page.querySelector<HTMLElement>('.tab-label')!;
      applyDesignConventions(document);
      expect(icon.getAttribute('aria-hidden')).toBe('true');
      // The page's own phone rule hides the label: the icon is the button's only name again.
      label.style.display = 'none';
      applyDesignConventions(document);
      expect(icon.hasAttribute('aria-hidden')).toBeFalse();
      label.style.display = '';
      applyDesignConventions(document);
      expect(icon.getAttribute('aria-hidden')).toBe('true');
    });

    it('never overrides what a template chose', () => {
      page.innerHTML = `<h2><span class="material-icons" aria-hidden="false">warning</span> Alerte</h2>
        <h2><span class="material-icons" role="img" aria-label="Alerte">warning</span> Titre</h2>
        <h2><span class="material-symbols-outlined" title="Identité vérifiée">verified</span> Awa</h2>`;
      applyDesignConventions(document);
      const [explicit, image, titled] = Array.from(page.querySelectorAll('span'));
      expect(explicit.getAttribute('aria-hidden')).toBe('false');
      expect(image.hasAttribute('aria-hidden')).toBeFalse();
      // An icon with a title is information: it is not silenced.
      expect(titled.hasAttribute('aria-hidden')).toBeFalse();
    });
  });

  describe('icon-only buttons have a tooltip (D2807)', () => {
    it('copies the accessible name to the title', () => {
      page.innerHTML = `<button aria-label="Désactiver l'utilisateur"><span class="material-symbols-outlined">block</span></button>`;
      const button = page.querySelector('button')!;
      titleIconButton(button);
      expect(button.title).toBe("Désactiver l'utilisateur");
    });

    it('leaves a button with visible text, or with its own title, alone', () => {
      page.innerHTML = `
        <button aria-label="Nouvelle facture"><span class="material-symbols-outlined">add</span> Nouvelle facture</button>
        <button aria-label="Modifier" title="Modifier la ligne"><span class="material-symbols-outlined">edit</span></button>`;
      applyDesignConventions(document);
      const [withText, withTitle] = Array.from(page.querySelectorAll('button'));
      expect(withText.hasAttribute('title')).toBeFalse();
      expect(withTitle.title).toBe('Modifier la ligne');
    });

    it('follows the name when a toggle renames its button, on the page as it runs', async () => {
      // « Désactiver » on an active row becomes « Réactiver » once it is off: a
      // tooltip left on the first name would say the opposite of the button.
      page.innerHTML = `<button aria-label="Désactiver le type de visite"><span class="material-symbols-outlined">block</span></button>`;
      const button = page.querySelector('button')!;
      installDesignConventions(document);
      await settle();
      expect(button.title).toBe('Désactiver le type de visite');
      button.setAttribute('aria-label', 'Réactiver le type de visite');
      await settle();
      expect(button.title).toBe('Réactiver le type de visite');
    });

    it('takes its tooltip back when the button is no longer icon-only', () => {
      page.innerHTML = `<button aria-label="Modifier"><span class="material-symbols-outlined">edit</span></button>
        <button aria-label="Supprimer" title="Supprimer la ligne"><span class="material-symbols-outlined">delete</span></button>`;
      const [auto, authored] = Array.from(page.querySelectorAll('button'));
      applyDesignConventions(document);
      expect(auto.title).toBe('Modifier');
      auto.append(' Modifier');
      authored.append(' Supprimer');
      applyDesignConventions(document);
      expect(auto.hasAttribute('title')).toBeFalse();
      // A title the template wrote stays.
      expect(authored.title).toBe('Supprimer la ligne');
    });
  });

  describe('sidebar labels (D2803)', () => {
    it('shows the full text of a label cut by an ellipsis, and nothing on one that fits', () => {
      page.innerHTML = `<nav class="sidebar" style="width: 120px">
          <a href="#"><span class="nav-label" style="display:block;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;width:100px">Registre des stupéfiants et substances contrôlées</span></a>
          <a href="#"><span class="nav-label" style="display:block;overflow:hidden;white-space:nowrap;width:100px">Lits</span></a>
        </nav>`;
      applyDesignConventions(document);
      const [cut, fits] = Array.from(page.querySelectorAll('a'));
      expect(cut.title).toBe('Registre des stupéfiants et substances contrôlées');
      expect(fits.hasAttribute('title')).toBeFalse();
      // Wide enough again: the tooltip goes, and the tooltip pass for buttons never took it.
      cut.querySelector<HTMLElement>('.nav-label')!.style.width = '600px';
      page.querySelector<HTMLElement>('.sidebar')!.style.width = '700px';
      applyDesignConventions(document);
      expect(cut.hasAttribute('title')).toBeFalse();
    });

    it('is not measured on a pass that asks to skip the navigation', () => {
      page.innerHTML = `<nav class="sidebar"><a href="#"><span class="nav-label" style="display:block;overflow:hidden;white-space:nowrap;width:40px">Communication du dossier</span></a></nav>`;
      applyDesignConventions(document, { navigation: false });
      expect(page.querySelector('a')!.hasAttribute('title')).toBeFalse();
      applyDesignConventions(document);
      expect(page.querySelector('a')!.title).toBe('Communication du dossier');
    });
  });
});
