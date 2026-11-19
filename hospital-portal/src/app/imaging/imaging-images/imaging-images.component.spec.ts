import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpEvent, HttpEventType, HttpResponse } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of } from 'rxjs';

import { ImagingImagesComponent } from './imaging-images.component';
import { ImagingImagesService, ImagingStudyImage } from '../../services/imaging-images.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';

function image(id: string, patch: Partial<ImagingStudyImage> = {}): ImagingStudyImage {
  return {
    id,
    imagingOrderId: 'o1',
    imagingReportId: null,
    contentType: 'image/png',
    sizeBytes: 10,
    caption: null,
    uploadedAt: '2026-11-18T10:00:00',
    locked: false,
    deletable: true,
    ...patch,
  };
}

describe('ImagingImagesComponent (V316)', () => {
  let fixture: ComponentFixture<ImagingImagesComponent>;
  let component: ImagingImagesComponent;
  let service: jasmine.SpyObj<ImagingImagesService>;
  let confirm: jasmine.SpyObj<ConfirmService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj('ImagingImagesService', [
      'list',
      'upload',
      'content',
      'delete',
      'withdraw',
      'updateCaption',
      'withdrawn',
    ]);
    service.list.and.returnValue(of([]));
    service.withdrawn.and.returnValue(of([]));
    service.content.and.returnValue(of(new Blob([new Uint8Array([1])], { type: 'image/png' })));
    confirm = jasmine.createSpyObj('ConfirmService', ['ask']);

    await TestBed.configureTestingModule({
      imports: [ImagingImagesComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ImagingImagesService, useValue: service },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: ConfirmService, useValue: confirm },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ImagingImagesComponent);
    component = fixture.componentInstance;
  });

  function init(images: ImagingStudyImage[], canUpload = false): void {
    service.list.and.returnValue(of(images));
    fixture.componentRef.setInput('orderId', 'o1');
    fixture.componentRef.setInput('canUpload', canUpload);
    fixture.detectChanges();
  }

  it('fetches thumbnails as authenticated blobs, never through a URL', () => {
    init([image('a'), image('b', { contentType: 'application/pdf' })]);

    expect(service.list).toHaveBeenCalledWith('o1');
    expect(service.content).toHaveBeenCalledOnceWith('a');
    const imgs = fixture.nativeElement.querySelectorAll('.thumb-open img');
    expect(imgs.length).toBe(1);
    expect((imgs[0] as HTMLImageElement).src).toMatch(/^blob:/);
  });

  it('uses the caption as the alt text, else the position in the study', () => {
    init([image('a', { caption: 'Vue de face' }), image('b')]);

    expect(component.altOf(component.images()[0], 0)).toBe('Vue de face');
    expect(component.altOf(component.images()[1], 1)).toBe('IMAGING_IMAGES.IMAGE_N');
  });

  it('offers no upload control without the right to add images', () => {
    init([]);
    expect(fixture.nativeElement.querySelector('[data-testid="imaging-images-drop"]')).toBeNull();
  });

  it('refuses a file the server would refuse before sending it', () => {
    expect(ImagingImagesComponent.refusalOf(new File(['x'], 'scan.dcm'))).toBe(
      'IMAGING_IMAGES.ERROR_TYPE',
    );
    expect(ImagingImagesComponent.refusalOf(new File([], 'scan.png'))).toBe(
      'IMAGING_IMAGES.ERROR_EMPTY',
    );
    expect(ImagingImagesComponent.refusalOf(new File(['x'], 'scan.WEBP'))).toBeNull();
    expect(ImagingImagesComponent.refusalOf(new File(['x'], 'export.pdf'))).toBeNull();
  });

  it('uploads a dropped file to the named report, shows progress, then reloads', () => {
    init([], true);
    fixture.componentRef.setInput('reportId', 'r1');
    const events = new Subject<HttpEvent<ImagingStudyImage>>();
    service.upload.and.returnValue(events);
    const file = new File(['png'], 'thorax.png', { type: 'image/png' });

    component.onDrop({
      preventDefault: () => undefined,
      dataTransfer: { files: [file] },
    } as unknown as DragEvent);
    expect(service.upload).withContext('staged until « Envoyer »').not.toHaveBeenCalled();
    component.setStagedCaption(component.staged()[0].key, 'Vue de face');
    component.sendStaged();

    expect(service.upload).toHaveBeenCalledWith('o1', file, 'r1', 'Vue de face');
    events.next({ type: HttpEventType.UploadProgress, loaded: 5, total: 10 });
    expect(component.uploads()[0].progress).toBe(50);
    events.next(new HttpResponse({ body: image('n') }));
    expect(component.uploads().length).toBe(0);
    expect(service.list).toHaveBeenCalledTimes(2);
  });

  it('removes an image only after the confirmation', async () => {
    init([image('a')]);
    service.delete.and.returnValue(of(undefined));

    confirm.ask.and.resolveTo(false);
    await component.remove(component.images()[0]);
    expect(service.delete).not.toHaveBeenCalled();

    confirm.ask.and.resolveTo(true);
    await component.remove(component.images()[0]);
    expect(service.delete).toHaveBeenCalledWith('a');
  });

  it('hides the remove button on a locked image', () => {
    init([image('a', { locked: true, deletable: false })]);
    expect(fixture.nativeElement.querySelector('.thumb-delete')).toBeNull();
    expect(fixture.nativeElement.querySelector('.thumb-lock')).not.toBeNull();
  });

  it('the viewer steps, zooms and pans from the keyboard', () => {
    init([image('a'), image('b')]);
    component.openViewer(0);
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[data-testid="imaging-images-viewer"]'),
    ).not.toBeNull();

    const key = (k: string) =>
      component.onViewerKey(new KeyboardEvent('keydown', { key: k, cancelable: true }));
    key('ArrowRight');
    expect(component.viewerIndex()).toBe(1);
    key('ArrowRight');
    expect(component.viewerIndex()).toBe(0);
    key('+');
    expect(component.zoom()).toBe(1.5);
    key('ArrowLeft');
    expect(component.viewerIndex()).withContext('pans when zoomed').toBe(0);
    expect(component.panX()).toBe(40);
    key('0');
    expect(component.zoom()).toBe(1);
    expect(component.panX()).toBe(0);

    component.closeViewer();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="imaging-images-viewer"]')).toBeNull();
  });

  it('opens a PDF inline', () => {
    service.content.and.returnValue(of(new Blob(['%PDF-'], { type: 'application/pdf' })));
    init([image('p', { contentType: 'application/pdf' })]);
    component.openViewer(0);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.viewer-stage iframe')).not.toBeNull();
  });

  it('QA D2466: each file of a batch is sent with its own caption', () => {
    init([], true);
    service.upload.and.returnValue(new Subject<HttpEvent<ImagingStudyImage>>());
    const a = new File(['a'], 'face.png', { type: 'image/png' });
    const b = new File(['b'], 'profil.png', { type: 'image/png' });
    component.onDrop({
      preventDefault: () => undefined,
      dataTransfer: { files: [a, b] },
    } as unknown as DragEvent);
    const [first, second] = component.staged();
    component.setStagedCaption(first.key, 'Face');
    component.setStagedCaption(second.key, 'Profil');
    component.sendStaged();

    expect(service.upload.calls.allArgs()).toEqual([
      ['o1', a, null, 'Face'],
      ['o1', b, null, 'Profil'],
    ]);
    expect(component.staged()).toEqual([]);
  });

  it('QA D2465: an oversize file is refused on screen at once, with the limit in the reader units', () => {
    init([], true);
    const big = new File([new Uint8Array(1)], 'big.png', { type: 'image/png' });
    Object.defineProperty(big, 'size', { value: 11 * 1024 * 1024 });
    component.onDrop({
      preventDefault: () => undefined,
      dataTransfer: { files: [big] },
    } as unknown as DragEvent);
    expect(component.staged()).toEqual([]);
    expect(component.uploads()[0].error).toBeTruthy();
    expect(service.upload).not.toHaveBeenCalled();
  });

  it('QA D2466: the caption is corrected from the viewer', () => {
    init([image('a', { caption: 'Coupe longitudinal', captionEditable: true })]);
    service.updateCaption.and.returnValue(
      of(image('a', { caption: 'Coupe longitudinale', captionEditable: true })),
    );
    component.openViewer(0);
    fixture.detectChanges();
    expect(component.captionDraft()).toBe('Coupe longitudinal');
    component.captionDraft.set('Coupe longitudinale ');
    component.saveCaption(component.images()[0]);

    expect(service.updateCaption).toHaveBeenCalledWith('a', 'Coupe longitudinale');
    expect(component.images()[0].caption).toBe('Coupe longitudinale');
  });

  it('QA D2464: a withdrawable image asks for a reason, then is withdrawn and reloaded', () => {
    init([image('a', { locked: true, deletable: false, withdrawable: true })]);
    service.withdraw.and.returnValue(of(undefined));
    (
      fixture.nativeElement.querySelector('[data-testid="imaging-image-withdraw-a"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="reason-dialog"]')).not.toBeNull();

    component.confirmWithdraw('Mauvais patient');

    expect(service.withdraw).toHaveBeenCalledWith('a', 'Mauvais patient');
    expect(component.withdrawing()).toBeNull();
    expect(service.list).toHaveBeenCalledTimes(2);
  });

  it('QA D2464: a locked image the caller may not withdraw offers no withdrawal', () => {
    init([image('a', { locked: true, deletable: false, withdrawable: false })]);
    expect(
      fixture.nativeElement.querySelector('[data-testid="imaging-image-withdraw-a"]'),
    ).toBeNull();
  });

  it('QA D2464: the withdrawn images stay in the record with their reason', () => {
    service.withdrawn.and.returnValue(
      of([
        {
          id: 'w',
          imagingOrderId: 'o1',
          withdrawnAt: '2026-11-19T10:00:00',
          withdrawalReason: 'Mauvais patient',
        },
      ]),
    );
    init([image('a')]);

    const section = fixture.nativeElement.querySelector(
      '[data-testid="imaging-images-withdrawn"]',
    ) as HTMLElement;
    expect(section.textContent).toContain('Mauvais patient');
    expect(fixture.nativeElement.querySelectorAll('.thumb').length).toBe(1);
  });
});
