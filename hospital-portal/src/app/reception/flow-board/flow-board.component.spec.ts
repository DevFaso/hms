import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { FlowBoardComponent } from './flow-board.component';
import { DragDropModule, CdkDragDrop } from '@angular/cdk/drag-drop';
import { ToastService } from '../../core/toast.service';
import { ReceptionQueueItem } from '../reception.service';

const makeItem = (overrides: Partial<ReceptionQueueItem> = {}): ReceptionQueueItem => ({
  patientId: 'p1',
  patientName: 'John',
  status: 'ARRIVED',
  waitMinutes: 0,
  hasInsuranceIssue: false,
  hasOutstandingBalance: false,
  appointmentId: 'a1',
  mrn: null,
  dateOfBirth: null,
  appointmentTime: '09:00',
  providerName: null,
  departmentName: null,
  appointmentReason: null,
  encounterId: 'e1',
  ...overrides,
});

const makeDropEvent = (
  previousData: ReceptionQueueItem[],
  containerData: ReceptionQueueItem[],
  previousIndex = 0,
  currentIndex = 0,
  sameContainer = false,
): CdkDragDrop<ReceptionQueueItem[]> => {
  const previous = { data: previousData } as any;
  const container = sameContainer ? previous : ({ data: containerData } as any);
  return {
    previousContainer: previous,
    container,
    previousIndex,
    currentIndex,
    item: {} as any,
    isPointerOverContainer: true,
    distance: { x: 0, y: 0 },
    dropPoint: { x: 0, y: 0 },
  } as CdkDragDrop<ReceptionQueueItem[]>;
};

describe('FlowBoardComponent', () => {
  let component: FlowBoardComponent;
  let fixture: ComponentFixture<FlowBoardComponent>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    toastSpy = jasmine.createSpyObj('ToastService', ['info', 'success', 'error']);

    await TestBed.configureTestingModule({
      imports: [FlowBoardComponent, DragDropModule, TranslateModule.forRoot()],
      providers: [{ provide: ToastService, useValue: toastSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(FlowBoardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty board when no input', () => {
    expect(component.localBoard.scheduled.length).toBe(0);
  });

  it('should populate localBoard on ngOnChanges', () => {
    const board = {
      scheduled: [makeItem({ status: 'SCHEDULED', appointmentId: 'a1' })],
      confirmed: [],
      arrived: [],
      inProgress: [],
      completed: [],
      noShow: [],
      walkIn: [],
    };
    component.board = board;
    component.ngOnChanges({
      board: {
        currentValue: board,
        previousValue: null,
        firstChange: true,
        isFirstChange: () => true,
      },
    });
    expect(component.localBoard.scheduled.length).toBe(1);
  });

  it('should have 7 columns', () => {
    expect(component.columns.length).toBe(7);
  });

  it('should emit patientClicked', () => {
    spyOn(component.patientClicked, 'emit');
    component.patientClicked.emit('p1');
    expect(component.patientClicked.emit).toHaveBeenCalledWith('p1');
  });

  describe('drop()', () => {
    it('should not mutate board when dropping item without encounterId', () => {
      const item = makeItem({ encounterId: null });
      const prev = [item];
      const dest: ReceptionQueueItem[] = [];
      const event = makeDropEvent(prev, dest);

      component.drop(event, 'arrived');

      expect(prev.length).toBe(1);
      expect(dest.length).toBe(0);
      expect(toastSpy.info).toHaveBeenCalledWith(
        'Cannot move this card — check the patient in first.',
      );
    });

    it('should not mutate board when dropping into unmappable column', () => {
      const item = makeItem();
      const prev = [item];
      const dest: ReceptionQueueItem[] = [];
      const event = makeDropEvent(prev, dest);

      // 'scheduled' has no colStatusMap entry
      component.drop(event, 'scheduled');

      expect(prev.length).toBe(1);
      expect(dest.length).toBe(0);
      expect(toastSpy.info).toHaveBeenCalled();
    });

    it('should transfer item and emit statusChanged on valid drop', () => {
      spyOn(component.statusChanged, 'emit');
      const item = makeItem();
      const prev = [item];
      const dest: ReceptionQueueItem[] = [];
      const event = makeDropEvent(prev, dest);

      component.drop(event, 'arrived');

      expect(prev.length).toBe(0);
      expect(dest.length).toBe(1);
      expect(component.statusChanged.emit).toHaveBeenCalledWith({
        encounterId: 'e1',
        newStatus: 'ARRIVED',
      });
      expect(toastSpy.info).not.toHaveBeenCalled();
    });

    it('should do nothing when dropping within the same container', () => {
      spyOn(component.statusChanged, 'emit');
      const item = makeItem();
      const prev = [item];
      const event = makeDropEvent(prev, prev, 0, 0, true);

      component.drop(event, 'arrived');

      expect(component.statusChanged.emit).not.toHaveBeenCalled();
    });
  });
});
