import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FlowBoard, ReceptionQueueItem } from './reception.service';

@Component({
  selector: 'app-flow-board',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './flow-board.html',
  styleUrl: './flow-board.scss',
})
export class FlowBoardComponent {
  @Input() board: FlowBoard | null = null;
  @Output() patientClicked = new EventEmitter<string>();

  readonly columns: { key: keyof FlowBoard; label: string; colorClass: string }[] = [
    { key: 'scheduled', label: 'Scheduled', colorClass: 'col-scheduled' },
    { key: 'arrived', label: 'Arrived', colorClass: 'col-arrived' },
    { key: 'inProgress', label: 'In Progress', colorClass: 'col-in-progress' },
    { key: 'completed', label: 'Completed', colorClass: 'col-completed' },
    { key: 'noShow', label: 'No Show', colorClass: 'col-no-show' },
    { key: 'walkIn', label: 'Walk-In', colorClass: 'col-walk-in' },
  ];

  colItems(key: keyof FlowBoard): ReceptionQueueItem[] {
    return this.board ? (this.board[key] as ReceptionQueueItem[]) : [];
  }
}
