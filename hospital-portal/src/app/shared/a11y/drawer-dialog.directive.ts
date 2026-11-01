import {
  Directive,
  ElementRef,
  HostListener,
  Injector,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  afterNextRender,
  booleanAttribute,
  inject,
  input,
  output,
} from '@angular/core';

import { captureOpener, restoreFocus } from './focus-return';

/**
 * QA D1153 / D1125 / D1004 (round 21): a panel that is a plain landmark on a
 * wide screen and a modal overlay on a phone — the shell's navigation drawer.
 *
 * At 375 px the drawer slid over the page, but focus stayed on the Menu button
 * behind it, Escape only worked once focus had been put inside by hand, and
 * Tab walked to the language select under the backdrop. `appModalDialog`
 * cannot be used: it makes its host a dialog for its whole life, and on a
 * desktop the sidebar is not one.
 *
 *     <aside appDrawerDialog [appDrawerDialog]="phoneDrawerOpen()"
 *            [attr.aria-label]="'SHELL.MENU' | translate" (drawerClose)="close()">
 *
 * While the bound value is true the host is `role="dialog"` +
 * `aria-modal="true"`, focus moves into it (its first focusable control, else
 * the host), Tab and Shift+Tab stay inside, Escape — pressed anywhere on the
 * page, the Menu button included — emits `drawerClose`, and when it turns
 * false focus goes back to the opener (only when focus was still in the
 * drawer or had fallen to the page: a navigation that moved focus to the new
 * page keeps it there).
 */
@Directive({
  selector: '[appDrawerDialog]',
  standalone: true,
  host: {
    '[attr.role]': "appDrawerDialog() ? 'dialog' : null",
    '[attr.aria-modal]': "appDrawerDialog() ? 'true' : null",
  },
})
export class DrawerDialogDirective implements OnChanges, OnDestroy {
  /** True while the panel covers the page as a modal (the phone drawer open). */
  readonly appDrawerDialog = input(false, { transform: booleanAttribute });
  /** Escape was pressed while the drawer was a modal. */
  readonly drawerClose = output<void>();

  private static readonly FOCUSABLE = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled]):not([type="hidden"])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(',');

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly injector = inject(Injector);
  private opener: HTMLElement | null = null;
  private open = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['appDrawerDialog']) return;
    const now = this.appDrawerDialog();
    if (now === this.open) return;
    this.open = now;
    if (now) {
      this.opener = captureOpener();
      // After the drawer's own content (rendered under the same flag) is on the page.
      afterNextRender(() => this.focusInitial(), { injector: this.injector });
    } else {
      // After this render: the page behind may still be `inert` until its own
      // binding updates later in the same pass, and an inert opener takes no focus.
      this.giveFocusBack((restore) => afterNextRender(restore, { injector: this.injector }));
    }
  }

  ngOnDestroy(): void {
    if (this.open) this.giveFocusBack((restore) => restore());
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeydown(event: KeyboardEvent): void {
    if (!this.open || event.defaultPrevented) return;
    const box = this.box();
    const active = document.activeElement;
    // Focus inside another modal (a dialog opened over the drawer) belongs to that one.
    if (
      active instanceof HTMLElement &&
      !box.contains(active) &&
      active.closest('[aria-modal="true"]')
    )
      return;
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.drawerClose.emit();
      return;
    }
    if (event.key !== 'Tab') return;
    const items = this.focusable();
    if (!box.contains(active)) {
      event.preventDefault();
      (items[0] ?? box).focus();
      return;
    }
    if (items.length === 0) {
      event.preventDefault();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && (active === first || active === box)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusInitial(): void {
    if (!this.open) return;
    const box = this.box();
    if (box.contains(document.activeElement) && document.activeElement !== box) return;
    const first = this.focusable()[0];
    if (first) {
      first.focus();
      return;
    }
    if (!box.hasAttribute('tabindex')) box.setAttribute('tabindex', '-1');
    box.focus();
  }

  /** Focus goes back to the opener only when it was still in the drawer or had fallen to the page. */
  private giveFocusBack(schedule: (restore: () => void) => void): void {
    const opener = this.opener;
    this.opener = null;
    const active = document.activeElement;
    const focusWasHere = active === null || active === document.body || this.box().contains(active);
    if (focusWasHere) schedule(() => restoreFocus(opener));
  }

  private box(): HTMLElement {
    return this.host.nativeElement as HTMLElement;
  }

  private focusable(): HTMLElement[] {
    return Array.from(
      this.box().querySelectorAll<HTMLElement>(DrawerDialogDirective.FOCUSABLE),
    ).filter(
      (el) =>
        !el.closest('[hidden]') &&
        el.getAttribute('aria-hidden') !== 'true' &&
        el.getClientRects().length > 0,
    );
  }
}
