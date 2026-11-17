import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { RangePagerComponent } from './range-pager.component';

describe('RangePagerComponent (QA D1379 / D1380 / D1390)', () => {
  let fixture: ComponentFixture<RangePagerComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RangePagerComponent, TranslateModule.forRoot()],
    }).compileComponents();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('en', {
      COMMON: {
        SHOWING_RANGE: 'Showing {{from}}–{{to}} of {{total}}',
        PREVIOUS: 'Previous',
        NEXT: 'Next',
      },
    });
    translate.use('en');
    fixture = TestBed.createComponent(RangePagerComponent);
  });

  function render(
    page: number,
    size: number,
    total: number,
    shown: number | null = null,
  ): HTMLElement {
    fixture.componentRef.setInput('page', page);
    fixture.componentRef.setInput('size', size);
    fixture.componentRef.setInput('total', total);
    fixture.componentRef.setInput('shown', shown);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('says which rows of the server total are on screen', () => {
    const el = render(1, 20, 667);
    expect(el.textContent).toContain('Showing 21–40 of 667');
  });

  it('counts a short last page from the rows it holds', () => {
    const el = render(33, 20, 667, 7);
    expect(el.textContent).toContain('Showing 661–667 of 667');
    expect(
      (el.querySelector('[data-testid="range-pager-next"]') as HTMLButtonElement).disabled,
    ).toBeTrue();
  });

  it('emits the next and previous page, never past either end', () => {
    const pages: number[] = [];
    fixture.componentInstance.pageChange.subscribe((p) => pages.push(p));
    const el = render(0, 10, 25);
    (el.querySelector('[data-testid="range-pager-prev"]') as HTMLButtonElement).click();
    (el.querySelector('[data-testid="range-pager-next"]') as HTMLButtonElement).click();
    expect(pages).toEqual([1]);
  });

  it('shows the count without buttons when everything fits, and nothing when there is nothing', () => {
    let el = render(0, 10, 4);
    expect(el.textContent).toContain('Showing 1–4 of 4');
    expect(el.querySelector('[data-testid="range-pager-next"]')).toBeNull();
    el = render(0, 10, 0);
    expect(el.querySelector('[data-testid="range-pager"]')).toBeNull();
  });
});
