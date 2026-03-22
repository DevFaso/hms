import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ChatComponent } from './chat';

describe('ChatComponent', () => {
  let component: ChatComponent;
  let fixture: ComponentFixture<ChatComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(ChatComponent);
    component = fixture.componentInstance;
    // Prevent ngOnInit from calling the real API
    spyOn(component, 'loadConversations').and.stub();
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('conversations starts empty', () => {
    expect(component.conversations()).toEqual([]);
  });

  it('pendingAttachment starts null', () => {
    expect(component.pendingAttachment()).toBeNull();
  });

  it('uploadingAttachment starts false', () => {
    expect(component.uploadingAttachment()).toBeFalse();
  });

  it('removeAttachment clears pendingAttachment', () => {
    component.pendingAttachment.set({
      url: 'https://example.com/file.pdf',
      fileName: 'file.pdf',
      contentType: 'application/pdf',
      sizeBytes: 1024,
    });
    component.removeAttachment();
    expect(component.pendingAttachment()).toBeNull();
  });

  it('isImageAttachment returns true for image/jpeg', () => {
    expect(component.isImageAttachment('image/jpeg')).toBeTrue();
  });

  it('isImageAttachment returns true for image/png', () => {
    expect(component.isImageAttachment('image/png')).toBeTrue();
  });

  it('isImageAttachment returns false for application/pdf', () => {
    expect(component.isImageAttachment('application/pdf')).toBeFalse();
  });

  it('isImageAttachment returns false for undefined', () => {
    expect(component.isImageAttachment(undefined)).toBeFalse();
  });

  it('showNewConversation starts false', () => {
    expect(component.showNewConversation()).toBeFalse();
  });

  it('openNewConversation sets showNewConversation to true', () => {
    component['loadUsers'] = jasmine.createSpy('loadUsers');
    component.openNewConversation();
    expect(component.showNewConversation()).toBeTrue();
  });

  it('closeNewConversation hides panel', () => {
    component.showNewConversation.set(true);
    component.closeNewConversation();
    expect(component.showNewConversation()).toBeFalse();
  });

  it('careTeamContacts starts empty', () => {
    expect(component.careTeamContacts()).toEqual([]);
  });

  it('newConvTab starts as care-team', () => {
    expect(component.newConvTab()).toBe('care-team');
  });

  it('loadingCareTeam starts false', () => {
    expect(component.loadingCareTeam()).toBeFalse();
  });

  it('startConversationWithContact adds conversation and selects it', () => {
    spyOn(component, 'selectConversation').and.stub();
    component.startConversationWithContact({
      userId: 'doc-1',
      displayName: 'Dr. Jane Smith',
      roleLabel: 'Primary Care Provider',
    });
    const convs = component.conversations();
    expect(convs.length).toBe(1);
    expect(convs[0].conversationUserId).toBe('doc-1');
    expect(convs[0].conversationUserName).toBe('Dr. Jane Smith');
    expect(component.selectConversation).toHaveBeenCalled();
  });
});
