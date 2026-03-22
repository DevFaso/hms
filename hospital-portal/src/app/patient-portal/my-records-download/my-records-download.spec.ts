import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyRecordsDownloadComponent } from './my-records-download';

describe('MyRecordsDownloadComponent', () => {
  let component: MyRecordsDownloadComponent;
  let fixture: ComponentFixture<MyRecordsDownloadComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyRecordsDownloadComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyRecordsDownloadComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should start with no active download', () => {
    expect(component.downloading()).toBeNull();
  });

  it('should start with no messages', () => {
    expect(component.successMsg()).toBeNull();
    expect(component.errorMsg()).toBeNull();
  });
});
