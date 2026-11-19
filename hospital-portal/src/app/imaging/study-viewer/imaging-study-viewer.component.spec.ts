import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ImagingStudyViewerComponent } from './imaging-study-viewer.component';
import {
  ImagingPacsService,
  ImagingStudyViewer,
  ImagingViewerStatus,
} from '../../services/imaging-pacs.service';

describe('ImagingStudyViewerComponent', () => {
  let fixture: ComponentFixture<ImagingStudyViewerComponent>;
  let pacs: jasmine.SpyObj<ImagingPacsService>;

  const manifest = (status: ImagingViewerStatus, count = 0): ImagingStudyViewer => ({
    reportId: 'r1',
    status,
    linkedBy: count > 0 ? 'STUDY_UID' : null,
    truncated: false,
    instances: Array.from({ length: count }, (_, i) => ({
      seriesInstanceUid: '1.2.3.1',
      sopInstanceUid: `1.2.3.1.${i + 1}`,
      seriesNumber: 1,
      instanceNumber: i + 1,
      numberOfFrames: i === 0 ? 2 : 1,
      modality: 'CR',
    })),
  });

  beforeEach(async () => {
    pacs = jasmine.createSpyObj<ImagingPacsService>('ImagingPacsService', [
      'viewer',
      'frame',
      'viewerReady',
    ]);
    pacs.viewerReady.and.returnValue(of({ ready: true }));
    pacs.frame.and.returnValue(
      of(new Blob([new Uint8Array([0xff, 0xd8])], { type: 'image/jpeg' })),
    );
    spyOn(URL, 'createObjectURL').and.returnValue('blob:frame');
    spyOn(URL, 'revokeObjectURL');
    await TestBed.configureTestingModule({
      imports: [ImagingStudyViewerComponent, TranslateModule.forRoot()],
      providers: [{ provide: ImagingPacsService, useValue: pacs }],
    }).compileComponents();
    fixture = TestBed.createComponent(ImagingStudyViewerComponent);
    fixture.componentRef.setInput('reportId', 'r1');
    fixture.componentRef.setInput('studyLinked', true);
    fixture.detectChanges();
  });

  const el = (id: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(`[data-testid="${id}"]`);

  it('loads nothing until asked', () => {
    expect(pacs.viewer).not.toHaveBeenCalled();
    expect(el('imaging-viewer-linked')).not.toBeNull();
  });

  it('shows « Images disponibles » and the first frame through the report-scoped proxy', () => {
    pacs.viewer.and.returnValue(of(manifest('AVAILABLE', 3)));
    el('imaging-viewer-open')!.click();
    fixture.detectChanges();

    expect(pacs.viewer).toHaveBeenCalledWith('r1');
    expect(el('imaging-viewer-available')?.textContent).toContain('IMAGING_PACS.IMAGES_AVAILABLE');
    expect(pacs.frame).toHaveBeenCalledWith(
      'r1',
      jasmine.objectContaining({ sopInstanceUid: '1.2.3.1.1' }),
      1,
    );
    expect((el('imaging-viewer-image') as HTMLImageElement).src).toContain('blob:frame');
  });

  it('pages through images and frames, revoking each replaced object URL', () => {
    pacs.viewer.and.returnValue(of(manifest('AVAILABLE', 3)));
    el('imaging-viewer-open')!.click();
    fixture.detectChanges();
    const cmp = fixture.componentInstance;

    cmp.goFrame(1);
    expect(pacs.frame).toHaveBeenCalledWith(
      'r1',
      jasmine.objectContaining({ sopInstanceUid: '1.2.3.1.1' }),
      2,
    );
    cmp.go(1);
    expect(pacs.frame).toHaveBeenCalledWith(
      'r1',
      jasmine.objectContaining({ sopInstanceUid: '1.2.3.1.2' }),
      1,
    );
    cmp.go(5);
    expect(cmp.index()).toBe(2);
    expect(URL.revokeObjectURL).toHaveBeenCalled();
  });

  for (const status of [
    'PROXY_DISABLED',
    'PACS_NOT_CONFIGURED',
    'NO_STUDY_LINKED',
    'STUDY_NOT_FOUND',
    'ACCESSION_AMBIGUOUS',
    'PACS_UNREACHABLE',
  ] as const) {
    it(`words the ${status} state and fetches no frame`, () => {
      pacs.viewer.and.returnValue(of(manifest(status)));
      el('imaging-viewer-open')!.click();
      fixture.detectChanges();
      expect(el('imaging-viewer-status')?.textContent).toContain(`IMAGING_PACS.STATUS.${status}`);
      expect(pacs.frame).not.toHaveBeenCalled();
    });
  }

  it('says when the request itself failed', () => {
    pacs.viewer.and.returnValue(throwError(() => new Error('down')));
    el('imaging-viewer-open')!.click();
    fixture.detectChanges();
    expect(el('imaging-viewer-status')?.textContent).toContain('IMAGING_PACS.STATUS.ERROR');
  });

  it('says when one frame could not be loaded', () => {
    pacs.viewer.and.returnValue(of(manifest('AVAILABLE', 1)));
    pacs.frame.and.returnValue(throwError(() => new Error('502')));
    el('imaging-viewer-open')!.click();
    fixture.detectChanges();
    expect(el('imaging-viewer-image-error')).not.toBeNull();
  });

  it('QA D2467: shows nothing at all when the hospital has no PACS connected', () => {
    pacs.viewerReady.and.returnValue(of({ ready: false }));
    fixture = TestBed.createComponent(ImagingStudyViewerComponent);
    fixture.componentRef.setInput('reportId', 'r2');
    fixture.detectChanges();

    expect(pacs.viewerReady).toHaveBeenCalledWith('r2');
    expect(fixture.nativeElement.querySelector('[data-testid="imaging-viewer-open"]')).toBeNull();
    expect(fixture.nativeElement.textContent.trim()).toBe('');
    expect(pacs.viewer).not.toHaveBeenCalled();
  });
});
