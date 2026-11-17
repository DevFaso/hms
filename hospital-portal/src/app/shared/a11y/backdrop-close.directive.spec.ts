import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';

import { BackdropCloseDirective } from './backdrop-close.directive';

@Component({
  standalone: true,
  imports: [BackdropCloseDirective, FormsModule],
  template: `
    <div id="backdrop" appBackdropClose (backdropClose)="closed = closed + 1">
      <div id="box">
        <label id="label"><input id="check" type="checkbox" [(ngModel)]="ticked" /> Tick</label>
        <a id="link" href="#inside">Link</a>
      </div>
    </div>
  `,
})
class HostComponent {
  closed = 0;
  ticked = false;
}

describe('BackdropCloseDirective (QA D1451)', () => {
  const start = () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    return { fixture, root, q: <T extends HTMLElement>(id: string) => root.querySelector<T>(id)! };
  };

  it('closes on a click of the backdrop itself', () => {
    const { fixture, q } = start();
    q('#backdrop').click();
    expect(fixture.componentInstance.closed).toBe(1);
  });

  it('does not close on a click inside, and does not cancel it', () => {
    const { fixture, q } = start();
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    q('#box').dispatchEvent(click);
    expect(click.defaultPrevented).toBeFalse();
    expect(fixture.componentInstance.closed).toBe(0);
  });

  it('leaves a checkbox inside working, by the box and by its label', async () => {
    const { fixture, q } = start();
    await fixture.whenStable();
    q<HTMLInputElement>('#check').click();
    fixture.detectChanges();
    expect(q<HTMLInputElement>('#check').checked).toBeTrue();
    expect(fixture.componentInstance.ticked).toBeTrue();
    q('#label').click();
    fixture.detectChanges();
    expect(q<HTMLInputElement>('#check').checked).toBeFalse();
    expect(fixture.componentInstance.ticked).toBeFalse();
  });

  it('leaves a link inside followable: its click is not cancelled', () => {
    const { q } = start();
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    q('#link').addEventListener('click', (event) => {
      expect(event.defaultPrevented).toBeFalse();
      event.preventDefault();
    });
    q('#link').dispatchEvent(click);
  });
});
