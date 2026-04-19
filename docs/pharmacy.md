Pharmacy Integration Strategy for DevFaso/hms in Burkina Faso
Executive summary
DevFaso/hms is already much farther along than a greenfield hospital system. In the code reviewed, it already has a prescription CRUD workflow, a pharmacist role and prescriptions route, patient-facing refill requests, medication administration records, patient notifications, billing views, and a medication-history service that can record pharmacy fills and compute overlaps, interactions, concurrent-medication counts, and polypharmacy warnings. The core architectural implication is that this should not be treated as “add a pharmacy module from scratch”; it should be treated as promoting existing medication capabilities into a first-class pharmacy domain with inventory, dispensing, payer/payment, and interoperability layers added around what already exists.        
For deployment in Burkina Faso, the best target architecture is a FHIR R4 canonical pharmacy service inside hms, fronted by the existing Angular portal, with an interoperability mediator for partner connections and an SFTP/batch fallback for low-maturity or intermittent-connectivity partners. That choice fits three facts from the sources: Burkina Faso has a current national essential-medicines framework and a universal-health-insurance law that preserves the patient’s free choice of pharmacist; the West African Health Organization is actively prioritizing interoperability, infrastructure, and cybersecurity in its 2026–2030 regional digital-health strategy; and WHO’s digital-health guidance explicitly warns that digital interventions must fit low-resource operating conditions rather than assume highly computerized ecosystems. 
The most important design decision is not whether to mimic U.S. pharmacy interoperability end to end. U.S. standards such as NCPDP SCRIPT, DEA EPCS rules, and HIPAA are useful comparison points, but they sit inside a dense national network and regulatory environment that Burkina Faso does not currently appear to have. For Burkina Faso, the right approach is to use FHIR for internal and partner APIs, HL7 v2 adapters where legacy hospital systems need it, local formulary and essential-medicines governance for master data, and NCPDP only as a comparator or for future connections to U.S.-oriented vendors. RxNorm should be treated as a helpful interoperability vocabulary, not the master drug catalog, because NLM states that RxNorm is scoped to prescription and many OTC drugs available in the United States. 
The largest repo-level opportunity is to normalize what is currently still partly free text. In the reviewed code, pharmacy destination selection is a simple DTO and service, refill requests take a preferredPharmacy string, and the current directory service even hardcodes a U.S.-style mail-order phone number and a default mail-order option. That is acceptable for prototype UX, but not for production in Burkina Faso. The next milestone should therefore be a normalized Pharmacy, InventoryItem, StockLot, Dispense, Payment, and Claim domain, with an auditable mapping to FHIR MedicationRequest, MedicationDispense, MedicationAdministration, Claim, Coverage, CommunicationRequest, and AuditEvent.    
Assumptions
The request leaves several deployment constraints unspecified, so the report assumes the following:
Country: Burkina Faso is the primary deployment jurisdiction, with regional interoperability needs inside West Africa.
Care setting: one hospital group or referral hospital plus satellite clinics, with inpatient and outpatient pharmacy workflows.
Language: French-first user interface and print/export artifacts, with English acceptable for technical internals.
Scale: low-to-medium transaction scale at launch, with limited always-on partner connectivity outside the hospital group.
Budget: modest public-sector or donor-constrained budget, so open-source-first is preferred unless commercial tooling clearly reduces execution risk.
Team: roughly 6–8 engineers plus one pharmacist domain lead, one implementation lead, and part-time compliance/security support.
Payments: mixed cash, mobile money, and partial insurance/benefit workflows rather than U.S.-style real-time pharmacy-benefit adjudication.
Regulatory maturity: local data-protection and cybersecurity compliance are mandatory; fully paperless controlled-substance e-prescribing should be treated as a later legal-validation milestone, not a day-one assumption.
What DevFaso/hms already contains
The reviewed code shows that DevFaso/hms already spans a Java/Spring-style backend and an Angular portal, and that medication workflows are not isolated fragments but are woven through portal navigation, role permissions, patient-facing services, and clinical models. The prescriptions route is already available to doctors, nurses, pharmacists, and admins; the permission model already gives pharmacists explicit prescription and dispensing-related permissions; and the patient portal already exposes prescriptions, medications, refills, billing, documents, notifications, consents, and access logs. That makes pharmacy integration a cross-cutting enhancement of an existing clinical platform, not a sidecar application.    
The table below summarizes the most relevant repo locations I found and what they mean for a pharmacy build-out.
Area in DevFaso/hms	Code location	What it already does	Why it matters for pharmacy
Prescription API contract	hospital-portal/src/app/services/prescription.service.ts	Exposes list/get/create/update/delete, with patient, staff, hospital filters and prescription statuses such as DRAFT, PENDING_SIGNATURE, SIGNED, TRANSMITTED, TRANSMISSION_FAILED, CANCELLED, DISCONTINUED.	A usable prescription state machine already exists; pharmacy should extend it rather than replace it. 
Prescription UI	hospital-portal/src/app/prescriptions/prescriptions.ts	Gives CRUD UI, patient lookup, search, status tabs, and tenant scoping by active hospital.	Good base for pharmacist work queues, but it is still prescriber-centric rather than dispense-centric. 
Patient-facing refill/notification/billing	hospital-portal/src/app/services/patient-portal.service.ts	Supports requestRefill, getMyRefills, cancellation, notification preferences, notifications, invoice views/payments, and medication/prescription views.	Refills, reminders, and patient self-service already exist, so pharmacy can become patient-facing quickly. 
Pharmacist authorization	hospital-portal/src/app/core/permission.service.ts	Defines ROLE_PHARMACIST with View Prescriptions, Dispense Medications, View Patient Records, View Notifications.	The role exists, but it is still too narrow for inventory, procurement, stock adjustment, and claims operations. 
Pharmacy destination model	hospital-core/.../PharmacyDirectoryServiceImpl.java and PharmacyLocationResponseDTO.java	Builds patient-facing pharmacy options from preferred pharmacy, hospital dispensary, and a hardcoded mail-order option; DTO includes e-prescribe and controlled-substance support flags.	Useful seed model, but currently too static and partly U.S.-centric for Burkina Faso operations.  
Fulfillment mode taxonomy	hospital-core/.../PharmacyFulfillmentMode.java	Defines COMMUNITY, MAIL_ORDER, INPATIENT_DISPENSARY.	Good starting taxonomy; Burkina rollout will likely need OUTPATIENT_HOSPITAL, PARTNER_PHARMACY, and MOBILE_OUTREACH variants. 
Medication history and CDS	hospital-core/.../MedicationHistoryServiceImpl.java	Creates fill records, builds medication timeline, detects overlaps/interactions, counts concurrent medicines, and flags polypharmacy.	This is already the nucleus of medication therapy management and clinical decision support.
Fill capture contract	hospital-core/.../PharmacyFillRequestDTO.java	Captures prescription link, NDC/RxNorm codes, quantity, days supply, substitution, pharmacy/prescriber identifiers, source system, and external reference.	Strong foundation for outpatient dispense and reconciliation, though several identifiers are U.S.-biased. 
Medication administration	hospital-core/.../MedicationAdministrationRecord.java	Tracks scheduled, administered, refused, held, or missed doses for a prescription.	Supports inpatient medication administration and closes the prescribe–dispense–administer loop.
Existing SMS channel	hospital-core/.../TwilioSmsServiceImpl.java	Sends SMS via Twilio when enabled.	The notification abstraction already exists; it can be retargeted for Burkina-friendly SMS providers if needed. 
Hospital-scoped access	hospital-core/.../RoleValidator.java	Enforces active-hospital context and hospital-scoped role checks.	Essential for multi-facility pharmacy stock visibility and proper segregation of duties. 

