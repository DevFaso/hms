import { Directive, EventEmitter, HostListener, Output } from '@angular/core';

/**
 * QA D1451: a click on the backdrop itself closes the dialog, and a click inside it does nothing.
 *
 *   <div class="modal-backdrop" appBackdropClose (backdropClose)="close()">
 *
 * Never write `(click)="$event.target === $event.currentTarget && close()"`. A click inside the
 * dialog bubbles here, that expression answers `false`, Angular cancels the event, and every
 * checkbox and link inside stops working. This handler returns nothing, so nothing is cancelled.
 * `npm run guards` (check-event-cancel.mjs) refuses the idiom.
 */
@Directive({
  selector: '[appBackdropClose]',
  standalone: true,
})
export class BackdropCloseDirective {
  @Output() readonly backdropClose = new EventEmitter<void>();

  @HostListener('click', ['$event'])
  onClick(event: Event): void {
    if (event.target === event.currentTarget) this.backdropClose.emit();
  }
}
