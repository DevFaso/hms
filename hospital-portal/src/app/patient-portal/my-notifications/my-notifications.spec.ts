import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyNotificationsComponent } from './my-notifications';

describe('MyNotificationsComponent', () => {
  let component: MyNotificationsComponent;
  let fixture: ComponentFixture<MyNotificationsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyNotificationsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyNotificationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