The strongest missing pieces are not in prescribing, but in pharmacy operations: I did not find, in the reviewed code paths, a dedicated stock ledger, lot/expiry model, purchase-order workflow, goods-receipt process, barcode/dispense verification flow, or true claim-adjudication connector. What exists today is best described as a medication-order and history platform with early pharmacy hooks, not a complete production pharmacy information system. That distinction should drive scoping and budget.     
Priority pharmacy use cases for Burkina Faso
Burkina Faso’s pharmacy workflows should be anchored to the national essential-medicines framework, the universal-health-insurance framework, and local operating realities. WHO’s essential-medicines guidance stresses that national lists are used for procurement, reimbursement, rational use, and supply management, and Burkina Faso’s 2023 national essential-medicines order is part of that same policy logic. Burkina Faso’s universal-health-insurance law, as summarized by WHO’s legislation repository, contemplates direct payment of all or part of covered costs and preserves the beneficiary’s free choice of pharmacist. Meanwhile, the Ministry of Health’s 2025 announcement around Faso Pharma shows that pharmaceutical sovereignty and supply capacity are active policy themes. 
That means the most valuable use cases are the ones that reduce stock-outs, preserve dispense traceability, support patient affordability, and work with mixed levels of digital readiness in community pharmacies.
Use case	What DevFaso/hms already supports	Burkina Faso / West Africa note	Priority
Prescription fulfillment	Prescription states, hospital context, patient and staff linkage, fill history hooks.	Must support both hospital dispensaries and affiliated community pharmacies; formulary should be tied to Burkina’s essential-medicines list first, not to a foreign catalog.	Very high
E-prescribing	Prescription creation and transmission states already exist.	Use internal e-prescribing day one; partner e-prescribing should start with REST/SFTP adapters because there is no visible national equivalent to the U.S. Surescripts/NCPDP network model in the official sources reviewed.	High
Inventory and stock control	No reviewed dedicated stock ledger yet.	This is the biggest operational gap; WHO digital guidance explicitly addresses stock notification/tracking in low-resource settings, and stock-outs matter more than sophisticated benefit checks in this context.	Very high
POS and payment collection	Patient billing and invoice views already exist.	Add cash and mobile-money-friendly checkout because mobile money is a mainstream regional payment rail in UEMOA-regulated markets and merchant payments are growing quickly.	High
Medication therapy management	Medication timeline, overlaps, interactions, polypharmacy warnings already exist.	Particularly valuable for chronic disease, maternal care, and antimicrobial stewardship when patients move between facilities and community pharmacies.	High
Clinical decision support	Interaction and overlap logic exists; medication administration records exist.	Start with practical CDS: allergy check, duplicate therapy, pregnancy/lactation warnings, renal-dose rules, stock substitution alerts, and antibiotic stewardship.	High
Patient notifications	Notifications, preferences, refill request flow, and SMS integration exist.	French SMS/app reminders for ready-for-pickup, refill approval, stock delay, and counseling follow-up are immediately useful.	High
Billing and claims	Billing views exist; no reviewed live pharmacy-claim adjudicator yet.	Burkina’s AMU law makes claims relevant, but implementation should begin with invoice + claim-file generation rather than assume U.S.-style real-time PBM adjudication.	Medium-high

