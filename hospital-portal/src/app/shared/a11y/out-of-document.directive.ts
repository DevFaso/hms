import { DestroyRef, Directive, ElementRef, effect, inject, input } from '@angular/core';

/**
 * Takes its element out of the document while the input is true, and puts it
 * back, unchanged, when it turns false. Used by every overlay that claims to
 * lock the screen: what is behind a lock must not be readable in the page
 * (by "select all", a screen reader or the browser's tools), which hiding or
 * blurring does not achieve. The element keeps its state while it is out, so
 * what the person was typing is still there after the unlock.
 *
 * A comment stands in for the element while it is out.
 */
@Directive({
  selector: '[appOutOfDocument]',
  standalone: true,
})
export class OutOfDocumentDirective {
  readonly appOutOfDocument = input(false);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private placeholder: Comment | null = null;

  constructor() {
    effect(() => {
      if (this.appOutOfDocument()) this.takeOut();
      else this.putBack();
    });
    // The element is never put back by a teardown: it leaves with its view.
    inject(DestroyRef).onDestroy(() => this.placeholder?.remove());
  }

  private takeOut(): void {
    const parent = this.host.parentNode;
    if (this.placeholder || !parent) return;
    this.placeholder = this.host.ownerDocument.createComment('locked');
    parent.replaceChild(this.placeholder, this.host);
  }

  private putBack(): void {
    const placeholder = this.placeholder;
    if (!placeholder) return;
    this.placeholder = null;
    placeholder.parentNode?.replaceChild(this.host, placeholder);
  }
}
