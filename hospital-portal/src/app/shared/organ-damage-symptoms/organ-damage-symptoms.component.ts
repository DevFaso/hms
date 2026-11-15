import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { ORGAN_DAMAGE_SYMPTOMS, OrganDamageSymptom } from '../organ-damage-symptoms';

/**
 * r54 (provisional, pending the clinical lead's approval): the optional checklist of organ-damage
 * signs recorded with a blood pressure. The server's shared severe-blood-pressure rule reads it: one
 * ticked sign turns an adult's severe-range warning URGENT. The portal holds no threshold and never
 * requires a tick; the set saves either way.
 */
@Component({
  selector: 'app-organ-damage-symptoms',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <fieldset class="organ-damage" data-testid="organ-damage-symptoms">
      <legend>{{ 'ORGAN_DAMAGE.LEGEND' | translate }}</legend>
      @for (symptom of symptoms; track symptom) {
        <label class="organ-damage-option">
          <input
            type="checkbox"
            [attr.data-testid]="'organ-damage-' + symptom"
            [checked]="selected().includes(symptom)"
            (change)="toggle(symptom, $any($event.target).checked)"
          />
          {{ 'ORGAN_DAMAGE.' + symptom | translate }}
        </label>
      }
    </fieldset>
  `,
  styles: [
    `
      .organ-damage {
        border: 1px solid var(--border-color, #d0d7de);
        border-radius: 6px;
        padding: 0.5rem 0.75rem;
        margin: 0;
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem 1rem;
      }
      .organ-damage legend {
        font-weight: 600;
        padding: 0 0.25rem;
      }
      .organ-damage-option {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        font-weight: normal;
      }
    `,
  ],
})
export class OrganDamageSymptomsComponent {
  readonly symptoms = ORGAN_DAMAGE_SYMPTOMS;
  readonly selected = input<readonly OrganDamageSymptom[]>([]);
  readonly selectedChange = output<OrganDamageSymptom[]>();

  toggle(symptom: OrganDamageSymptom, checked: boolean): void {
    const rest = this.selected().filter((s) => s !== symptom);
    // Kept in the server's order, so the same ticks always read the same way.
    const next = checked ? [...rest, symptom] : rest;
    this.selectedChange.emit(ORGAN_DAMAGE_SYMPTOMS.filter((s) => next.includes(s)));
  }
}
