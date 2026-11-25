import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { NurseTaskService } from '../../services/nurse-task.service';
import { WitnessPickerComponent } from './witness-picker.component';

describe('witness picker native design', () => {
  it('skins the existing select and preserves its original selected-user output', async () => {
    TestBed.configureTestingModule({
      imports: [WitnessPickerComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: NurseTaskService,
          useValue: {
            getAdministrationWitnessCandidates: () =>
              of([{ userId: 'cdx-witness', displayName: 'cdx Witness' }]),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(WitnessPickerComponent);
    const emitted: (string | null)[] = [];
    fixture.componentInstance.witnessChange.subscribe((value) => emitted.push(value));
    fixture.detectChanges();
    await fixture.whenStable();
    const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    expect(select.classList.contains('keneya-form-control')).toBeTrue();
    expect(select.id).toBe('witness-select');
    expect(select.getAttribute('aria-describedby')).toBe('witness-select-hint');
    select.value = 'cdx-witness';
    select.dispatchEvent(new Event('change'));
    select.value = '';
    select.dispatchEvent(new Event('change'));
    expect(emitted).toEqual(['cdx-witness', null]);
  });
});
