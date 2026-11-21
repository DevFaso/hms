import {
  applyDesignConventions,
  hideIcon,
  installDesignConventions,
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
      await frame();
      await frame();
      expect(body.querySelector('td:last-child')!.getAttribute('data-label')).toBe('Statut');
      page.querySelector('th:last-child')!.textContent = 'État';
      await frame();
      await frame();
      expect(body.querySelector('td:last-child')!.getAttribute('data-label')).toBe('État');
    });
  });

  describe('icons are not text (D2792)', () => {
    it('hides an icon beside a label, in a heading, a link and a sortable header', () => {
      page.innerHTML = `
        <h1><span class="material-symbols-outlined">calendar_month</span> Mes rendez-vous</h1>
        <a href="#"><span class="material-symbols-outlined nav-icon">dashboard</span><span>Tableau de bord</span></a>
        <button aria-label="Modifier"><span class="material-icons">edit</span></button>
        <th>Médecin <span class="material-symbols-outlined sort-icon">unfold_more</span></th>`;
      applyDesignConventions(document);
      for (const icon of Array.from(
        page.querySelectorAll('.material-symbols-outlined, .material-icons'),
      )) {
        expect(icon.getAttribute('aria-hidden')).withContext(icon.textContent!).toBe('true');
      }
    });

    it('keeps the icon when it is the only name its control has', () => {
      page.innerHTML = `<button><span class="material-symbols-outlined">delete</span></button>`;
      const icon = page.querySelector('span')!;
      hideIcon(icon);
      expect(icon.hasAttribute('aria-hidden')).toBeFalse();
    });

    it('never overrides what a template chose', () => {
      page.innerHTML = `<span class="material-icons" aria-hidden="false">warning</span>
        <span class="material-icons" role="img" aria-label="Alerte">warning</span>`;
      applyDesignConventions(document);
      const [explicit, image] = Array.from(page.querySelectorAll('span'));
      expect(explicit.getAttribute('aria-hidden')).toBe('false');
      expect(image.hasAttribute('aria-hidden')).toBeFalse();
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
  });
});
