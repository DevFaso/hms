import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnChanges,
  SimpleChanges,
  inject,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { CdkDragDrop, DragDropModule, transferArrayItem } from '@angular/cdk/drag-drop';
import { FlowBoard, ReceptionQueueItem } from '../reception.service';
import { ToastService } from '../../core/toast.service';

export interface FlowBoardStatusChange {
  encounterId: string;
  newStatus: string;
}

/** QA D788: a card nobody has seen, dropped on "completed" or "no-show". */
export interface FlowBoardCloseUnseen {
  encounterId: string;
  patientName: string | null;
  outcome: 'LEFT_WITHOUT_BEING_SEEN' | 'CANCELLED';
}

/** Board statuses of a visit no clinician has seen yet (ARRIVED covers triage and waiting). */
const NEVER_SEEN_STATUSES = new Set(['ARRIVED', 'WALK_IN', 'CHECKED_IN']);

type ColKey = keyof FlowBoard;

@Component({
  selector: 'app-flow-board',
  standalone: true,
  imports: [CommonModule, DragDropModule, TranslateModule],
  templateUrl: './flow-board.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './flow-board.component.scss',
})
export class FlowBoardComponent implements OnChanges {
  @Input() board: FlowBoard | null = null;
  @Output() patientClicked = new EventEmitter<string>();
  @Output() statusChanged = new EventEmitter<FlowBoardStatusChange>();
  @Output() closeUnseenRequested = new EventEmitter<FlowBoardCloseUnseen>();

  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

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
    this.applyMove(
      event.previousContainer.data,
      event.container.data,
      event.previousIndex,
      event.currentIndex,
      targetKey,
    );
  }

  /** Whether the card is a visit nobody has seen, which the desk may close as left unseen. */
  canCloseUnseen(item: ReceptionQueueItem): boolean {
    return !!item.encounterId && NEVER_SEEN_STATUSES.has(item.status);
  }

  /**
   * QA D1023 (WCAG 2.1.1): the keyboard and button path to what a drop on
   * "completed" or "no-show" does for a visit nobody has seen. The close-unseen
   * dialog opened only on a mouse drag.
   */
  requestCloseUnseen(item: ReceptionQueueItem, event?: Event): void {
    event?.stopPropagation();
    if (!this.canCloseUnseen(item)) return;
    this.closeUnseenRequested.emit({
      encounterId: item.encounterId!,
      patientName: item.patientName ?? null,
      outcome: 'LEFT_WITHOUT_BEING_SEEN',
    });
  }

  /**
   * QA D1023 (WCAG 2.1.1): Alt+Right / Alt+Left on a focused card moves it to
   * the next or previous column, through exactly the rules of a drop.
   */
  moveCard(item: ReceptionQueueItem, fromKey: ColKey, step: 1 | -1, event?: Event): void {
    event?.preventDefault();
    const at = this.columns.findIndex((c) => c.key === fromKey);
    const target = this.columns[at + step];
    if (!target) return;
    const from = this.localBoard[fromKey];
    const index = from.indexOf(item);
    if (index < 0) return;
    const to = this.localBoard[target.key];
    this.applyMove(from, to, index, to.length, target.key);
  }

  private applyMove(
    from: ReceptionQueueItem[],
    to: ReceptionQueueItem[],
    fromIndex: number,
    toIndex: number,
    targetKey: ColKey,
  ): void {
    const draggedItem = from[fromIndex];
    const newStatus = this.colStatusMap[targetKey];

    // QA D788: a visit nobody has seen is never "completed": the desk closes it
    // as left without being seen (or cancelled), with a reason, in a dialog.
    if (
      (targetKey === 'completed' || targetKey === 'noShow') &&
      draggedItem?.encounterId &&
      NEVER_SEEN_STATUSES.has(draggedItem.status)
    ) {
      this.closeUnseenRequested.emit({
        encounterId: draggedItem.encounterId,
        patientName: draggedItem.patientName ?? null,
        outcome: 'LEFT_WITHOUT_BEING_SEEN',
      });
      return;
    }
    // A seen visit is not a no-show: there is no such encounter status.
    if (targetKey === 'noShow') {
      this.toast.info(this.translate.instant('RECEPTION.CANNOT_MOVE_CARD'));
      return;
    }

    if (!newStatus || !draggedItem?.encounterId) {
      this.toast.info(this.translate.instant('RECEPTION.CANNOT_MOVE_CARD'));
      return;
    }

    transferArrayItem(from, to, fromIndex, toIndex);

    this.statusChanged.emit({ encounterId: draggedItem.encounterId, newStatus });
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
