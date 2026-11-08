# Pharmacy operations workspace

Seven existing pharmacy screens share a teal, responsive presentation with readable controls, table headers, keyboard focus and clear status colours. This is a presentation change: component services, clinical decisions, payment amounts, witness requirements, export scope and hospital permissions are unchanged.

| Screen | Presentation |
| --- | --- |
| Stock adjustment | Filter panel, count of loaded movements for the current filter, lot references and movement author |
| Controlled-drug register | Period/pharmacy filters, prominent opening/closing/current balances, full witness and prescriber columns |
| Dispensing | Clearly labelled current queue-page count, queue and recent dispensing cards, readable verification badges |
| Stock-out routing | Prescription / stock check / routing steps, existing scoped actions and search |
| Claims | Loaded-record count, desktop status chips, compact mobile filter, styled empty state and lifecycle actions |
| Checkout | Lookup/payment steps, responsive selection and payment forms, server-priced amount retained |
| Drug interactions | Global-scope notice, filtered result count, readable recommendations and existing curation actions |

Counts describe loaded or filtered records, never an inferred hospital-network total. No quantities with different units or currencies are summed. The shared hospital network workspace introduced in #1094 remains the global entry for gated modules; these screen changes do not add cross-hospital clinical aggregation.

Shared styles live in `hospital-portal/src/app/pharmacy/_operations-workspace.scss`. Each screen retains its existing styles and includes the workspace mixin last. New labels are translated in FR, EN and ES. Horizontally scrolling tables have named, keyboard-focusable regions. The receipt template and its print controls remain in place.

## Local review evidence

Preview gallery: `C:/dev/hms-qa/designs/pharmacy-operations/index.html`.

The gallery contains captures of the actual production Angular build using fictional `cdx-` records. The local `verify.cjs` harness intercepts API requests, blocks all non-GET requests and external services, and records calls and accessibility results in `browser-verification.json`. It covers all seven screens in FR/EN/ES, widths 1880/1024/390, claim filtering, a server-priced payment form, search with no matches and empty datasets. Screenshot fixtures are not clinical reference data.

Checks: full frontend coverage suite, coverage floors, script tests, ESLint, Prettier, regression guards, translation parity/referenced keys and production build. Backend implementation is untouched; no deployment or live operations are part of this change.
