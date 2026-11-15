import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { OrganDamageSymptom } from '../organ-damage-symptoms';
import { OrganDamageSymptomsComponent } from './organ-damage-symptoms.component';

/** r54: the optional organ-damage checklist; never required, always in the server's order. */
describe('OrganDamageSymptomsComponent', () => {
  let fixture: ComponentFixture<OrganDamageSymptomsComponent>;
  let emitted: OrganDamageSymptom[][];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [OrganDamageSymptomsComponent, TranslateModule.forRoot()],
    });
    fixture = TestBed.createComponent(OrganDamageSymptomsComponent);
    emitted = [];
    fixture.componentInstance.selectedChange.subscribe((v) => emitted.push(v));
    fixture.detectChanges();
  });

  function box(symptom: OrganDamageSymptom): HTMLInputElement {
    return fixture.nativeElement.querySelector(`[data-testid="organ-damage-${symptom}"]`);
  }

  it('offers the five signs, none ticked, inside a labelled group', () => {
    const boxes = fixture.nativeElement.querySelectorAll('input[type="checkbox"]');
    expect(boxes.length).toBe(5);
    expect(Array.from(boxes as NodeListOf<HTMLInputElement>).some((b) => b.checked)).toBeFalse();
    expect(fixture.nativeElement.querySelector('fieldset legend')).toBeTruthy();
  });

  it('emits the ticked signs in the server order, and an empty list when unticked', () => {
    box('SEVERE_HEADACHE').click();
    fixture.componentRef.setInput('selected', emitted[0]);
    fixture.detectChanges();
    box('CHEST_PAIN').click();
    expect(emitted[1]).toEqual(['CHEST_PAIN', 'SEVERE_HEADACHE']);

    fixture.componentRef.setInput('selected', ['CHEST_PAIN']);
    fixture.detectChanges();
    box('CHEST_PAIN').click();
    expect(emitted[2]).toEqual([]);
  });
});