The design implication is straightforward: phase one should optimize dispense reliability and stock visibility, phase two should improve patient communications and payments, and phase three should add insurer and external-partner connectivity. In low-resource settings, WHO explicitly cautions that digital interventions are not substitutes for functioning health systems and should be designed for feasibility in environments where extensive computerized infrastructure may not exist. The WAHO strategy process likewise puts interoperability, infrastructure, and cybersecurity at the center of regional priorities. 
Data model and standards mapping
The cleanest way to evolve DevFaso/hms is to establish a canonical internal pharmacy model aligned primarily to FHIR R4, while preserving adapters for HL7 v2 where required and treating NCPDP as a comparison and optional edge connector rather than the core model. HL7 states that MedicationRequest covers both inpatient and community medication orders, and MedicationDispense covers supply to a patient as the result of a pharmacy system responding to a medication order. FHIR also gives first-class resources for claims, communications, subscriptions, coverage, medication administration, and audit events, which makes it well-suited as a canonical API around an HMS that must serve clinical, financial, and patient-facing workflows.
HL7 v2 remains useful as an adapter technology because it is still widely used for operational message exchange, including orders and billing-like workflows, and its pharmacy/treatment chapter includes encoded orders, dispense, and administration messages. HL7 v3/CDA remains far better for document exchange than for nimble transactional pharmacy APIs. NCPDP, by contrast, is highly capable in U.S. pharmacy operations, but it is U.S.-centric, subject to paid standards access, and tightly coupled to U.S. e-prescribing and pharmacy-claims ecosystems. That makes it a poor choice for DevFaso/hms’s core domain model in Burkina Faso. 
RxNorm and SNOMED CT should be used differently. NLM says RxNorm is intended to normalize drug names and identifiers for U.S. prescription and many OTC drugs, so it is best used here as a crosswalk vocabulary where there is a match. SNOMED CT is a broader clinical terminology and is stronger for indications, allergies, conditions, and semantic consistency across clinical data. However, SNOMED International’s current members page does not list Burkina Faso, so licensing, affiliate access, and French localization should be clarified before making SNOMED CT a hard dependency at national scale. 
Standards comparison
Standard / terminology	Best role in this project	Strengths	Limitations for Burkina Faso	Recommendation
FHIR R4	Canonical API and internal interoperability model	Resource model covers prescribing, dispensing, administration, claims, notifications, audit, and subscriptions; REST-native and implementer-friendly.	Requires profiling discipline and governance; partner adoption may vary.	Primary standard
HL7 v2	Legacy adapter for hospital systems and selected partner feeds	Very common operational messaging pattern; supports pharmacy/treatment orders, dispense, and give/admin messages.	Harder to govern semantically; interface-by-interface mapping burden.	Secondary adapter
HL7 v3 / CDA	Human-readable documents and discharge/summary exchange	Strong document structure, authentication context, and human readability.	Poor fit for modern transactional pharmacy APIs.	Use for documents only
NCPDP SCRIPT / Telecommunication	U.S. comparison model; optional edge connector	Mature e-prescribing, refill, medication history, and pharmacy-claim workflows.	U.S.-specific ecosystem; membership/licensing access; not a natural fit for West African partner networks.	Do not make canonical
RxNorm	Drug normalization crosswalk where possible	Strong semantic normalization of U.S. drug names and codes.	U.S.-scoped; incomplete for local brands and regional products.	Crosswalk only
SNOMED CT	Clinical semantics for conditions, allergies, indications, dose instructions	Rich multilingual clinical terminology with strong semantic interoperability value.	Licensing/translation/governance need validation; Burkina not shown as a current member.	Use selectively, after governance check

