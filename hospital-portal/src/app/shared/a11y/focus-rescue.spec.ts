import { installFocusRescue, uninstallFocusRescue } from './focus-rescue';

/**
 * QA D1202 / D1198 / D1199 / D1197 (round 22): focus fell to the page body
 * after a submit, a panel toggle or a close, because the focused control went
 * away. The rescue moves it to the nearest surviving part of the page.
 */
describe('focus rescue (QA D1202 class)', () => {
  let page: HTMLElement;

  const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

  beforeEach(() => {
    page = document.createElement('div');
    page.innerHTML = `
      <main id="main">
        <section id="recalls">
          <ul>
            <li id="row-1"><span>Abdoul Nana</span><button id="close-1">Fermer</button></li>
            <li id="row-2"><span>Awa</span><button id="close-2">Fermer</button></li>
          </ul>
        </section>
        <button id="save">Enregistrer</button>
      </main>`;
    document.body.appendChild(page);
    installFocusRescue(document);
  });

  afterEach(() => {
    uninstallFocusRescue();
    page.remove();
  });

  // QA D1297 (round 28, declared change): the nearest surviving container is
  // no longer used as it is. An anonymous UL or SECTION announces nothing; the
  // stand-in is the nearest NAMED place — here the page's main landmark.
  it('a removed row: focus goes to the nearest named place, never the body or a bare list', async () => {
    const button = page.querySelector<HTMLButtonElement>('#close-1')!;
    button.focus();
    page.querySelector('#row-1')!.remove();
    await settle();
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement).toBe(page.querySelector('main'));
    // Script-only focus: no new tab stop.
    expect(document.activeElement?.getAttribute('tabindex')).toBe('-1');
  });

  it('a whole panel replaced: focus goes to the section that is still there, once it has a name', async () => {
    page.querySelector<HTMLButtonElement>('#close-2')!.focus();
    page.querySelector('ul')!.remove();
    await settle();
    expect(document.activeElement).toBe(page.querySelector('main'));

    const recalls = page.querySelector<HTMLElement>('#recalls')!;
    recalls.innerHTML =
      '<h2>Rappels</h2><ul><li><span>Awa</span><button id="c3">Fermer</button></li></ul>';
    page.querySelector<HTMLButtonElement>('#c3')!.focus();
    recalls.querySelector('ul')!.remove();
    await settle();
    expect(document.activeElement).toBe(recalls.querySelector('h2'));
  });

  it('a submit button disabled while saving gets focus back when enabled', async () => {
    const save = page.querySelector<HTMLButtonElement>('#save')!;
    save.focus();
    save.disabled = true;
    await settle();
    save.disabled = false;
    await settle();
    expect(document.activeElement).toBe(save);
  });

  it('never takes focus the reader moved to the page on purpose', async () => {
    const save = page.querySelector<HTMLButtonElement>('#save')!;
    save.focus();
    save.blur();
    await settle();
    page.querySelector('#row-1')!.remove();
    await settle();
    expect(document.activeElement).toBe(document.body);
  });

  // QA D1283 (round 27): the rescue now waits for a removed control to come
  // back. A control that never comes back must not pull focus to a button
  // elsewhere on the page that only shares its name.
  it('a removed control is not replaced by a same-named button already on the page', async () => {
    const other = document.createElement('button');
    other.id = 'elsewhere';
    other.textContent = 'Enregistrer';
    page.appendChild(other);
    const save = page.querySelector<HTMLButtonElement>('#save')!;
    save.removeAttribute('id');
    save.focus();
    save.remove();
    await settle();
    expect(document.activeElement).not.toBe(other);
    expect(document.activeElement).toBe(page.querySelector('main'));
  });

  it('a row re-rendered with the same patient gets its button back', async () => {
    page.querySelector<HTMLButtonElement>('#close-1')!.focus();
    const list = page.querySelector('ul')!;
    list.innerHTML = '';
    await settle();
    list.innerHTML = '<li><span>Abdoul Nana</span><button>Fermer</button></li>';
    await settle();
    expect(document.activeElement?.textContent).toBe('Fermer');
    expect(document.activeElement?.closest('li')?.textContent).toContain('Abdoul Nana');
  });

  it('leaves alone a focus a screen already moved somewhere else', async () => {
    page.querySelector<HTMLButtonElement>('#close-1')!.focus();
    page.querySelector('#row-1')!.remove();
    page.querySelector<HTMLButtonElement>('#save')!.focus();
    await settle();
    expect(document.activeElement).toBe(page.querySelector('#save'));
  });
});
