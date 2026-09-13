import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { RestrictedRowsComponent } from './restricted-rows.component';
import { RestrictedRows } from '../../services/patient.service';

/**
 * E9 #64 — "Dossier restreint (hôpital, département, n)" with "Ouvrir avec
 * motif". Only where and how many reach the screen; the action is the
 * host's to route into the break-glass declaration.
 */
describe('RestrictedRowsComponent', () => {
  let fixture: ComponentFixture<RestrictedRowsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RestrictedRowsComponent, TranslateModule.forRoot()],
    }).compileComponents();
    fixture = TestBed.createComponent(RestrictedRowsComponent);
  });

  function render(rows: RestrictedRows[]): void {
    fixture.componentRef.setInput('rows', rows);
    fixture.detectChanges();
  }

  function el(testId: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  }

  it('renders nothing for an empty list, so an in-hospital chart is unchanged', () => {
    render([]);
    expect(el('restricted-rows')).toBeNull();
  });

  it('renders one line per hospital and department, with the count and never a row', () => {
    render([
      { hospitalId: 'h-2', hospitalName: 'CHU Yalgado', departmentName: 'Psychiatrie', count: 2 },
      { hospitalId: 'h-2', hospitalName: 'CHU Yalgado', departmentName: null, count: 1 },
    ]);

    const lines = fixture.nativeElement.querySelectorAll('[data-testid="restricted-row"]');
    expect(lines.length).toBe(2);
    // TranslateModule.forRoot() with no loader echoes the key; the parameters
    // are what the component must hand over, and they are asserted through
    // the two keys it chooses between.
    expect(lines[0].textContent).toContain('RECORD_ACCESS.RESTRICTED_ROW_DEPT');
    expect(lines[1].textContent).toContain('RECORD_ACCESS.RESTRICTED_ROW');
    expect(lines[1].textContent).not.toContain('RESTRICTED_ROW_DEPT');
    expect(el('restricted-open')).not.toBeNull();
  });

  it('emits openWithReason when the clinician asks to open with a reason', () => {
    render([{ hospitalId: 'h-2', hospitalName: 'CHU Yalgado', count: 1 }]);
    const emitted = jasmine.createSpy('openWithReason');
    fixture.componentInstance.openWithReason.subscribe(emitted);

    el('restricted-open')!.click();

    expect(emitted).toHaveBeenCalledTimes(1);
  });
});