Sources for the table. 
Explicit mappings to DevFaso/hms entities
DevFaso/hms entity or field	Canonical FHIR mapping	Other mappings / notes	Implementation note
PrescriptionRequest.medicationName / display name	MedicationRequest.medication[x]	Map to local formulary code first; attach RxNorm code when available.	Add internal MedicationCatalogItem table and stop relying on free text alone.  
patientId, staffId, encounterId, hospitalId	subject, requester, encounter, performer / dispenseRequest.dispenser	HL7 v2 equivalents live in PID, PV1, ORC, RXE/RXO families depending on message type.	These identities are already present; add stable external identifiers for interoperable exchange.  
dosage, frequency, duration, notes, route, instructions	dosageInstruction.text, timing, route, patientInstruction, note	NCPDP SCRIPT structured/codified Sig is a comparator here.	Keep free-text sig, but add structured dosage fields alongside it.   
Prescription status (DRAFT, SIGNED, TRANSMITTED, etc.)	MedicationRequest.status + extensions for local workflow state	NCPDP and v2 have their own transaction states.	Preserve local states for UX; map outward to standard status plus event history.  
PharmacyLocationResponseDTO	Organization + Location + HealthcareService as needed	Mode can map to local service taxonomy.	Replace generated options with persisted pharmacy registry records.   
preferredPharmacy in refill requests	MedicationRequest.dispenseRequest.performer or linked destination Organization	Currently free text in portal workflow.	Normalize to pharmacyId; keep human-readable fallback string for edge cases. 
PharmacyFillRequestDTO core fields	MedicationDispense	NCPDP refill/fill-status comparator; HL7 v2 RDS/RRD comparator.	This DTO is already close to a MedicationDispense command and should become the canonical write contract.  
ndcCode, rxnormCode, pharmacyNpi, pharmacyNcpdp, prescriberNpi, prescriberDea	Identifier slices with system URIs	U.S.-specific identifiers should be optional, not mandatory.	Add local identifier systems for Burkina licensure, facility registration, and payer membership. 
genericSubstitution, quantityDispensed, daysSupply, fillDate, directions	MedicationDispense.substitution, quantity, daysSupply, whenHandedOver, dosageInstruction	Useful for AMU claims and refill control.	Add substitute-reason and stock-lot references.  
MedicationAdministrationRecord	MedicationAdministration	Align with MAR and bedside scanning later.	Strong inpatient foundation already present. 
Billing invoice / pharmacy charge	Claim, ClaimResponse, Coverage, Invoice	In Burkina, begin with partial adjudication and batch claim outputs rather than assume live PBM logic.	Build a local PharmacyClaim layer linked to invoices and coverage.  
Notifications / reminders	CommunicationRequest, Communication, Subscription	SMS or app push; FHIR subscriptions can trigger event-driven notifications.	Reuse current notification preferences and SMS service abstraction.   
Access log / compliance trail	AuditEvent	Critical for privacy and security oversight.	Add audit coverage to all prescription, dispense, cancel, override, and stock-adjustment events.   

