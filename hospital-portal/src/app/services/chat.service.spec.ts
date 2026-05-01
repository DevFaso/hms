import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { ChatService, ChatAttachment, ChatSendRequest } from './chat.service';

describe('ChatService', () => {
  let service: ChatService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ChatService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ChatService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a multipart upload to /files/chat-attachments for PHOTO with no duration', (done) => {
    const blob = new Blob(['fake'], { type: 'image/jpeg' });
    const expected: ChatAttachment = {
      id: 'a1',
      storageKey: '/uploads/chat-attachments/a1.jpg',
      publicUrl: 'https://hms/uploads/chat-attachments/a1.jpg',
      displayName: 'rash.jpg',
      contentType: 'image/jpeg',
      sizeBytes: 4,
      sha256: 'beef',
      kind: 'PHOTO',
    };
    service.uploadChatAttachment(blob, 'PHOTO', 'rash.jpg').subscribe((res) => {
      expect(res.kind).toBe('PHOTO');
      expect(res.storageKey).toBe(expected.storageKey);
      done();
    });
    const req = httpMock.expectOne('/files/chat-attachments');
    expect(req.request.method).toBe('POST');
    const form = req.request.body as FormData;
    expect(form.get('kind')).toBe('PHOTO');
    expect(form.get('durationSeconds')).toBeNull();
    expect(form.get('file')).toBeTruthy();
    req.flush(expected);
  });

  it('includes durationSeconds when uploading AUDIO', (done) => {
    const blob = new Blob(['x'], { type: 'audio/webm' });
    const expected: ChatAttachment = {
      storageKey: '/uploads/chat-attachments/v.webm',
      kind: 'AUDIO',
      durationSeconds: 12,
    };
    service.uploadChatAttachment(blob, 'AUDIO', 'voice.webm', 12).subscribe((res) => {
      expect(res.kind).toBe('AUDIO');
      expect(res.durationSeconds).toBe(12);
      done();
    });
    const req = httpMock.expectOne('/files/chat-attachments');
    const form = req.request.body as FormData;
    expect(form.get('kind')).toBe('AUDIO');
    expect(form.get('durationSeconds')).toBe('12');
    req.flush(expected);
  });

  it('rounds non-integer durationSeconds before submit', (done) => {
    const blob = new Blob(['x'], { type: 'audio/webm' });
    service.uploadChatAttachment(blob, 'AUDIO', 'voice.webm', 8.7).subscribe(() => done());
    const req = httpMock.expectOne('/files/chat-attachments');
    expect((req.request.body as FormData).get('durationSeconds')).toBe('9');
    req.flush({ storageKey: 'k', kind: 'AUDIO', durationSeconds: 9 });
  });

  it('omits durationSeconds for PHOTO even if passed', (done) => {
    const blob = new Blob(['x'], { type: 'image/png' });
    service.uploadChatAttachment(blob, 'PHOTO', 'x.png', 5).subscribe(() => done());
    const req = httpMock.expectOne('/files/chat-attachments');
    expect((req.request.body as FormData).get('durationSeconds')).toBeNull();
    req.flush({ storageKey: 'k', kind: 'PHOTO' });
  });

  it('forwards attachments on /chat/send when present', (done) => {
    const send: ChatSendRequest = {
      senderId: 's',
      recipientId: 'r',
      content: 'hi',
      attachments: [{ storageKey: 'k', kind: 'PHOTO' }],
    };
    service.sendMessage(send).subscribe((msg) => {
      expect(msg.id).toBe('m1');
      done();
    });
    const req = httpMock.expectOne('/chat/send');
    expect(req.request.body.attachments?.length).toBe(1);
    expect(req.request.body.attachments[0].kind).toBe('PHOTO');
    req.flush({
      id: 'm1',
      senderId: 's',
      senderName: '',
      recipientId: 'r',
      recipientName: '',
      content: 'hi',
      timestamp: '',
      read: false,
      attachments: send.attachments,
    });
  });

  it('omits attachments key entirely when caller passes none', (done) => {
    const send: ChatSendRequest = { senderId: 's', recipientId: 'r', content: 'hi' };
    service.sendMessage(send).subscribe(() => done());
    const req = httpMock.expectOne('/chat/send');
    expect('attachments' in req.request.body).toBe(false);
    req.flush({
      id: 'm2',
      senderId: 's',
      senderName: '',
      recipientId: 'r',
      recipientName: '',
      content: 'hi',
      timestamp: '',
      read: false,
    });
  });
});
