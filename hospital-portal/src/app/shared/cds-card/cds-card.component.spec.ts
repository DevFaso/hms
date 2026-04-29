import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { CdsCardListComponent } from './cds-card.component';
import { CdsCard } from './cds-card.model';

describe('CdsCardListComponent', () => {
  let fixture: ComponentFixture<CdsCardListComponent>;
  let component: CdsCardListComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CdsCardListComponent, TranslateModule.forRoot()],
    }).compileComponents();

    fixture = TestBed.createComponent(CdsCardListComponent);
    component = fixture.componentInstance;
  });

  function setCards(cards: CdsCard[]): void {
    fixture.componentRef.setInput('cards', cards);
    fixture.detectChanges();
  }

  it('renders nothing when the input is empty', () => {
    setCards([]);
    expect(fixture.nativeElement.querySelector('[data-testid="cds-card-list"]')).toBeNull();
  });

  it('renders one item per card', () => {
    setCards([
      sampleCard('warning', 'Possible duplicate order'),
      sampleCard('critical', 'Drug-drug interaction'),
    ]);
    const items = fixture.nativeElement.querySelectorAll('[data-testid="cds-card-list"] li');
    expect(items.length).toBe(2);
  });

  it('applies critical styling class to critical cards', () => {
    setCards([sampleCard('critical', 'Drug-drug interaction')]);
    const li = fixture.nativeElement.querySelector('li');
    expect(li.classList.contains('cds-card--critical')).toBeTrue();
    expect(li.getAttribute('data-indicator')).toBe('critical');
  });

  it('renders detail when present', () => {
    setCards([{ ...sampleCard('info', 'X'), detail: 'Long detail here' }]);
    expect(fixture.nativeElement.textContent).toContain('Long detail here');
  });

  it('uses the card uuid for trackBy when present, summary as fallback', () => {
    const withUuid: CdsCard = { ...sampleCard('info', 'A'), uuid: 'fixed-id' };
    expect(component.trackByCard(0, withUuid)).toBe('fixed-id');

    const withoutUuid: CdsCard = { ...sampleCard('warning', 'Fallback summary'), uuid: undefined };
    expect(component.trackByCard(1, withoutUuid)).toBe('Fallback summary');
  });
});

function sampleCard(indicator: 'info' | 'warning' | 'critical', summary: string): CdsCard {
  return {
    summary,
    indicator,
    source: { label: 'Test source' },
    uuid: `${indicator}-${summary}`,
  };
}
