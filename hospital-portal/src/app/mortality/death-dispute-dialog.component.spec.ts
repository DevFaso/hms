import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { MortalityService } from '../services/mortality.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { expectEmptySubmitRefusal } from '../testing/form-refusal-behaviour';
import { DeathDisputeDialogComponent } from './death-dispute-dialog.component';

describe('DeathDisputeDialogComponent', () => {
  let fixture: ComponentFixture<DeathDisputeDialogComponent>;
  let spy: jasmine.SpyObj<MortalityService>;
  let confirm: jasmine.SpyObj<ConfirmService>;

  beforeEach(async () => {
    spy = jasmine.createSpyObj('MortalityService', ['disputeDeath']);
    spy.disputeDeath.and.returnValue(of({} as never));
    confirm = jasmine.createSpyObj('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(true);
    await TestBed.configureTestingModule({
      imports: [DeathDisputeDialogComponent, TranslateModule.forRoot()],
      providers: [
        { provide: MortalityService, useValue: spy },
        { provide: ConfirmService, useValue: confirm },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(DeathDisputeDialogComponent);
    fixture.componentRef.setInput('patientId', 'p1');
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => fixture.nativeElement.remove());

  it('an empty form marks the reason and both statements, and sends nothing', () => {
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="death-dispute-submit"]',
      sent: spy.disputeDeath,
    });
    expect(marked).toContain('dd-reason');
  });

  it('neither statement alone is enough', async () => {
    const c = fixture.componentInstance;
    c.reason = 'Seen alive in the emergency room';
    c.assessedAlive = true;
    await c.submit();
    c.assessedAlive = false;
    c.identityVerified = true;
    await c.submit();
    expect(spy.disputeDeath).not.toHaveBeenCalled();
  });

  it('sends the reason and both statements after the confirmation, and tells the chart', async () => {
    const c = fixture.componentInstance;
    const told = jasmine.createSpy('disputed');
    c.disputed.subscribe(told);
    c.reason = ' Seen alive ';
    c.assessedAlive = true;
    c.identityVerified = true;
    await c.submit();
    expect(confirm.ask).toHaveBeenCalled();
    expect(spy.disputeDeath).toHaveBeenCalledWith('p1', {
      reason: 'Seen alive',
      assessedAlive: true,
      identityVerified: true,
    });
    expect(told).toHaveBeenCalled();
  });

  it('sends nothing when the confirmation is declined', async () => {
    confirm.ask.and.resolveTo(false);
    const c = fixture.componentInstance;
    c.reason = 'Seen alive';
    c.assessedAlive = true;
    c.identityVerified = true;
    await c.submit();
    expect(spy.disputeDeath).not.toHaveBeenCalled();
  });
});
