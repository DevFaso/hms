# coordination.md

**Full-Stack Changes:** Always update all impacted layers: database/schema, backend logic, API models, frontend calls, UI. Add tests for each layer.
**API Contracts:** Use explicit request/response models. Document any API changes and version them if needed.
**Migrations:** Prefer additive DB changes. Document migration steps, data transformations, and rollback plan.