Integration architecture and workflows
The integration-pattern choice should follow the realities of the ecosystem, not the elegance of the standards stack. FHIR REST is the best default for new work. FHIR subscriptions are suitable for event-driven notifications and partner callbacks. GraphQL can be useful for read-optimized portal queries, but HL7 itself describes GraphQL support as draft and not a formal standard, so it should remain optional and read-mostly. For low-maturity partners, SFTP remains practical. For multi-system coordination, an interoperability layer such as OpenHIM is especially well aligned with low- and middle-income-country architectures because OpenHIM positions itself explicitly as a central interoperability layer, and OpenHIE frames that pattern for low-resource settings. 
Integration-pattern pros and cons
Pattern	Pros	Cons	Best use here
Direct EHR ↔ pharmacy REST integration	Fastest to start, least moving parts, easiest debugging.	Becomes brittle as partners multiply; duplicate mappings everywhere.	Internal hospital dispensary and one or two strategic partner pharmacies.
Middleware / integration engine	Centralized mappings, retries, monitoring, routing, audit, partner isolation.	Additional platform to run and govern.	Recommended default for Burkina Faso rollout.
National / regional HIE dependence	Strong long-term interoperability story.	Requires external governance and network maturity.	Future option, not day-one dependency.
SFTP flat-file exchange	Works with weak connectivity and low-maturity partners.	Slow, brittle semantics, weak real-time UX.	Fallback for insurer uploads and smaller partner pharmacies.
REST / FHIR	Clean contracts, mobile/web friendly, canonical model alignment.	Requires partner development capacity.	Primary greenfield API.
GraphQL facade	Efficient UI reads and composable queries.	Not a formal healthcare standard path and risky as the primary write API.	Optional portal/read layer only.
Webhooks / FHIR Subscriptions	Event-driven refill, dispense, claim-status, and stock-alert flows.	Requires reliable endpoints and retry semantics.	Use internally and with strong partners.

The recommended pattern for Burkina Faso is therefore: DevFaso/hms remains the system of clinical record; a pharmacy domain service is added inside hospital-core; a mediator handles partner-specific mappings; FHIR R4 is the canonical API; SFTP is retained as an operational fallback; and GraphQL is optional for UI reads only. This pattern is the best fit for WAHO’s interoperability/cybersecurity direction, WHO’s low-resource implementation guidance, and the fact that the current repo already has enough medication workflow logic to justify one canonical model instead of multiple competing ones.   
End-to-end workflow
In stock
Out of stock
Doctor or nurse creates prescription in hms
Clinical checks
allergy duplicate therapy pregnancy renal rules
Prescription signed and committed
Local stock check and formulary validation
Dispense task to hospital pharmacy
Route to partner community pharmacy
MedicationDispense recorded
Patient notification
SMS/app/print
Invoice and payment or claim file
Medication history and reconciliation
Follow-up refill / MTM / adherence review


Show code
This workflow uses structures already present in the repo: prescription creation and status handling, patient refill and notification flows, medication-history logic, and medication administration records. It extends them with stock validation, dispense execution, and claims/payment orchestration.     
Target architecture
Angular hospital-portal
DevFaso/hms hospital-core
Prescription service
Pharmacy domain service
Audit and consent services
Billing and claim service
Notification service
Medication catalog and formulary
Inventory and stock ledger
Dispense and refill engine
Medication history and CDS
Interoperability mediator
FHIR REST APIs
HL7 v2 adapters
SFTP batch fallback
Webhook/subscription dispatcher
Partner pharmacies
Legacy hospital systems
Payers / AMU files
SMS or app channels


Show code
Core entity relationships
receives
writes
scopes
authorizes
drives
performs
owns
stocked_as
ordered_as
settles
billed_as
covered_by
supports
generates
traced_by
traced_by
PATIENT
PRESCRIPTION
STAFF
HOSPITAL
DISPENSE
MAR
PHARMACY
STOCK_LOT
MEDICATION_ITEM
PAYMENT
CLAIM
COVERAGE
USER
AUDIT_EVENT


