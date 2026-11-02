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

  it('a removed row: focus goes to the nearest surviving container, never the body', async () => {
    const button = page.querySelector<HTMLButtonElement>('#close-1')!;
    button.focus();
    page.querySelector('#row-1')!.remove();
    await settle();
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement?.tagName).toBe('UL');
    // Script-only focus: no new tab stop.
    expect(document.activeElement?.getAttribute('tabindex')).toBe('-1');
  });

  it('a whole panel replaced: focus goes to the section that is still there', async () => {
    page.querySelector<HTMLButtonElement>('#close-2')!.focus();
    page.querySelector('ul')!.remove();
    await settle();
    expect(document.activeElement).toBe(page.querySelector('#recalls'));
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

  it('leaves alone a focus a screen already moved somewhere else', async () => {
    page.querySelector<HTMLButtonElement>('#close-1')!.focus();
    page.querySelector('#row-1')!.remove();
    page.querySelector<HTMLButtonElement>('#save')!.focus();
    await settle();
    expect(document.activeElement).toBe(page.querySelector('#save'));
  });
});
