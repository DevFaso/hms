/**
 * QA D2230 / D2231 (owner screenshots, 2026-11-18): the layout-overflow class.
 * styles.scss carries zero-specificity defaults so a KPI amount, a page
 * toolbar, a row's action buttons and a cell's secondary line never push past
 * their box. This reads the GLOBAL stylesheet as the app loads it (angular.json
 * test styles); e2e/layout-overflow.spec.ts measures the real pages.
 */
describe('global overflow class (D2230 / D2231)', () => {
  let box: HTMLDivElement;

  beforeEach(() => {
    box = document.createElement('div');
    document.body.appendChild(box);
  });
  afterEach(() => box.remove());

  it('wraps an unbreakable KPI amount inside its card', () => {
    box.style.width = '120px';
    box.innerHTML = `<div class="summary-card" style="display:flex">
        <span style="flex-shrink:0;width:48px">i</span>
        <div class="card-info" style="display:flex;flex-direction:column">
          <span class="card-value" style="font-size:20px;font-weight:700">13\u00a0550\u00a0F\u00a0CFA</span>
        </div>
      </div>`;
    const card = box.querySelector<HTMLElement>('.summary-card')!;
    expect(card.scrollWidth).toBeLessThanOrEqual(card.clientWidth + 1);
  });

  it('wraps a page toolbar onto a second line', () => {
    box.style.width = '200px';
    box.innerHTML = `<div class="page-actions" style="display:flex;gap:8px">
        <button style="white-space:nowrap;width:120px">Grille tarifaire</button>
        <button style="white-space:nowrap;width:120px">Nouvelle facture</button>
      </div>`;
    const bar = box.querySelector<HTMLElement>('.page-actions')!;
    expect(getComputedStyle(bar).flexWrap).toBe('wrap');
    expect(bar.scrollWidth).toBeLessThanOrEqual(200);
  });

  it('lets a nowrap amount cell wrap its secondary line instead of widening the table', () => {
    box.style.width = '160px';
    box.innerHTML = `<table style="width:100%;border-collapse:collapse"><tr>
        <td style="white-space:nowrap">0 F CFA
          <span class="field-hint">À rembourser au patient : 100 F CFA</span></td>
      </tr></table>`;
    const hint = box.querySelector<HTMLElement>('.field-hint')!;
    expect(getComputedStyle(hint).whiteSpace).toBe('normal');
    expect(getComputedStyle(hint).display).toBe('block');
    expect(box.querySelector('table')!.getBoundingClientRect().width).toBeLessThanOrEqual(161);
  });

  it('gives a table a scroll box of its own with .table-scroll', () => {
    box.style.width = '200px';
    box.innerHTML = `<div class="table-scroll"><table style="width:600px"><tr><td>x</td></tr></table></div>`;
    const wrap = box.querySelector<HTMLElement>('.table-scroll')!;
    expect(getComputedStyle(wrap).overflowX).toBe('auto');
    expect(wrap.getBoundingClientRect().width).toBeLessThanOrEqual(200);
  });

  it('keeps a row actions cell (and its header) stuck to the right edge (D2321)', () => {
    box.innerHTML = `<table><thead><tr><th>Nom</th><th>Actions</th></tr></thead>
      <tbody><tr><td>x</td><td><div class="action-group"><button>edit</button></div></td></tr></tbody></table>`;
    const cell = box.querySelector<HTMLElement>('tbody td:last-child')!;
    const head = box.querySelector<HTMLElement>('thead th:last-child')!;
    expect(getComputedStyle(cell).position).toBe('sticky');
    expect(getComputedStyle(cell).right).toBe('0px');
    expect(getComputedStyle(head).position).toBe('sticky');
  });

  /* ── r64 (D2461 / D2462 / D2463 / D2284 / D2286) ── */

  it('caps every dialog family at the screen width less a gutter (D2461)', () => {
    for (const cls of ['modal modal-lg', 'modal-content', 'modal-panel', 'dialog-box']) {
      box.innerHTML = `<div class="${cls}" style="width:2000px">x</div>`;
      const dialog = box.firstElementChild as HTMLElement;
      expect(getComputedStyle(dialog).maxWidth).not.toBe('none');
      expect(dialog.getBoundingClientRect().width).toBeLessThanOrEqual(window.innerWidth - 31);
    }
  });

  it('lets a select in a dialog shrink below its longest option (D2286)', () => {
    box.innerHTML = `<div class="modal"><select><option>x</option></select></div>`;
    expect(getComputedStyle(box.querySelector('select')!).minWidth).toBe('0px');
  });

  it('drives the sticky actions cell shadow from the table scroll, not a fixed shadow (D2462)', () => {
    box.innerHTML = `<table><thead><tr><th>Nom</th><th>Actions</th></tr></thead>
      <tbody><tr><td>x</td><td><div class="action-group"><button>edit</button></div></td></tr></tbody></table>`;
    const cell = box.querySelector<HTMLElement>('tbody td:last-child')!;
    const head = box.querySelector<HTMLElement>('thead th:last-child')!;
    // A table that does not scroll has no shadow at all.
    expect(getComputedStyle(cell).boxShadow).toBe('none');
    if (CSS.supports('animation-timeline: scroll()')) {
      expect(getComputedStyle(cell).animationName).toBe('table-scroll-cue-cell');
      expect(getComputedStyle(head).animationName).toBe('table-scroll-cue-cell');
    }
  });

  it('fades the edge of a scrolling table box that has no sticky cell (D2462)', () => {
    box.innerHTML = `<div style="overflow-x:auto;width:100px"><table style="width:600px"><tr><td>x</td></tr></table></div>`;
    const wrap = box.firstElementChild as HTMLElement;
    if (CSS.supports('animation-timeline: scroll()')) {
      expect(getComputedStyle(wrap).animationName).toBe('table-scroll-cue-edge');
    }
  });

  it('styles the shared search box and dialog close and link buttons (D2463 / D2284)', () => {
    box.innerHTML = `<div class="search-box"><span class="material-symbols-outlined">search</span><input /></div>
      <button class="btn-close">x</button><button class="btn-link">Annuler</button>`;
    const search = box.querySelector<HTMLElement>('.search-box')!;
    expect(getComputedStyle(search).display).toBe('flex');
    expect(getComputedStyle(search).borderTopStyle).toBe('solid');
    expect(getComputedStyle(box.querySelector('.search-box input')!).borderTopStyle).toBe('none');
    expect(getComputedStyle(box.querySelector('.btn-close')!).borderTopStyle).toBe('none');
    expect(getComputedStyle(box.querySelector('.btn-link')!).backgroundColor).toBe(
      'rgba(0, 0, 0, 0)',
    );
  });
});
