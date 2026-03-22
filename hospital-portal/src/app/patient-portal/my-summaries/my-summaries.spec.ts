import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MySummariesComponent } from './my-summaries';

describe('MySummariesComponent', () => {
  let component: MySummariesComponent;
  let fixture: ComponentFixture<MySummariesComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MySummariesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MySummariesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
