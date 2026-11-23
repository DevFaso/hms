import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { provideRouter } from '@angular/router';
import { NotificationSettingsComponent } from './notification-settings';
import { NotificationService } from '../services/notification.service';
import { ToastService } from '../core/toast.service';

describe('NotificationSettingsComponent consent preservation', () => {
  let api: jasmine.SpyObj<NotificationService>;
  function render(failed = false) {
    api = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'getPreferences',
      'updatePreferences',
    ]);
    api.getPreferences.and.returnValue(failed ? throwError(() => new Error('offline')) : of([]));
    TestBed.configureTestingModule({
      imports: [NotificationSettingsComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: NotificationService, useValue: api },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    });
    const fixture = TestBed.createComponent(NotificationSettingsComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('sends only edited cells and adopts another recorder’s newer refusal from the response', () => {
    const fixture = render();
    const component = fixture.componentInstance;
    api.updatePreferences.and.returnValue(
      of([
        { notificationType: 'LAB_RESULT', channel: 'SMS', enabled: false },
        { notificationType: 'BILLING', channel: 'EMAIL', enabled: false },
      ]),
    );
    component.rows().find((row) => row.notificationType === 'LAB_RESULT')!.channels['SMS'] = false;
    component.markChanged();
    component.save();
    expect(api.updatePreferences).toHaveBeenCalledOnceWith([
      { notificationType: 'LAB_RESULT', channel: 'SMS', enabled: false },
    ]);
    expect(
      component.rows().find((row) => row.notificationType === 'BILLING')!.channels['EMAIL'],
    ).toBeFalse();
    expect(component.dirty()).toBeFalse();
  });

  it('does not fabricate an all-enabled matrix or accept writes when the read failed', () => {
    const fixture = render(true);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).toBeNull();
    fixture.componentInstance.save();
    expect(api.updatePreferences).not.toHaveBeenCalled();
  });

  it('recognizes a switch returned to its saved value as no change', () => {
    const fixture = render();
    const component = fixture.componentInstance;
    const row = component.rows().find((candidate) => candidate.notificationType === 'LAB_RESULT')!;
    row.channels['SMS'] = false;
    component.markChanged();
    expect(component.dirty()).toBeTrue();
    row.channels['SMS'] = true;
    component.markChanged();
    expect(component.dirty()).toBeFalse();
    component.save();
    expect(api.updatePreferences).not.toHaveBeenCalled();
  });
});
