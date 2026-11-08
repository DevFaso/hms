# Laboratory results: compact list and evolution

The results list now displays ten rows initially, with 10/25/50 rows per page, previous/next controls, page count and the visible result range. Searching, changing tabs and changing page size reset the page. All existing result actions remain available in a single horizontal group; the table scrolls horizontally on smaller screens instead of expanding rows vertically.

Opening a result requests its detail through the existing authorized GET endpoint. Its patient/test history appears above reference ranges in a wider detail panel. The chart uses elapsed time on the horizontal axis and exact numeric observations on the vertical axis. Units are separate selectable series, with the selected result's unit preferred. Comparator, qualitative, undated and unknown-unit observations are retained in the observations table but excluded from plotting. Corrections retain their badges and distinct chart markers. Historical versions may be present in laboratory history; the explanatory text makes this explicit. No reference band, threshold or clinical interpretation is invented.

History loading and failure are distinct from insufficient history. Late responses are ignored after closing or switching patient details; a hospital scope change closes the detail. A mismatched detail response is rejected.

This is frontend pagination over the existing all-pages loader, not new server pagination. No backend, authorization, clinical rule, release, signing or correction policy changes are included.

Validation uses synthetic data and intercepted local APIs only. Screenshots and the local preview gallery are in `C:/dev/hms-qa/designs/lab-results-evolution/`. Browser checks cover French, English and Spanish, desktop/tablet/mobile, pagination, filtering, mixed units, correction markers, history failure and accessibility. Unit coverage includes page changes, filtering, stale history, history failures and numeric/unit/date exclusions.

Verified: 4,975 frontend tests, 535 script tests, production build, ESLint, formatting, coverage floors, repository guards and translation parity/reference checks passed. The final browser run passed 11 screenshot/accessibility checks with no page errors; desktop rows measured 53px in FR/EN/ES.
