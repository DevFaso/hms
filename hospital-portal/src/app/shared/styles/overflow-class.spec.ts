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

  /* ── r66 (D2938): a dialog is never taller than the screen ── */

  const DIALOG_FAMILIES = [
    'modal modal-lg',
    'modal-content modal-vitals',
    'modal-panel',
    'modal-card',
    'modal-box',
    'dialog-box',
    'dialog-panel',
  ];
  const ACTION_ROWS = ['modal-actions', 'modal-footer', 'dialog-actions', 'dialog-footer'];

  /**
   * A dialog in a centred backdrop, as the portal builds them, holding far more
   * fields than a screen. Its entrance animation (a 20 px slide) is off: the
   * dialog is measured where it rests.
   */
  function tallDialog(cls: string, actions: string, attrs = ''): HTMLElement {
    box.innerHTML = `<div style="position:fixed;inset:0;display:flex;align-items:center;justify-content:center">
        <div class="${cls}" ${attrs} style="background:#fff;width:300px;padding:24px;animation:none">
          <h3>Signes vitaux</h3>
          <div style="height:3000px">champs</div>
          <div class="${actions}"><button type="button">Enregistrer</button></div>
        </div>
      </div>`;
    return box.firstElementChild!.firstElementChild as HTMLElement;
  }

  it('caps every dialog family at the screen height and lets it scroll (D2938)', () => {
    for (const cls of DIALOG_FAMILIES) {
      const dialog = tallDialog(cls, 'modal-actions');
      const rect = dialog.getBoundingClientRect();
      expect(rect.top).withContext(cls).toBeGreaterThanOrEqual(0);
      expect(rect.bottom).withContext(cls).toBeLessThanOrEqual(window.innerHeight);
      expect(getComputedStyle(dialog).overflowY).withContext(cls).toBe('auto');
      expect(dialog.scrollHeight).withContext(cls).toBeGreaterThan(dialog.clientHeight);
    }
  });

  it('caps a dialog known only by its role, never a drawer (D2938)', () => {
    const dialog = tallDialog('cred-renewal', 'modal-actions', 'role="dialog" aria-modal="true"');
    expect(dialog.getBoundingClientRect().bottom).toBeLessThanOrEqual(window.innerHeight);
    const drawer = tallDialog('invoice-drawer', 'modal-actions', 'role="dialog" aria-modal="true"');
    expect(getComputedStyle(drawer).maxHeight).toBe('none');
  });

  it('keeps the action row of every dialog family on screen, scrolled or not (D2938)', () => {
    for (const cls of DIALOG_FAMILIES) {
      for (const actions of ACTION_ROWS) {
        const dialog = tallDialog(cls, actions);
        const save = dialog.querySelector('button')!;
        for (const scrollTop of [0, 1200, dialog.scrollHeight]) {
          dialog.scrollTop = scrollTop;
          const button = save.getBoundingClientRect();
          const frame = dialog.getBoundingClientRect();
          const where = `${cls} > ${actions} @${scrollTop}`;
          expect(button.height).withContext(where).toBeGreaterThan(0);
          expect(button.top).withContext(where).toBeGreaterThanOrEqual(frame.top);
          expect(button.bottom)
            .withContext(where)
            .toBeLessThanOrEqual(frame.bottom + 1);
          expect(button.bottom).withContext(where).toBeLessThanOrEqual(window.innerHeight);
        }
        // The fields scroll under an opaque row, not through the buttons.
        expect(getComputedStyle(save.parentElement!).backgroundColor)
          .withContext(`${cls} > ${actions}`)
          .toBe('rgb(255, 255, 255)');
      }
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
