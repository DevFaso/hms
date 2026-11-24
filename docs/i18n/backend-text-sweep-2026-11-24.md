# Backend display-text sweep (C-P4)

Local source inventory; this is not dev acceptance or a scorecard pass.

## Public refusal expressions

Each listed site changes only the refusal wording/arguments. Existing predicates, status handling, permissions, transaction boundaries and numerical checks remain. Field-specific name/trigger conflicts additionally keep their request-field metadata. CSV parsing retains its exception cause without disclosing its internal message.

| Source under hospital-core/src/main/java | Original line | Old expression | New expression |
|---|---|---|---|
| com/example/hms/controller/EncounterController.java | 124 | "Receptionist must be affiliated with a hospital (no active hospital in the request context)." | "request.hospitalContext.required" |
| com/example/hms/controller/EncounterController.java | 202 | "Hospital context required to list encounters. Please select an active hospital." | "request.hospitalContext.required" |
| com/example/hms/controller/InBasketController.java | 78 | "Hospital context is required. Pass hospitalId or ensure your token has a hospital scope." | "request.hospitalContext.required" |
| com/example/hms/controller/InBasketController.java | 106 | "Hospital context is required. Pass hospitalId or ensure your token has a hospital scope." | "request.hospitalContext.required" |
| com/example/hms/controller/InBasketController.java | 165 | "Unable to resolve user from authentication token." | "request.userContext.unavailable" |
| com/example/hms/controller/MeController.java | 113 | "Unable to resolve hospital from your context." | "request.hospitalContext.required" |
| com/example/hms/controller/MeController.java | 339 | "Unable to resolve user ID" | "request.userContext.unavailable" |
| com/example/hms/controller/MeController.java | 341 | "Unable to resolve user ID from authentication" | "request.userContext.unavailable" |
| com/example/hms/controller/NotificationController.java | 105 | "Unable to resolve user" | "request.userContext.unavailable" |
| com/example/hms/controller/NotificationController.java | 115 | "Unable to resolve user" | "request.userContext.unavailable" |
| com/example/hms/controller/PatientController.java | 190 | "Unable to resolve authenticated user identifier for nurse dashboard filtering." | "request.userContext.unavailable" |
| com/example/hms/controller/PatientController.java | 193 | "Hospital context required when filtering by assigned nurse." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 505 | "Doctor record requests require an explicit hospital context." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 532 | "Doctor timeline requests require an explicit hospital context." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 557 | "Hospital context is required to view address history." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 576 | "Unable to resolve authenticated clinician context." | "request.userContext.unavailable" |
| com/example/hms/controller/PatientController.java | 579 | "Hospital context is required to view allergy history." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 624 | "Hospital context is required to add allergies." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 651 | "Hospital context is required to update allergies." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 704 | "Hospital context is required to view diagnoses." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 732 | "Hospital context is required to add diagnoses." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 759 | "Hospital context is required to update diagnoses." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 807 | "Unable to resolve authenticated user context." | "request.userContext.unavailable" |
| com/example/hms/controller/PatientController.java | 810 | "Hospital context is required to view chart updates." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 834 | "Unable to resolve authenticated user context." | "request.userContext.unavailable" |
| com/example/hms/controller/PatientController.java | 837 | "Hospital context is required to view chart updates." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 859 | "Unable to resolve authenticated clinician context." | "request.userContext.unavailable" |
| com/example/hms/controller/PatientController.java | 862 | "Hospital context is required to create chart updates." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 933 | "Invalid inhouse date; expected ISO format yyyy-MM-dd or 'today'." | "request.day.invalid" |
| com/example/hms/controller/PatientController.java | 940 | "Doctor assignment not found for requested hospital." | "request.clinicianAssignment.unavailable" |
| com/example/hms/controller/PatientController.java | 945 | "Hospital context is required for clinician authorization." | "request.hospitalContext.required" |
| com/example/hms/controller/PatientController.java | 954 | "No active clinician assignment found for requested hospital context." | "request.clinicianAssignment.unavailable" |
| com/example/hms/controller/PatientTrackerController.java | 49 | "Hospital context is required for the patient tracker board." | "request.hospitalContext.required" |
| com/example/hms/controller/PharmacyDirectoryController.java | 108 | "Hospital context is required. Provide hospitalId parameter or select an active hospital." | "request.hospitalContext.required" |
| com/example/hms/controller/integration/Dhis2AdminController.java | 51 | "Access denied for hospital: " + hospitalId | "request.hospitalContext.denied" |
| com/example/hms/controller/integration/Dhis2AdminController.java | 68 | "Access denied for hospital: " + hospitalId | "request.hospitalContext.denied" |
| com/example/hms/controller/integration/Dhis2AdminController.java | 84 | "Access denied for hospital: " + hospitalId | "request.hospitalContext.denied" |
| com/example/hms/controller/integration/Dhis2AdminController.java | 99 | "Access denied for hospital: " + hospitalId | "request.hospitalContext.denied" |
| com/example/hms/controller/integration/Dhis2AdminController.java | 115 | "Access denied for hospital: " + hospitalId | "request.hospitalContext.denied" |
| com/example/hms/controller/integration/Dhis2AdminController.java | 130 | "Access denied for hospital: " + hospitalId | "request.hospitalContext.denied" |
| com/example/hms/controller/integration/Dhis2ExportController.java | 52 | "Access denied for hospital: " + body.hospitalId() | "request.hospitalContext.denied" |
| com/example/hms/controller/integration/Dhis2ExportController.java | 74 | "Access denied for hospital: " + hospitalId | "request.hospitalContext.denied" |
| com/example/hms/controller/integration/Dhis2ExportController.java | 92 | "Access denied for hospital: " + run.getHospital().getId() | "request.hospitalContext.denied" |
| com/example/hms/controller/support/ControllerAuthUtils.java | 44 | "Authentication required." | "request.context.sessionRequired" |
| com/example/hms/controller/support/ControllerAuthUtils.java | 125 | "Receptionist must be affiliated with a hospital (select an active hospital or provide hospitalId)." | "request.hospitalContext.required" |
| com/example/hms/controller/support/ControllerAuthUtils.java | 178 | "Invalid datetime format; expected ISO-8601." | "request.datetime.invalid" |
| com/example/hms/service/FrontendAuditServiceImpl.java | 31 | "Audit payload is required." | "request.auditPayload.required" |
| com/example/hms/service/PermissionServiceImpl.java | 44 | "A permission with this name already exists for the specified assignment." | "permission.name.taken" |
| com/example/hms/service/PermissionServiceImpl.java | 88 | "Another permission with this name already exists for the specified assignment." | "permission.name.taken" |
| com/example/hms/service/ReferenceCatalogServiceImpl.java | 93 | "CSV file is required for import" | "catalog.import.required" |
| com/example/hms/service/ReferenceCatalogServiceImpl.java | 127 | "Failed to parse catalog CSV: " + ex.getMessage(), ex | "catalog.import.unreadable", ex |
| com/example/hms/service/SmartPhraseServiceImpl.java | 222 | "SmartPhraseRequest is required" | "phrase.request.required" |
| com/example/hms/service/SmartPhraseServiceImpl.java | 255 | "A SmartPhrase already exists for trigger '" + trigger                         + "' at scope " + request.getScope() | "phrase.trigger.taken", trigger |
| com/example/hms/service/apikey/ApiKeyService.java | 193 | "An active hospital context is required." | "request.hospitalContext.required" |
| com/example/hms/service/apikey/ApiKeyService.java | 204 | "The key is already revoked." | "apikey.revocation.finished" |
| com/example/hms/service/apikey/ApiKeyService.java | 217 | "The key was changed at the same time by someone else - reload and retry." | "apikey.change.concurrent" |
| com/example/hms/service/credentialing/CredentialRenewalService.java | 103 | "A practitioner cannot record their own credential renewal." | "credential.renewal.otherRecorder" |
| com/example/hms/service/empi/EmpiServiceImpl.java | 536 | "EMPI link request is required" | "empi.link.requestRequired" |
| com/example/hms/service/empi/EmpiServiceImpl.java | 550 | "Alias request is required" | "empi.alias.requestRequired" |
| com/example/hms/service/impl/CdsAcknowledgementServiceImpl.java | 72 | "Unable to resolve user from authentication." | "request.userContext.unavailable" |
| com/example/hms/service/impl/InBasketServiceImpl.java | 138 | "In-Basket item is already acknowledged" | "inbasket.acknowledgement.finished" |
| com/example/hms/service/impl/InBasketServiceImpl.java | 299 | "In-Basket item does not belong to the current user" | "inbasket.item.otherOwner" |
| com/example/hms/service/impl/MedicationCatalogItemServiceImpl.java | 99 | "Hospital ID is required. Only super-admins may create global "                         + "(platform-wide) medication catalog entries." | "catalog.hospital.required" |
| com/example/hms/service/impl/MedicationCatalogItemServiceImpl.java | 111 | "Hospital admins can only add medications to their own hospital's catalog." | "catalog.hospital.otherOwner" |
| com/example/hms/service/impl/SupportImpersonationServiceImpl.java | 98 | "Cannot start impersonation while already impersonating" | "support.session.nested" |
| com/example/hms/service/impl/SupportImpersonationServiceImpl.java | 114 | "An impersonation session is already active for this account" | "support.session.active" |
| com/example/hms/service/impl/SupportImpersonationServiceImpl.java | 126 | "Cannot impersonate another super admin" | "support.target.superAdmin" |
| com/example/hms/service/impl/SupportImpersonationServiceImpl.java | 136 | "Target user has no active role assignments and cannot be impersonated" | "support.target.noActiveRole" |
| com/example/hms/service/impl/SupportImpersonationServiceImpl.java | 190 | "No impersonation session is active for this token" | "support.session.missing" |
| com/example/hms/service/integration/impl/Dhis2ConfigServiceImpl.java | 116 | "Cross-tenant edit blocked: mapping belongs to another hospital" | "dhis.mapping.otherOwner" |
| com/example/hms/service/integration/impl/Dhis2ConfigServiceImpl.java | 128 | "Cross-tenant delete blocked: mapping belongs to another hospital" | "dhis.mapping.otherOwner" |
| com/example/hms/service/integration/impl/DhisAdxExportServiceImpl.java | 70 | "periodType " + periodType + " does not match facility default "                     + config.getDefaultPeriodType() | "dhis.period.mismatch" |
| com/example/hms/service/pro/ProInstrumentService.java | 105 | "Instrument " + instrument.getCode() + " has no text loaded." | "pro.text.missing", instrument.getCode() |
| com/example/hms/service/pro/ProInstrumentService.java | 261 | "Item " + item.getItemNo() + " is defined twice." | "pro.item.repeated", item.getItemNo() |
| com/example/hms/service/pro/ProInstrumentService.java | 266 | "Item " + item.getItemNo() + " defines option "                         + option.getOptionNo() + " twice." | "pro.option.repeated", item.getItemNo(), option.getOptionNo() |
| com/example/hms/service/pro/ProInstrumentService.java | 272 | "Item " + item.getItemNo()                         + " options must be numbered 1.." + item.getOptions().size() + " without gaps." | "pro.options.sequence", item.getItemNo(), item.getOptions().size() |
| com/example/hms/service/pro/ProInstrumentService.java | 289 | "Language " + language + " is given twice." | "pro.language.repeated", language |
| com/example/hms/service/pro/ProInstrumentService.java | 295 | "Language " + language + " has text for item "                         + itemText.getItemNo() + ", which the instrument does not define." | "pro.language.itemUnknown", language, itemText.getItemNo() |
| com/example/hms/service/pro/ProInstrumentService.java | 299 | "Language " + language + " gives item "                         + itemText.getItemNo() + " twice." | "pro.language.itemRepeated", language, itemText.getItemNo() |
| com/example/hms/service/pro/ProInstrumentService.java | 303 | "Language " + language + " item " + itemText.getItemNo()                         + " has " + itemText.getOptions().size() + " option labels; the item has "                         + expectedOptions + " options." | "pro.language.optionsCount", language, itemText.getItemNo(), itemText.getOptions().size(), expectedOptions |
| com/example/hms/service/pro/ProInstrumentService.java | 309 | "Language " + language + " covers " + covered.size()                     + " of " + optionCounts.size() + " items; every language must cover every item." | "pro.language.itemsCount", language, covered.size(), optionCounts.size() |
| com/example/hms/service/pro/ProScoring.java | 56 | "Instrument " + instrument.getCode() + " has no items loaded" | "pro.items.missing", instrument.getCode() |
| com/example/hms/service/pro/ProScoring.java | 60 | "Instrument " + instrument.getCode() + " has no item " + itemNo | "pro.item.unknown", instrument.getCode(), itemNo |
| com/example/hms/service/pro/ProScoring.java | 97 | "Instrument " + instrument.getCode()             + " item " + item.getItemNo() + " has no option " + optionNo | "pro.option.unknown", instrument.getCode(), item.getItemNo(), optionNo |
| com/example/hms/service/reporting/ReportGenerationService.java | 146 | "Unparseable period token '" + token                 + "' for a " + period + " report." | "report.period.invalid" |

