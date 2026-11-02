import { blankFields, focusFirstInvalid, invalidMessageId, refuseFields } from './invalid-fields';

describe('invalid-fields (QA D1072)', () => {
  let root: HTMLElement;

  beforeEach(() => {
    root = document.createElement('form');
    root.innerHTML = `
      <input id="first" />
      <input id="last" aria-describedby="last-hint" />
      <small id="last-hint">hint</small>
      <select id="gender"></select>
    `;
    document.body.appendChild(root);
  });

  afterEach(() => root.remove());

  it('marks each refused field, shows a message under it and focuses the first', () => {
    refuseFields(
      root,
      ['last', 'gender'],
      ['first', 'last', 'gender'],
      'Ce champ est obligatoire.',
    );

    const last = root.querySelector<HTMLInputElement>('#last')!;
    expect(last.getAttribute('aria-invalid')).toBe('true');
    expect(last.getAttribute('aria-describedby')).toBe(`last-hint ${invalidMessageId('last')}`);
    const msg = root.querySelector(`#${invalidMessageId('last')}`)!;
    expect(msg.textContent).toBe('Ce champ est obligatoire.');
    expect(msg.getAttribute('role')).toBe('alert');
    expect(last.nextElementSibling).toBe(msg);
    expect(root.querySelector('#gender')!.getAttribute('aria-invalid')).toBe('true');
    expect(root.querySelector('#first')!.hasAttribute('aria-invalid')).toBeFalse();
    expect(document.activeElement).toBe(last);
  });

  it('clears the mark and the message once the field is filled', () => {
    refuseFields(root, ['last'], ['first', 'last'], 'x');
    refuseFields(root, [], ['first', 'last'], 'x');

    const last = root.querySelector('#last')!;
    expect(last.hasAttribute('aria-invalid')).toBeFalse();
    expect(last.getAttribute('aria-describedby')).toBe('last-hint');
    expect(root.querySelector(`#${invalidMessageId('last')}`)).toBeNull();
  });

  it('a second refusal does not duplicate the message', () => {
    refuseFields(root, ['first'], ['first'], 'a');
    refuseFields(root, ['first'], ['first'], 'b');
    expect(root.querySelectorAll(`#${invalidMessageId('first')}`).length).toBe(1);
    expect(root.querySelector(`#${invalidMessageId('first')}`)!.textContent).toBe('b');
    expect(root.querySelector('#first')!.getAttribute('aria-describedby')).toBe(
      invalidMessageId('first'),
    );
  });

  it('focusFirstInvalid focuses the first id present, in the order given', () => {
    expect(focusFirstInvalid(root, ['missing', 'gender', 'first'])).toBeTrue();
    expect(document.activeElement).toBe(root.querySelector('#gender'));
    expect(focusFirstInvalid(root, [])).toBeFalse();
    expect(focusFirstInvalid(null, ['first'])).toBeFalse();
  });

  it('blankFields lists the blank required values in order', () => {
    expect(
      blankFields({ a: 'x', b: '  ', c: null, d: undefined, e: 0 }, ['a', 'b', 'c', 'd', 'e']),
    ).toEqual(['b', 'c', 'd']);
  });
});