Show code
Security, privacy, and compliance
For Burkina Faso, the minimum compliance baseline is local law first, comparative frameworks second. WHO’s legislation repository shows that Burkina Faso enacted a 2021 personal-data-protection law covering automated and non-automated processing of personal data. The constitutional data-protection authority, CIL, describes itself as an independent administrative authority created to protect personal data. Burkina Faso’s national cybersecurity authority, ANSSI-BF, states that it is responsible for control and protection of the national cyberspace and offers audit, advice, assistance, and accreditation services; an ANSSI-hosted extract of the 2024 information-systems-security law says the law applies to public administration systems, critical infrastructure, communications operators, trust-service providers, and entities with economic or social impact in Burkina Faso. In other words, a hospital pharmacy deployment should be designed as a regulated information system from day one, not as a lightly governed app. 
If any data is hosted in, processed in, or systematically exchanged with the EU, GDPR becomes relevant as a comparative or additional requirement. EUR-Lex Article 9 treats health data as a special category of personal data, and Article 32 requires security measures proportionate to risk, including encryption/pseudonymization where appropriate, resilience, recoverability, and regular testing. HIPAA and the DEA rules are not directly applicable in Burkina Faso unless a U.S.-regulated party is involved, but they are still useful benchmarks: HHS emphasizes administrative, physical, and technical safeguards for confidentiality, integrity, and availability, while DEA’s EPCS rules illustrate how controlled-substance workflows demand logical access controls, strong practitioner authentication, and tamper-evident signing. 
The current repo already contains useful building blocks for this: patient consent and access-log views in the portal, an audit-log route, hospital-scoped role validation, and a pharmacist-specific role definition. That is enough to build a robust local control set if the pharmacy rollout adds the missing pharmacy-specific controls below.    
Required control set
Control	Why it is required	DevFaso/hms implication
Role-based access with segregation of duties	Prescribing, verification, dispensing, stock adjustment, and claim approval should not collapse into one role.	Expand beyond current pharmacist permissions to include verifier, inventory clerk, store manager, and claim reviewer roles.
Hospital and pharmacy context enforcement	Multi-site facilities need stock and dispense separation by site.	Reuse current hospital-context enforcement and add pharmacy-context scoping.
Strong audit trail	Required for privacy, fraud detection, override review, and stock reconciliation.	Emit immutable audit events for create/sign/cancel/refill/dispense/return/stock-adjust/claim-submit/claim-reverse.
Encryption in transit and at rest	Required by good security practice and comparative frameworks.	TLS everywhere; encrypted database storage and backups; encrypt sensitive exports.
Consent and minimum necessary disclosure	Needed for cross-hospital and partner-pharmacy exchange.	Reuse consent infrastructure and define explicit pharmacy-sharing purposes.
Controlled-substance hardening	High-risk workflow even if local e-prescribing rules are still evolving.	Dual approval, strong signer authentication, non-repudiation, and printable legal fallback.
Periodic security audit and incident readiness	Burkina’s ANSSI emphasizes audits, assistance, and security governance.	Schedule pre-go-live and annual assessments; maintain incident playbooks and offline downtime procedures.
Offline-safe operations	Connectivity outages should not stop dispensing.	Provide local dispense queue, delayed synchronization, and signed downtime forms/exports.