Additional HTTP HL7 refusals use structured field/maximum metadata for localized display. Existing protocol diagnostic strings, ACK reply header, validation widths and import rules are unchanged.

## Other display boundaries

- Missing-key/bootstrap messages: MessageUtil returns translated safe text without key/argument disclosure. Already-rendered prose and resolveOrRaw retain their contracts. The five optional enum-label callers retain their prior explicit fallback through resolveLabel.
- Mapper fallback labels: InvoiceItemMapper staff and NursingNoteMapper unknown author use reader-language bundles; real recorded names remain unchanged.
- Direct controller responses: all former ResponseProseGuardTest allowances are translated, including profile/referral/chart/chat uploads, invalid invoice e-mail, mail unavailability and default-security confirmation. Success/error status codes and validation predicates are unchanged.
- Calculated dashboard KPI labels/units and organization compliance notes are translated on read; metric keys/values/trends and compliance identity/severity/expiry values remain unchanged.
- Currency: every PdfInvoiceService invoice/receipt/statement/till amount and both invoice-e-mail amount callers carry the document/patient locale. Whole-franc HALF_UP rounding, BigDecimal precision and underlying amounts remain unchanged. French uses spaces, English commas and Spanish periods.

## Explicit non-display or immutable boundaries

- Canonical audit records remain English under the repository’s audit convention; the existing audit reader translates known descriptions. No historical audit row is rewritten.
- Log messages, developer diagnostics, protocol ACK text, API/enum codes and FHIR coding-system display text retain their machine/integrator contracts.
- Applied seed migrations and already persisted free-text records are not rewritten. User-supplied clinical text is not translated or fabricated.
- Existing clinical-rule and medication-limit text is outside the assigned files; no clinical threshold, instrument wording, rule, protected insurance file or copy-download policy is changed.
- Existing SMS, e-mail template, date/number, exception-prose and bundle/key guards remain active. Response-prose allowances shrink to zero; exception-prose and stored/returned-prose allowances shrink only, never grow.

## Proof

Production-only rollback of InBasketController: three real MockMvc HTTP language cases and the whole-tree business-refusal guard fail. Restored positive run passes.

Production-only forced French currency formatter: invoice PDF/receipt/e-mail runtime cases and the independent locale-source guard fail. Restored positive run passes; compatibility/null/large-value/rounding tests retain existing amount semantics.

Production-only rollback of FileUploadController: actual HTTP upload language cases and the zero-allowance response-prose guard fail. Restored positive run passes.

Full-suite and exact-head CI status are recorded in the PR body after completion.

Calculated-label production-only rollback causes5 runtime language failures and the independent stored/returned-prose guard failure; the restored run passes. The original26 refusal-sweep files were compared after replacing complete exception constructor expressions with a sentinel: all other tokens remain identical (whitespace normalized).
