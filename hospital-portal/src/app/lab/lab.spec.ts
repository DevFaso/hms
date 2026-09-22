import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LabComponent } from './lab';
import { LabOrderResponse } from '../services/lab.service';

function order(status: string): LabOrderResponse {
  return { id: status.toLowerCase(), status } as LabOrderResponse;
}

/**
 * The worklist's Pending / Completed tabs against the backend lifecycle.
 *
 * The backend moves an order ORDERED → COLLECTED → RECEIVED on the specimen
 * events and RESULTED → COMPLETED on results (#716). The tabs used to know
 * only PENDING / IN_PROGRESS / ORDERED and COMPLETED / RESULTED, so an order
 * disappeared from both between specimen collection and result entry.
 */
describe('LabComponent worklist tabs', () => {
  let component: LabComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LabComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), provideRouter([])],
    });
    // No detectChanges: ngOnInit would fetch; the tabs are pure over orders().
    component = TestBed.createComponent(LabComponent).componentInstance;
  });

  const lifecycle = [
    'ORDERED',
    'PENDING',
    'COLLECTED',
    'RECEIVED',
    'IN_PROGRESS',
    'RESULTED',
    'VERIFIED',
    'COMPLETED',
    'CANCELLED',
  ];

  it('keeps an order on the Pending tab while its specimen is collected and received', () => {
    component.orders.set(lifecycle.map(order));

    component.setTab('pending');

    expect(component.filtered().map((o) => o.status)).toEqual([
      'ORDERED',
      'PENDING',
      'COLLECTED',
      'RECEIVED',
      'IN_PROGRESS',
    ]);
  });

  it('shows resulted, verified and completed orders on the Completed tab', () => {
    component.orders.set(lifecycle.map(order));

    component.setTab('completed');

    expect(component.filtered().map((o) => o.status)).toEqual([
      'RESULTED',
      'VERIFIED',
      'COMPLETED',
    ]);
  });

  it('places every lifecycle state on exactly one tab, cancelled on neither', () => {
    for (const status of lifecycle) {
      const o = order(status);
      const tabs = Number(component.isPending(o)) + Number(component.isCompleted(o));
      expect(tabs).toBe(status === 'CANCELLED' ? 0 : 1, status);
    }
  });

  it('badges COLLECTED and RECEIVED as specimen states and VERIFIED as done', () => {
    expect(component.getStatusClass('RECEIVED')).toBe('status-badge status-collected');
    expect(component.getStatusClass('COLLECTED')).toBe('status-badge status-collected');
    expect(component.getStatusClass('VERIFIED')).toBe('status-badge status-completed');
  });
});
