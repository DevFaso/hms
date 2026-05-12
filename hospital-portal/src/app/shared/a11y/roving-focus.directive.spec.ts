import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RovingFocusDirective } from './roving-focus.directive';

/**
 * v1.0 row 11 finish — unit tests for the RovingFocusDirective.
 *
 * The directive is a thin DOM glue layer (keydown → tabindex update +
 * focus()) so we test against real DOM dispatch, not Angular form
 * abstractions. Each describe block exercises one wiring mode used
 * by the three production callers (vitals grid, patient-tracker rows,
 * in-basket panel).
 */
// Note: items intentionally have NO explicit tabindex attribute. The
// directive's contract is "items with an explicit tabindex are
// author-controlled and opt out of the roving group", so the directive
// only manages items that are tabindex-naked at mount. To make the
// items focusable in the test we rely on the directive setting
// tabindex itself in ngAfterViewInit.
@Component({
  standalone: true,
  imports: [RovingFocusDirective],
  template: `
    <ul appRovingFocus="li.item" [orientation]="orientation" [modifier]="modifier" [wrap]="wrap">
      <li class="item" data-id="0">a</li>
      <li class="item" data-id="1">b</li>
      <li class="item" data-id="2">c</li>
    </ul>
  `,
})
class HostComponent {
  orientation: 'vertical' | 'horizontal' | 'both' = 'vertical';
  modifier: 'none' | 'alt' | 'ctrl' = 'none';
  wrap = false;
}

function press(host: HTMLElement, key: string, opts: { alt?: boolean; ctrl?: boolean } = {}): void {
  const target = (document.activeElement as HTMLElement) ?? host;
  const event = new KeyboardEvent('keydown', {
    key,
    altKey: !!opts.alt,
    ctrlKey: !!opts.ctrl,
    bubbles: true,
    cancelable: true,
  });
  target.dispatchEvent(event);
}

function items(fixture: ComponentFixture<HostComponent>): HTMLElement[] {
  return Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('li.item'),
  );
}

describe('RovingFocusDirective', () => {
  let fixture: ComponentFixture<HostComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    // The directive's ngAfterViewInit runs after detectChanges. Tabindex
    // should now be applied: first item 0, others -1.
  });

  it('initializes tabindex to 0 on the first item and -1 on the rest', () => {
    const [a, b, c] = items(fixture);
    expect(a.getAttribute('tabindex')).toBe('0');
    expect(b.getAttribute('tabindex')).toBe('-1');
    expect(c.getAttribute('tabindex')).toBe('-1');
  });

  describe('vertical orientation, no modifier (default)', () => {
    it('ArrowDown advances focus and tabindex to the next item', () => {
      const [a, b] = items(fixture);
      a.focus();
      press(fixture.nativeElement, 'ArrowDown');
      expect(document.activeElement).toBe(b);
      expect(b.getAttribute('tabindex')).toBe('0');
      expect(a.getAttribute('tabindex')).toBe('-1');
    });

    it('ArrowUp at index 0 is a clamped no-op', () => {
      const [a] = items(fixture);
      a.focus();
      press(fixture.nativeElement, 'ArrowUp');
      expect(document.activeElement).toBe(a);
      expect(a.getAttribute('tabindex')).toBe('0');
    });

    it('ArrowDown past the last item clamps when wrap is false', () => {
      const [, , c] = items(fixture);
      c.focus();
      // Reset the tabindex so the active state matches c
      c.setAttribute('tabindex', '0');
      press(fixture.nativeElement, 'ArrowDown');
      expect(document.activeElement).toBe(c);
    });

    it('wraps to first item past the last when wrap=true', () => {
      fixture.componentInstance.wrap = true;
      fixture.detectChanges();
      const [a, , c] = items(fixture);
      c.setAttribute('tabindex', '0');
      c.focus();
      press(fixture.nativeElement, 'ArrowDown');
      expect(document.activeElement).toBe(a);
    });

    it('ignores ArrowLeft / ArrowRight when orientation is vertical', () => {
      const [a] = items(fixture);
      a.focus();
      press(fixture.nativeElement, 'ArrowRight');
      expect(document.activeElement).toBe(a);
    });
  });

  describe('horizontal orientation', () => {
    beforeEach(() => {
      fixture.componentInstance.orientation = 'horizontal';
      fixture.detectChanges();
    });

    it('ArrowRight advances focus to the next item', () => {
      const [a, b] = items(fixture);
      a.focus();
      press(fixture.nativeElement, 'ArrowRight');
      expect(document.activeElement).toBe(b);
    });

    it('does not respond to ArrowDown when orientation is horizontal', () => {
      const [a] = items(fixture);
      a.focus();
      press(fixture.nativeElement, 'ArrowDown');
      expect(document.activeElement).toBe(a);
    });
  });

  describe('alt modifier mode', () => {
    beforeEach(() => {
      fixture.componentInstance.modifier = 'alt';
      fixture.detectChanges();
    });

    it('plain ArrowDown is ignored (no Alt held)', () => {
      const [a] = items(fixture);
      a.focus();
      press(fixture.nativeElement, 'ArrowDown'); // no modifier
      expect(document.activeElement).toBe(a);
    });

    it('Alt+ArrowDown advances focus when alt is held', () => {
      const [a, b] = items(fixture);
      a.focus();
      press(fixture.nativeElement, 'ArrowDown', { alt: true });
      expect(document.activeElement).toBe(b);
    });
  });

  describe('none modifier mode rejects modifier keystrokes', () => {
    it('Alt+ArrowDown does not advance focus when modifier=none', () => {
      const [a] = items(fixture);
      a.focus();
      press(fixture.nativeElement, 'ArrowDown', { alt: true });
      expect(document.activeElement).toBe(a);
    });
  });
});
