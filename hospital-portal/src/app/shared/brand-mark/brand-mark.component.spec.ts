import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BrandMarkComponent } from './brand-mark.component';

/**
 * The e-Keneya mark.
 *
 * Coverage:
 *   - renders at the requested size
 *   - `tone` picks the green that holds contrast on that ground
 *   - stays decorative (aria-hidden), because every mounting point renders
 *     "e-Keneya" as real text beside it — a label here is announced twice
 *   - keeps the four woven centre cells, which are the mark itself
 */
describe('BrandMarkComponent', () => {
  let fixture: ComponentFixture<BrandMarkComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [BrandMarkComponent] });
    fixture = TestBed.createComponent(BrandMarkComponent);
  });

  function svg(): SVGSVGElement {
    return fixture.nativeElement.querySelector('svg');
  }

  it('creates', () => {
    fixture.detectChanges();
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders a square at the requested size and keeps the 64-unit viewBox', () => {
    fixture.componentRef.setInput('size', 44);
    fixture.detectChanges();

    expect(svg().getAttribute('width')).toBe('44');
    expect(svg().getAttribute('height')).toBe('44');
    // The viewBox is fixed so every instance scales rather than re-lays-out.
    expect(svg().getAttribute('viewBox')).toBe('0 0 64 64');
  });

  it('uses the deep green on light grounds', () => {
    fixture.componentRef.setInput('tone', 'onLight');
    fixture.detectChanges();

    const vertical = fixture.nativeElement.querySelector('rect[height="56"]');
    expect(vertical.getAttribute('fill')).toBe('#0E7C6B');
  });

  it('lifts the green on dark grounds so it holds against the sidebar', () => {
    fixture.componentRef.setInput('tone', 'onDark');
    fixture.detectChanges();

    const vertical = fixture.nativeElement.querySelector('rect[height="56"]');
    expect(vertical.getAttribute('fill')).toBe('#23B79C');
  });

  it('is decorative — the adjacent wordmark carries the name', () => {
    fixture.detectChanges();
    expect(svg().getAttribute('aria-hidden')).toBe('true');
    expect(svg().getAttribute('focusable')).toBe('false');
    expect(svg().getAttribute('aria-label')).toBeNull();
  });

  it('keeps the two ochre cells that make the cross read as woven', () => {
    fixture.detectChanges();

    const cells = fixture.nativeElement.querySelectorAll('rect[width="7"][height="7"]');
    expect(cells.length).toBe(2);
    cells.forEach((cell: SVGRectElement) => expect(cell.getAttribute('fill')).toBe('#E39A2B'));
  });
});
