import XCTest
@testable import PatientMobileApp

final class TreatmentPlansViewModelTests: XCTestCase {
    func testInitialState() async {
        let vm = await TreatmentPlansViewModel()
        XCTAssertTrue(vm.plans.isEmpty)
        XCTAssertFalse(vm.isLoading)
        XCTAssertNil(vm.error)
        XCTAssertTrue(vm.hasMore)
    }

    func testStatusColor() async {
        let vm = await TreatmentPlansViewModel()
        XCTAssertEqual(vm.statusColor("active"), "green")
        XCTAssertEqual(vm.statusColor("completed"), "blue")
        XCTAssertEqual(vm.statusColor("cancelled"), "red")
        XCTAssertEqual(vm.statusColor("on_hold"), "orange")
        XCTAssertEqual(vm.statusColor("on hold"), "orange")
        XCTAssertEqual(vm.statusColor("other"), "gray")
        XCTAssertEqual(vm.statusColor(nil), "gray")
    }
}
