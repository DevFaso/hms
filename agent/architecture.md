# architecture.md

**Backend:** Use layered architecture (Controller -> Service -> Repository -> DTO). Controllers thin; use Mapper for DTO mapping; validate inputs in controllers.
**Frontend (Angular):** Separate UI components from state/logic (use Services/NgRx). Reuse focused components; strong TypeScript types.
**Mobile (React Native):** Separate Screens, Components, Navigation, API services. Handle loading/empty/error states explicitly; design for unstable networks.
