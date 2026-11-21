import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { CardSortChange, CardSortComponent } from './card-sort.component';

describe('CardSortComponent (#1247: sorting a table in card mode)', () => {
  let fixture: ComponentFixture<CardSortComponent>;
  let changes: CardSortChange[];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CardSortComponent, TranslateModule.forRoot()],
    }).compileComponents();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      CARD_SORT: {
        GROUP: 'Trier la liste',
        SORT_BY: 'Trier par',
        ASCENDING: 'Croissant',
        DESCENDING: 'Décroissant',
        ASCENDING_FLIP: 'Croissant, passer en décroissant',
        DESCENDING_FLIP: 'Décroissant, passer en croissant',
      },
      COL: { NAME: 'Nom', DATE: 'Date' },
    });
    translate.use('fr');
    fixture = TestBed.createComponent(CardSortComponent);
    changes = [];
    fixture.componentInstance.sortChange.subscribe((c) => changes.push(c));
    fixture.componentRef.setInput('columns', [
      { field: 'name', label: 'COL.NAME' },
      { field: 'date', label: 'COL.DATE' },
    ]);
    fixture.componentRef.setInput('field', 'date');
    fixture.componentRef.setInput('direction', 'desc');
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const select = () => el().querySelector('select') as HTMLSelectElement;
  const button = () => el().querySelector('button') as HTMLButtonElement;

  it('lists the sortable columns, shows the current sort, and is labelled', () => {
    expect(Array.from(select().options).map((o) => o.textContent?.trim())).toEqual(['Nom', 'Date']);
    expect(select().value).toBe('date');
    const label = el().querySelector('label') as HTMLLabelElement;
    expect(label.textContent?.trim()).toBe('Trier par');
    expect(label.htmlFor).toBe(select().id);
    expect(el().querySelector('[role="group"]')?.getAttribute('aria-label')).toBe('Trier la liste');
    expect(button().type).toBe('button');
    // The accessible name starts with the visible text (WCAG 2.5.3).
    expect(button().textContent).toContain('Décroissant');
    expect(button().getAttribute('aria-label')).toBe('Décroissant, passer en croissant');
    expect(button().querySelector('.material-symbols-outlined')?.getAttribute('aria-hidden')).toBe(
      'true',
    );
  });

  it('a new column keeps the direction; the toggle flips it', () => {
    select().value = 'name';
    select().dispatchEvent(new Event('change'));
    button().click();
    expect(changes).toEqual([
      { field: 'name', direction: 'desc' },
      { field: 'date', direction: 'asc' },
    ]);
  });

  it('picking the current column changes nothing', () => {
    select().value = 'date';
    select().dispatchEvent(new Event('change'));
    expect(changes).toEqual([]);
  });

  it('follows the page when its sort changes elsewhere (a header click)', () => {
    fixture.componentRef.setInput('field', 'name');
    fixture.componentRef.setInput('direction', 'asc');
    fixture.detectChanges();
    expect(select().value).toBe('name');
    expect(button().getAttribute('aria-label')).toBe('Croissant, passer en décroissant');
  });
});
