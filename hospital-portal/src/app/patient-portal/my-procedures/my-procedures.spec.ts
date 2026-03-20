import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyProceduresComponent } from './my-procedures';

describe('MyProceduresComponent', () => {
  let component: MyProceduresComponent;
  let fixture: ComponentFixture<MyProceduresComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyProceduresComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyProceduresComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
