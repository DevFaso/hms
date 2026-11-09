# Laboratory result history: response contract correction

GET `/lab-results/{id}` returns a bare `LabResultResponseDTO`. The portal incorrectly requested `ApiWrapper<LabResultResponse>` and returned `res.data`, discarding a successful response. The result identity guard then correctly rejected the missing detail and displayed the history-load error.

The client now preserves the raw detail DTO. No backend endpoint, permission, clinical rule, threshold, version filtering or patient identity check changes. Existing HTTP failures and mismatched identities still show errors. Unit and browser fixtures now use the actual bare response shape instead of the wrapper that masked this defect.

| Class | Hunt and instances | Fix | Guard | Before evidence | After evidence |
| --- | --- | --- | --- | --- | --- |
| Client decodes a raw response as an envelope | All seven GET endpoints on LabResultController: two wrapped paged reads and five raw reads. Only getResult was mismatched; comparison, sequential comparison, critical and unacknowledged reads already preserve raw DTOs. One UI caller: LabResultsComponent.viewResult. | Correct getResult; correct eight existing detail fixtures; add raw-response service and rendered-panel regressions. | scripts/lab-result-read-contract.test.mjs enumerates every raw GET from the Java controller and verifies the TypeScript clients; adding or changing a raw endpoint requires an explicit classification. | Contract guard failed: getResult must preserve the backend raw DTO. Focused tests: 2 failed, 92 passed; a valid raw response produced the error state and no chart. | 94 focused tests; 5,008 full frontend tests; 538 script tests; build, lint, formatting, guards and coverage floors passed. |

Read-only dev verification on 2026-11-08: login succeeded, downtime was false before and after, and the requested detail returned HTTP 200 with result/patient identity matching the readable list. It has no data envelope and contains one historical observation. Backend deployment `52cfeffc-f035-40f5-b776-b5817d7989d1` stayed unchanged during the check. No clinical records or infrastructure were modified.

The built portal was tested with a sanitized replay of that actual response in FR, EN and ES and at desktop/tablet/mobile sizes. The detail loads and displays the insufficient-history explanation for the single observation. A separately labelled synthetic two-observation case verifies chart rendering. A 503 case still displays the request failure. Nine screenshot/accessibility checks passed without page errors.

[Sanitized replay of the actual response](assets/lab-history-response/history-fr.png)

This follow-up corrects the merged PR #1099; it is not a deployment. A clinical trend requires at least two comparable results for the same patient and test.
