import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyRecordsComponent } from './my-records';

describe('MyRecordsComponent', () => {
  let component: MyRecordsComponent;
  let fixture: ComponentFixture<MyRecordsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyRecordsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyRecordsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
