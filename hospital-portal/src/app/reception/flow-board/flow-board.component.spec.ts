import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { FlowBoardComponent } from './flow-board.component';
import { DragDropModule } from '@angular/cdk/drag-drop';

describe('FlowBoardComponent', () => {
  let component: FlowBoardComponent;
  let fixture: ComponentFixture<FlowBoardComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FlowBoardComponent, DragDropModule, TranslateModule.forRoot()],
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
      scheduled: [
        {
          patientId: 'p1',
          patientName: 'John',
          status: 'SCHEDULED',
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
        },
      ],
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
});
