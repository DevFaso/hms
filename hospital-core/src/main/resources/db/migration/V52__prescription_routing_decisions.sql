-- Stock-Out Routing: prescription routing decisions
-- Rollback: DROP TABLE IF EXISTS clinical.prescription_routing_decisions;

CREATE TABLE IF NOT EXISTS clinical.prescription_routing_decisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prescription_id UUID NOT NULL REFERENCES clinical.prescriptions(id),
    routing_type VARCHAR(20) NOT NULL,
    target_pharmacy_id UUID REFERENCES clinical.pharmacies(id),
    decided_by_user_id UUID NOT NULL REFERENCES security.users(id),
    decided_for_patient_id UUID NOT NULL REFERENCES clinical.patients(id),
    reason VARCHAR(1024),
    estimated_restock_date DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decided_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_routing_prescription ON clinical.prescription_routing_decisions(prescription_id);
CREATE INDEX idx_routing_patient ON clinical.prescription_routing_decisions(decided_for_patient_id);
CREATE INDEX idx_routing_status ON clinical.prescription_routing_decisions(status);
