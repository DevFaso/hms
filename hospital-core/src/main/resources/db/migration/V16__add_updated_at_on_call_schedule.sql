-- V16: Add missing updated_at column to hospital.on_call_schedule
-- BaseEntity requires both created_at and updated_at; V14 only created created_at.

ALTER TABLE hospital.on_call_schedule
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now();
