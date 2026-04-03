import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { CdkDragDrop, DragDropModule, transferArrayItem } from '@angular/cdk/drag-drop';
import { FlowBoard, ReceptionQueueItem } from '../reception.service';

export interface FlowBoardStatusChange {
  encounterId: string;
  newStatus: string;
}

type ColKey = keyof FlowBoard;

@Component({
  selector: 'app-flow-board',
  standalone: true,
  imports: [CommonModule, DragDropModule, TranslateModule],
  templateUrl: './flow-board.component.html',
  styleUrl: './flow-board.component.scss',
})
export class FlowBoardComponent implements OnChanges {
  @Input() board: FlowBoard | null = null;
  @Output() patientClicked = new EventEmitter<string>();
  @Output() statusChanged = new EventEmitter<FlowBoardStatusChange>();

  readonly columns: { key: ColKey; labelKey: string; colorClass: string }[] = [
    { key: 'scheduled', labelKey: 'RECEPTION.SCHEDULED', colorClass: 'col-scheduled' },
    { key: 'confirmed', labelKey: 'RECEPTION.CONFIRMED', colorClass: 'col-confirmed' },
    { key: 'arrived', labelKey: 'RECEPTION.ARRIVED', colorClass: 'col-arrived' },
    { key: 'inProgress', labelKey: 'RECEPTION.IN_PROGRESS', colorClass: 'col-in-progress' },
    { key: 'completed', labelKey: 'RECEPTION.COMPLETED', colorClass: 'col-completed' },
    { key: 'noShow', labelKey: 'RECEPTION.NO_SHOW', colorClass: 'col-no-show' },
    { key: 'walkIn', labelKey: 'RECEPTION.WALKIN', colorClass: 'col-walk-in' },
  ];

  /** Mutable local copy so CDK can splice arrays */
  localBoard: Record<ColKey, ReceptionQueueItem[]> = this.emptyBoard();

  /** All drop-list IDs, so every list is connected to every other */
  readonly listIds: string[] = this.columns.map((c) => 'list-' + c.key);

  /** Status emitted when dropping into a column */
  private readonly colStatusMap: Partial<Record<ColKey, string>> = {
    arrived: 'ARRIVED',
    inProgress: 'IN_PROGRESS',
    noShow: 'NO_SHOW',
    completed: 'COMPLETED',
  };

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['board'] && this.board) {
      this.localBoard = {
        scheduled: [...(this.board.scheduled ?? [])],
        confirmed: [...(this.board.confirmed ?? [])],
        arrived: [...(this.board.arrived ?? [])],
        inProgress: [...(this.board.inProgress ?? [])],
        completed: [...(this.board.completed ?? [])],
        noShow: [...(this.board.noShow ?? [])],
        walkIn: [...(this.board.walkIn ?? [])],
      };
    }
  }

  drop(event: CdkDragDrop<ReceptionQueueItem[]>, targetKey: ColKey): void {
    if (event.previousContainer === event.container) return;

    transferArrayItem(
      event.previousContainer.data,
      event.container.data,
      event.previousIndex,
      event.currentIndex,
    );

    const item = event.container.data[event.currentIndex];
    const newStatus = this.colStatusMap[targetKey];

    if (newStatus && item.encounterId) {
      this.statusChanged.emit({ encounterId: item.encounterId, newStatus });
    }
  }

  listId(key: ColKey): string {
    return 'list-' + key;
  }

  private emptyBoard(): Record<ColKey, ReceptionQueueItem[]> {
    return {
      scheduled: [],
      confirmed: [],
      arrived: [],
      inProgress: [],
      completed: [],
      noShow: [],
      walkIn: [],
    };
  }
}
