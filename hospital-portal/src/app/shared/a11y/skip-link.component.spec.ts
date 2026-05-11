import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SkipLinkComponent } from './skip-link.component';

/**
 * v1.0 / Accessibility / Keyboard navigation pass (roadmap row 11).
 *
 * Coverage:
 *   - renders the label
 *   - clicking moves focus to the element matching `target`
 *   - missing-target case logs a warning and does NOT throw
 *   - Enter key triggers the same focus move (keyboard equivalent of click)
 */
describe('SkipLinkComponent', () => {
  let fixture: ComponentFixture<SkipLinkComponent>;
  let component: SkipLinkComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SkipLinkComponent],
    });
    fixture = TestBed.createComponent(SkipLinkComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    // Tests append elements to <body>; clean up between tests so a
    // surviving #main-content can't leak focus into the next case.
    document.querySelectorAll('[data-test-target]').forEach((n) => n.remove());
  });

  it('renders the configured label', () => {
    component.label = 'Skip to main content';
    fixture.detectChanges();
    const anchor = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>(
      '[data-testid="skip-link"]',
    );
    expect(anchor).toBeTruthy();
    expect(anchor!.textContent?.trim()).toBe('Skip to main content');
  });

  it('writes the target into the anchor href so assistive tech sees the destination', () => {
    component.target = '#main-content';
    fixture.detectChanges();
    const anchor = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>(
      '[data-testid="skip-link"]',
    );
    expect(anchor!.getAttribute('href')).toBe('#main-content');
  });

  it('focuses the target element when activated by click', () => {
    const target = document.createElement('main');
    target.id = 'main-content';
    target.tabIndex = -1;
    target.setAttribute('data-test-target', '1');
    document.body.appendChild(target);

    component.target = '#main-content';
    fixture.detectChanges();

    const anchor = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>(
      '[data-testid="skip-link"]',
    );
    anchor!.click();

    expect(document.activeElement).toBe(target);
  });

  it('focuses the target when activated by Enter (keyboard equivalent)', () => {
    const target = document.createElement('main');
    target.id = 'main-content';
    target.tabIndex = -1;
    target.setAttribute('data-test-target', '1');
    document.body.appendChild(target);

    component.target = '#main-content';
    fixture.detectChanges();

    const anchor = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>(
      '[data-testid="skip-link"]',
    );
    anchor!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));

    expect(document.activeElement).toBe(target);
  });

  it('warns but does not throw when target is missing', () => {
    const warnSpy = spyOn(console, 'warn');
    component.target = '#does-not-exist';
    fixture.detectChanges();

    const anchor = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>(
      '[data-testid="skip-link"]',
    );
    expect(() => anchor!.click()).not.toThrow();
    expect(warnSpy).toHaveBeenCalled();
  });
});
