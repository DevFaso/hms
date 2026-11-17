import {
  handleDecimalCommaKeydown,
  handleDecimalCommaPaste,
  normaliseDecimalComma,
} from './decimal-comma';

/**
 * QA D505: NURSE_A typed "7,5" kg and a glucose of "5,6" in the vitals modal;
 * the request carried weightKg 75 and bloodGlucoseMgDl 56 (browser in an
 * English locale). The comma must reach the form as a decimal point.
 */
describe('decimal comma in number inputs (QA D505)', () => {
  let input: HTMLInputElement;
  let doc: jasmine.SpyObj<Document>;

  beforeEach(() => {
    input = document.createElement('input');
    input.type = 'number';
    document.body.appendChild(input);
    doc = jasmine.createSpyObj<Document>('Document', ['execCommand']);
  });

  afterEach(() => input.remove());

  function keydown(target: HTMLElement, key: string, code = ''): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key, code, bubbles: true, cancelable: true });
    Object.defineProperty(event, 'target', { value: target });
    return event;
  }

  it('types a comma as a decimal point in a number input', () => {
    const event = keydown(input, ',');
    handleDecimalCommaKeydown(event, doc);
    expect(event.defaultPrevented).toBeTrue();
    expect(doc.execCommand).toHaveBeenCalledWith('insertText', false, '.');
  });

  it('treats the French numeric-pad decimal key the same way', () => {
    const event = keydown(input, ',', 'NumpadDecimal');
    handleDecimalCommaKeydown(event, doc);
    expect(doc.execCommand).toHaveBeenCalledWith('insertText', false, '.');
  });

  it('leaves a point, a digit and a text input alone', () => {
    handleDecimalCommaKeydown(keydown(input, '.'), doc);
    handleDecimalCommaKeydown(keydown(input, '5'), doc);
    const text = document.createElement('input');
    text.type = 'text';
    handleDecimalCommaKeydown(keydown(text, ','), doc);
    expect(doc.execCommand).not.toHaveBeenCalled();
  });

  it('a pasted "7,5" arrives as "7.5"', () => {
    const data = new DataTransfer();
    data.setData('text', '7,5');
    const event = new ClipboardEvent('paste', { clipboardData: data, cancelable: true });
    Object.defineProperty(event, 'target', { value: input });
    handleDecimalCommaPaste(event, doc);
    expect(event.defaultPrevented).toBeTrue();
    expect(doc.execCommand).toHaveBeenCalledWith('insertText', false, '7.5');
  });

  it('normalises only a single decimal comma', () => {
    expect(normaliseDecimalComma('7,5')).toBe('7.5');
    expect(normaliseDecimalComma('-0,25')).toBe('-0.25');
    expect(normaliseDecimalComma('70.5')).toBe('70.5');
    expect(normaliseDecimalComma('1,000,5')).toBe('1,000,5');
  });

  it('in the real document, typing 7 , 5 into a number input gives 7.5', () => {
    input.focus();
    document.execCommand('insertText', false, '7');
    const event = keydown(input, ',');
    handleDecimalCommaKeydown(event, document);
    document.execCommand('insertText', false, '5');
    expect(input.value).toBe('7.5');
    expect(Number(input.value)).toBe(7.5);
  });
});