Implementation roadmap, effort, and tool choices
The roadmap below assumes a modest, pragmatic rollout that delivers operational value in phases instead of waiting for a perfect regional interoperability environment.
Phase	Milestones	Estimated effort	Key dependencies	Main risks	Success metrics
Foundation	Finalize scope, add pharmacy product owner, define Burkina formulary model, create pharmacy registry, define canonical FHIR profiles, security threat model.	4–6 weeks	Pharmacy lead, compliance review, architecture lead	Scope creep; weak local policy validation	Signed solution design; approved data model; top 20 workflows agreed
Core pharmacy domain	Add Pharmacy, MedicationCatalogItem, InventoryItem, StockLot, StockTransaction, Dispense, Payment, Claim, and substitution/reason models; migrate free-text pharmacy references.	8–12 weeks	DB migration capacity; backend engineers	Legacy data cleanup; poor formulary normalization	>95% new prescriptions linked to normalized medication and pharmacy IDs
Dispensing and inventory	Build dispense queue, stock decrement, lot/expiry capture, returns, substitutions, reorder thresholds, and prescription-to-dispense linkage.	8–10 weeks	Barcode/label decisions; store workflows	Stock accuracy issues; user-adoption friction	Stock variance <2%; dispense turnaround reduced; zero missing lot capture for tracked items
Patient and payment flows	Ready-for-pickup and refill notifications, checkout flow, cash/mobile money support, invoice-to-dispense linkage, receipt/printouts.	4–6 weeks	Payment integration; message templates	Failed notifications; cashier workflow mismatch	>80% successful notifications; reduced abandoned pickups
Interoperability edge	FHIR API publication, middleware routes, SFTP fallback, partner-pharmacy onboarding, insurer/AMU file export.	8–12 weeks	Partner engagement; mediator tooling	Partner heterogeneity; semantic drift	First partner live; <1% failed interface transactions after stabilization
Advanced clinical support	Allergy and duplicate-therapy checks, substitution advisories, stewardship rules, MTM review list, reporting dashboard.	6–8 weeks	Clinical governance committee	Alert fatigue	override rate monitored; reduced duplicate therapy events
Scale and governance	Security audit, disaster recovery drills, KPI dashboards, release governance, documentation and training.	4–6 weeks, then ongoing	Security lead; operations team	Governance gaps	successful DR test; monthly KPI review in place

These effort ranges assume one team and sequential delivery. With parallel frontend/backend work and a dedicated interoperability engineer, the first production-ready hospital dispensary deployment is realistic in about 6 to 8 months, with community-pharmacy and payer integration following in the next 3 to 6 months.
Recommended open-source and commercial tools
For an open-source-first deployment, the best fit is a combination of LinuxForHealth FHIR or HAPI FHIR for standards-aligned FHIR implementation, plus OpenHIM as the interoperability mediator. LinuxForHealth describes itself as a modular Java implementation of FHIR R4/R4B focused on performance and configurability; HAPI FHIR is a Java implementation of HL7 FHIR with client, plain-server, and JPA-server tooling; and OpenHIM presents itself as an interoperability layer and reference implementation within OpenHIE for low-resource settings. That combination maps well to a Spring-based backend such as DevFaso/hms. 
For commercial options, the strongest healthcare-grade integration engines among the reviewed sources are InterSystems, Oracle Health, and NextGen Healthcare’s Mirth Connect. InterSystems emphasizes healthcare integration, FHIR façades, monitoring, audit trails, and message routing; Oracle Health emphasizes secure provider/payer/public-health exchange and FHIR APIs; and Mirth Connect emphasizes high-scale interoperability across many exchanges. 
A U.S.-network option such as Surescripts is strategically relevant only if DevFaso/hms later needs to connect to U.S.-style medication-history or e-prescribing ecosystems. Surescripts is highly capable at national-scale interoperability and medication history in the United States, but it is not the right primary pharmacy network assumption for Burkina Faso.
Suggested tool stack
Category	Preferred choice	Why
Canonical standards layer	LinuxForHealth FHIR or HAPI FHIR	Best alignment with Java/Spring ecosystem and FHIR-first architecture
Interoperability mediator	OpenHIM	Strong fit for LMIC/OpenHIE-style interoperability governance
Enterprise integration fallback	InterSystems Health Connect or Mirth Connect	Strong monitoring, retries, transformations, and scale
EHR/platform integration when a commercial EHR is present	Oracle Health or InterSystems APIs/connectors	Useful when host organizations already own those ecosystems
Notifications	Keep current notification abstraction; retarget SMS provider if needed	The repo already has notification preferences and an SMS implementation hook
U.S.-specific network edge	Surescripts / NCPDP stack	Future optional edge, not the Burkina core

Sample code sketch for a FHIR mapper
This mapper belongs naturally next to the existing prescription and pharmacy-fill contracts in:
hospital-portal/src/app/services/prescription.service.ts
hospital-core/.../service/impl/PatientMedicationServiceImpl.java
hospital-core/.../service/impl/MedicationHistoryServiceImpl.java
hospital-core/.../payload/dto/medication/PharmacyFillRequestDTO.java
   
java
Copy
package com.example.hms.pharmacy.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.*;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.medication.PharmacyFillRequestDTO;

import java.math.BigDecimal;
import java.util.UUID;

public class PharmacyFhirMapper {
    private final FhirContext ctx = FhirContext.forR4();

