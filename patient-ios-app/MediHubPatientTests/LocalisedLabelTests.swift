import XCTest
@testable import MediHubPatient

/// Wire enums, API errors and disclosure roles read in the app's language —
/// English, French and Spanish — and never as a raw token.
final class LocalisedLabelTests: XCTestCase {

    private func bundle(_ language: String) throws -> Bundle {
        for candidate in [Bundle(for: LocalizationManager.self), Bundle.main] {
            if let path = candidate.path(forResource: language, ofType: "lproj"),
               let localized = Bundle(path: path) {
                return localized
            }
        }
        throw NSError(domain: "LocalisedLabelTests", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "no \(language).lproj in the app bundle"])
    }

    // MARK: - Every wire value of every family resolves

    /// The backend's own enum values (hospital-core `enums/*`), plus the
    /// payment methods the patient form sends. A family's key is constructed,
    /// so no source scan can see a missing one; this list is the gate.
    private static let wireValues: [EnumLabel.Family: [String]] = [
        .appointmentStatus: ["SCHEDULED", "CONFIRMED", "CHECKED_IN", "CANCELLED", "COMPLETED", "NO_SHOW",
                             "PENDING", "RESCHEDULED", "IN_PROGRESS", "FAILED", "UNKNOWN"],
        .claimStatus: ["DRAFT", "SUBMITTED", "ACCEPTED", "REJECTED", "PAID"],
        .dischargeDisposition: ["HOME", "HOME_WITH_HOME_HEALTH", "SKILLED_NURSING_FACILITY",
                                "LONG_TERM_CARE_FACILITY", "REHABILITATION_FACILITY", "HOSPICE_HOME",
                                "HOSPICE_FACILITY", "PSYCHIATRIC_FACILITY", "AGAINST_MEDICAL_ADVICE",
                                "LEFT_WITHOUT_BEING_SEEN", "TRANSFERRED_TO_ANOTHER_HOSPITAL", "EXPIRED", "OTHER"],
        .encounterStatus: ["SCHEDULED", "ARRIVED", "TRIAGE", "WAITING_FOR_PHYSICIAN", "IN_PROGRESS",
                           "AWAITING_RESULTS", "READY_FOR_DISCHARGE", "COMPLETED", "CANCELLED"],
        .encounterType: ["CONSULTATION", "FOLLOW_UP", "EMERGENCY", "SURGERY", "LAB", "OUTPATIENT",
                         "INPATIENT", "TELEHEALTH"],
        .gender: ["MALE", "FEMALE", "OTHER", "NON_BINARY", "PREFER_NOT_TO_SAY"],
        .immunizationStatus: ["COMPLETED", "DEFERRED", "ENTERED_IN_ERROR", "NOT_DONE", "REFUSED"],
        .invoiceStatus: ["DRAFT", "SENT", "PARTIALLY_PAID", "PAID", "CANCELLED"],
        .paymentMethod: ["CASH", "CARD", "CREDIT_CARD", "DEBIT_CARD", "INSURANCE", "BANK_TRANSFER",
                         "CHECK", "OTHER", "MOBILE_MONEY"],
        .proxyPermission: ["VIEW_APPOINTMENTS", "VIEW_MEDICATIONS", "VIEW_LAB_RESULTS", "VIEW_BILLING",
                           "VIEW_RECORDS", "ALL"],
        .proxyStatus: ["ACTIVE", "EXPIRED", "REVOKED"],
        .referralStatus: ["DRAFT", "SUBMITTED", "ACKNOWLEDGED", "SCHEDULED", "IN_PROGRESS", "COMPLETED",
                          "CANCELLED", "REJECTED", "EXPIRED"],
        .referralUrgency: ["ROUTINE", "PRIORITY", "URGENT", "EMERGENCY"],
        .relationship: ["PARENT", "SPOUSE", "CHILD", "CAREGIVER", "LEGAL_GUARDIAN", "SIBLING", "OTHER"],
        .treatmentPlanStatus: ["DRAFT", "IN_REVIEW", "REVISIONS_REQUIRED", "APPROVED", "ARCHIVED", "CANCELLED"],
        .vitalSource: ["NURSE_STATION", "CLINICAL", "HOME", "SELF_REPORTED", "DEVICE", "TRIAGE"],
    ]

    func testEveryFamilyIsListed() {
        XCTAssertEqual(Set(Self.wireValues.keys), Set(EnumLabel.Family.allCases))
    }

    func testEveryWireValueHasALabelInEveryLanguage() throws {
        for language in ["en", "fr", "es"] {
            let localized = try bundle(language)
            var missing: [String] = []
            for (family, values) in Self.wireValues {
                for value in values {
                    let key = EnumLabel.key(family, value)
                    let label = localized.localizedString(forKey: key, value: "__MISSING__", table: nil)
                    if label == "__MISSING__" || label.isEmpty { missing.append(key) }
                }
            }
            XCTAssertTrue(missing.isEmpty, "\(language).lproj lacks: \(missing.sorted().joined(separator: ", "))")
        }
    }

    // MARK: - The labels the tasklist named

    func testTreatmentPlanAndReferralStatusesAreFrenchInTheFrenchBuild() throws {
        let fr = try bundle("fr")
        XCTAssertEqual(EnumLabel.label(.treatmentPlanStatus, "REVISIONS_REQUIRED", bundle: fr), "Révisions requises")
        XCTAssertEqual(EnumLabel.label(.referralStatus, "ACKNOWLEDGED", bundle: fr), "Confirmée")
        let es = try bundle("es")
        XCTAssertEqual(EnumLabel.label(.referralStatus, "ACKNOWLEDGED", bundle: es), "Confirmada")
    }

    func testKeyNormalisesCaseSpacesAndDashes() {
        XCTAssertEqual(EnumLabel.key(.referralStatus, " In Progress "), "enum_referral_status_in_progress")
        XCTAssertEqual(EnumLabel.key(.encounterType, "follow-up"), "enum_encounter_type_follow_up")
    }

    func testAnUnknownValueIsUnknownNotTheRawToken() throws {
        let fr = try bundle("fr")
        XCTAssertEqual(EnumLabel.label(.referralStatus, "BRAND_NEW_STATE", bundle: fr), "Inconnu")
        XCTAssertNil(EnumLabel.label(.referralStatus, "  ", bundle: fr))
        XCTAssertNil(EnumLabel.label(.referralStatus, nil, bundle: fr))
    }

    func testFreeTextFieldsKeepWhatWasTyped() throws {
        let fr = try bundle("fr")
        XCTAssertEqual(EnumLabel.label(.relationship, "Tante", rawFallback: true, bundle: fr), "Tante")
        XCTAssertEqual(EnumLabel.label(.gender, "female", rawFallback: true, bundle: fr), "Féminin")
    }

    // MARK: - API errors

    func testApiErrorsFollowTheAppLanguage() {
        let restorer = LanguageRestorer()
        defer { restorer.restore() }

        LocalizationManager.shared.setLanguage("fr")
        XCTAssertEqual(APIError.unauthorized.localizedDescription,
                       "Votre session a expiré. Veuillez vous reconnecter.")
        XCTAssertEqual(APIError.httpError(statusCode: 503, message: nil).localizedDescription,
                       "Erreur du serveur (503).")
        XCTAssertEqual(APIError.networkError(URLError(.notConnectedToInternet)).localizedDescription,
                       "Vous semblez hors ligne. Vérifiez votre connexion et réessayez.")

        LocalizationManager.shared.setLanguage("es")
        XCTAssertEqual(APIError.unknown.localizedDescription, "Se produjo un error desconocido.")
        XCTAssertEqual(AuthError.notPatient.localizedDescription,
                       "Esta aplicación es solo para pacientes. Utilice el portal web para iniciar sesión.")
    }

    func testTheServersOwnMessageIsKept() {
        // The backend localises `message` from Accept-Language; it is shown as is.
        XCTAssertEqual(APIError.httpError(statusCode: 400, message: "Motif requis").localizedDescription,
                       "Motif requis")
    }

    // MARK: - Disclosure roles

    func testAnUnknownDisclosureRoleIsAGenericLabelInTheAppLanguage() {
        let restorer = LanguageRestorer()
        defer { restorer.restore() }

        LocalizationManager.shared.setLanguage("fr")
        XCTAssertEqual(DisclosureFormat.roleLabel("ROLE_FLEET_COORDINATOR"), "Membre du personnel")
        XCTAssertEqual(DisclosureFormat.roleLabel("ROLE_LAB_TECHNICIAN"), "Technicien(ne) de laboratoire")
        XCTAssertEqual(DisclosureFormat.roleLabel("STORE_MANAGER"), "Gestionnaire de stock")

        LocalizationManager.shared.setLanguage("es")
        XCTAssertEqual(DisclosureFormat.roleLabel("ROLE_FLEET_COORDINATOR"), "Miembro del personal")
    }

    // MARK: - Account links and password rules

    func testTokenIsReadFromAPastedLinkOrTakenAsIs() {
        XCTAssertEqual(AccountLinkParser.token(from: " https://e-keneya.com/reset-password?token=abc123 "), "abc123")
        XCTAssertEqual(AccountLinkParser.token(from: "abc123"), "abc123")
        XCTAssertEqual(AccountLinkParser.email(from: "https://e-keneya.com/verify?email=a%40b.co&token=t"), "a@b.co")
        XCTAssertNil(AccountLinkParser.email(from: "t0k3n"))
        XCTAssertTrue(AccountLinkParser.looksLikeEmail("awa@example.bf"))
        XCTAssertFalse(AccountLinkParser.looksLikeEmail("awa@example"))
    }

    func testPasswordRulesMirrorTheBackend() {
        XCTAssertEqual(PasswordRules.problem(newPassword: "short", confirmation: "short"), "password_min_length")
        XCTAssertEqual(PasswordRules.problem(newPassword: "longenough", confirmation: "different"), "passwords_mismatch")
        XCTAssertEqual(PasswordRules.problem(newPassword: "longenough", confirmation: "longenough",
                                             current: "longenough"), "password_must_differ")
        XCTAssertNil(PasswordRules.problem(newPassword: "longenough", confirmation: "longenough", current: "old-one"))
    }

    func testMfaCodeShapeMatchesWhatTheServerAccepts() {
        XCTAssertTrue(LoginViewModel.isPlausibleMfaCode("123456"))
        XCTAssertTrue(LoginViewModel.isPlausibleMfaCode("ABCD1234"))
        XCTAssertFalse(LoginViewModel.isPlausibleMfaCode("12345"))
        XCTAssertFalse(LoginViewModel.isPlausibleMfaCode("123456789"))
    }
}
