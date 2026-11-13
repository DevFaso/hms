/**
 * QA r44: a long unbroken string in a note ("NNNN…") pushed out of its panel in
 * the encounter drawer. styles.scss wraps every element's text (break-word) and
 * lets the free-text displays wrap inside flex and grid cells too (anywhere).
 * This reads the GLOBAL stylesheet as the app loads it (angular.json test styles).
 */
const UNBROKEN = 'N'.repeat(400);
const FREE_TEXT_CLASSES = [
  'free-text',
  'notes-content',
  'detail-notes',
  'range-notes',
  'saved-note',
  'doc-notes',
  'note-history-item',
  'personal-notes',
  'study-notes',
  'vital-notes',
];

describe('global free-text wrapping', () => {
  let box: HTMLDivElement;

  beforeEach(() => {
    box = document.createElement('div');
    box.style.width = '160px';
    document.body.appendChild(box);
  });
  afterEach(() => box.remove());

  it('breaks an unbroken word in ordinary block text instead of overflowing', () => {
    box.innerHTML = `<p>${UNBROKEN}</p>`;
    const p = box.querySelector('p')!;
    expect(p.scrollWidth).toBeLessThanOrEqual(160);
  });

  for (const layout of ['flex', 'grid']) {
    for (const cls of FREE_TEXT_CLASSES) {
      it(`wraps .${cls} inside a ${layout} cell`, () => {
        box.style.display = layout;
        box.innerHTML = `<div class="${cls}">${UNBROKEN}</div>`;
        const cell = box.querySelector('div')!;
        expect(cell.getBoundingClientRect().width).toBeLessThanOrEqual(160);
        expect(box.scrollWidth).toBeLessThanOrEqual(160);
      });
    }
  }
});
