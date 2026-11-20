import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { MaternalConcern } from '../maternal-concerns';
import { MaternalConcernsComponent } from './maternal-concerns.component';

/** Policy 2026-11-20 (provisional): the optional tick list of the maternal score's additional concerns. */
describe('MaternalConcernsComponent', () => {
  let fixture: ComponentFixture<MaternalConcernsComponent>;
  let emitted: MaternalConcern[][];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MaternalConcernsComponent, TranslateModule.forRoot()],
    });
    fixture = TestBed.createComponent(MaternalConcernsComponent);
    emitted = [];
    fixture.componentInstance.selectedChange.subscribe((v) => emitted.push(v));
    fixture.detectChanges();
  });

  function box(concern: MaternalConcern): HTMLInputElement {
    return fixture.nativeElement.querySelector(`[data-testid="maternal-concern-${concern}"]`);
  }

  it('offers the six concerns, none ticked, in a labelled group that is folded until one is ticked', () => {
    const boxes = fixture.nativeElement.querySelectorAll('input[type="checkbox"]');
    expect(boxes.length).toBe(6);
    expect(Array.from(boxes as NodeListOf<HTMLInputElement>).some((b) => b.checked)).toBeFalse();
    expect(fixture.nativeElement.querySelector('fieldset legend')).toBeTruthy();
    const details: HTMLDetailsElement = fixture.nativeElement.querySelector('details');
    expect(details.open).toBeFalse();

    fixture.componentRef.setInput('selected', ['VAGINAL_BLEEDING']);
    fixture.detectChanges();
    expect(details.open).toBeTrue();
    expect(box('VAGINAL_BLEEDING').checked).toBeTrue();
  });

  it('emits the ticked concerns in the server order, and an empty list when unticked', () => {
    box('REDUCED_URINE_OUTPUT').click();
    fixture.componentRef.setInput('selected', emitted[0]);
    fixture.detectChanges();
    box('STAFF_CONCERNED').click();
    expect(emitted[1]).toEqual(['STAFF_CONCERNED', 'REDUCED_URINE_OUTPUT']);

    fixture.componentRef.setInput('selected', ['STAFF_CONCERNED']);
    fixture.detectChanges();
    box('STAFF_CONCERNED').click();
    expect(emitted[2]).toEqual([]);
  });

  it('never overflows its container, unfolded, at 1366, 1024, 768 and 390 px', () => {
    fixture.componentRef.setInput('selected', ['STAFF_CONCERNED']);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    host.style.display = 'block';
    const details: HTMLElement = host.querySelector('details')!;
    for (const width of [1366, 1024, 768, 390]) {
      // The dialog's side padding leaves less than the viewport: the narrowest case is well under 390.
      host.style.width = `${width - 48}px`;
      expect(details.getBoundingClientRect().width)
        .withContext(`${width}px: the group fits its container`)
        .toBeLessThanOrEqual(host.getBoundingClientRect().width + 1);
      expect(details.scrollWidth)
        .withContext(`${width}px: nothing inside is cut off`)
        .toBeLessThanOrEqual(details.clientWidth + 1);
    }
  });
});