    public MedicationRequest toMedicationRequest(
            PrescriptionRequestDTO dto,
            UUID prescriptionId,
            UUID patientId,
            UUID encounterId,
            UUID practitionerId
    ) {
        MedicationRequest mr = new MedicationRequest();
        mr.setId(prescriptionId.toString());
        mr.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        mr.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);

        mr.setSubject(new Reference("Patient/" + patientId));
        if (encounterId != null) {
            mr.setEncounter(new Reference("Encounter/" + encounterId));
        }
        if (practitionerId != null) {
            mr.setRequester(new Reference("Practitioner/" + practitionerId));
        }

        CodeableConcept med = new CodeableConcept().setText(dto.getMedicationName());
        mr.setMedication(med);

        Dosage dosage = new Dosage();
        dosage.setText(
            String.join(" ",
                safe(dto.getDosage()),
                safe(dto.getFrequency()),
                safe(dto.getDuration())
            ).trim()
        );
        if (dto.getNotes() != null && !dto.getNotes().isBlank()) {
            mr.addNote().setText(dto.getNotes());
        }
        mr.addDosageInstruction(dosage);

        return mr;
    }

    public MedicationDispense toMedicationDispense(
            PharmacyFillRequestDTO dto,
            UUID dispenseId
    ) {
        MedicationDispense md = new MedicationDispense();
        md.setId(dispenseId.toString());
        md.setStatus(MedicationDispense.MedicationDispenseStatus.COMPLETED);
        md.setSubject(new Reference("Patient/" + dto.getPatientId()));

        if (dto.getPrescriptionId() != null) {
            md.addAuthorizingPrescription(new Reference("MedicationRequest/" + dto.getPrescriptionId()));
        }

        CodeableConcept medication = new CodeableConcept().setText(dto.getMedicationName());
        if (dto.getRxnormCode() != null && !dto.getRxnormCode().isBlank()) {
            medication.addCoding()
                .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
                .setCode(dto.getRxnormCode());
        }
        md.setMedication(new Reference().setDisplay(dto.getMedicationName()));

        if (dto.getQuantityDispensed() != null) {
            md.setQuantity(new Quantity()
                .setValue(dto.getQuantityDispensed())
                .setUnit(dto.getQuantityUnit()));
        }
        if (dto.getDaysSupply() != null) {
            md.setDaysSupply(new Quantity().setValue(dto.getDaysSupply()).setUnit("days"));
        }
        if (dto.getFillDate() != null) {
            md.setWhenHandedOver(java.sql.Date.valueOf(dto.getFillDate()));
        }
        if (dto.getPharmacyName() != null && !dto.getPharmacyName().isBlank()) {
            md.setLocation(new Reference().setDisplay(dto.getPharmacyName()));
        }
        if (dto.getDirections() != null && !dto.getDirections().isBlank()) {
            md.addDosageInstruction().setText(dto.getDirections());
        }
        if (dto.getGenericSubstitution() != null) {
            MedicationDispense.MedicationDispenseSubstitutionComponent sub =
                new MedicationDispense.MedicationDispenseSubstitutionComponent();
            sub.setWasSubstituted(dto.getGenericSubstitution());
            md.setSubstitution(sub);
        }
        if (dto.getNotes() != null && !dto.getNotes().isBlank()) {
            md.addNote().setText(dto.getNotes());
        }

        return md;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
This is only a starter. In production, the mapper should bind to a normalized medication catalog, emit stable identifier systems for local pharmacies and prescribers, and capture event history for signing, transmission, dispense, and claim submission. HAPI FHIR is a reasonable implementation library for this in Java, and LinuxForHealth is a strong option if the team wants a more complete FHIR server boundary. 
Final recommendation
The recommended strategy is to build Pharmacy as a native domain inside DevFaso/hms, not as a detached module. Use the repo’s existing prescription, refill, notification, and medication-history capabilities as the base. Introduce a normalized pharmacy and inventory model, make FHIR R4 the canonical representation, add an interoperability mediator for partner connections, retain SFTP as a fallback, and localize terminology and formulary governance around Burkina Faso’s essential-medicines policy. Security should be designed to satisfy local data-protection and cybersecurity expectations from the start, with HIPAA/DEA used only as comparative hardening references. This approach is the best balance of realism, standards alignment, and delivery speed for Burkina Faso and adjacent West African deployments.     