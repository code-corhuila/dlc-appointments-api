package co.edu.corhuila.dlc.appointments.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A validated canonical event consumed by Appointments (event-catalog.md): envelope identity plus only the payload
 * fields this owner uses. The fact type fixes the event name, producer and aggregate.
 */
public record InboundEvent(UUID eventId, String eventType, int version, String producer, UUID aggregateId,
                           UUID correlationId, Fact fact) {
    public InboundEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(fact, "fact");
        if (!fact.eventType().equals(eventType) || !fact.producer().equals(producer) || version != 1
                || !fact.aggregateId().equals(aggregateId)) {
            throw new IllegalArgumentException("The fact does not match its canonical envelope.");
        }
    }

    public sealed interface Fact permits CareCompletionDeclared, PatientDeactivation {
        String eventType();

        String producer();

        UUID aggregateId();
    }

    /** ClinicalCareCompleted v1 (D8); procedure and manual-charge lists are not kept by Appointments. */
    public record CareCompletionDeclared(UUID clinicalRecordId, UUID consultationId, UUID appointmentId,
                                         UUID patientId, UUID dentistId, Instant completedAt, int clinicalVersion)
            implements Fact {
        public CareCompletionDeclared {
            Objects.requireNonNull(clinicalRecordId, "clinicalRecordId");
            Objects.requireNonNull(consultationId, "consultationId");
            Objects.requireNonNull(appointmentId, "appointmentId");
            Objects.requireNonNull(patientId, "patientId");
            Objects.requireNonNull(dentistId, "dentistId");
            Objects.requireNonNull(completedAt, "completedAt");
            if (clinicalVersion < 1) throw new IllegalArgumentException("clinicalVersion must be at least 1.");
        }

        @Override
        public String eventType() {
            return "ClinicalCareCompleted";
        }

        @Override
        public String producer() {
            return "clinical-service";
        }

        @Override
        public UUID aggregateId() {
            return clinicalRecordId;
        }
    }

    /** PatientDeactivated v1 (D9): the patient's gate becomes INACTIVE. */
    public record PatientDeactivation(UUID patientId, Instant deactivatedAt) implements Fact {
        public PatientDeactivation {
            Objects.requireNonNull(patientId, "patientId");
            Objects.requireNonNull(deactivatedAt, "deactivatedAt");
        }

        @Override
        public String eventType() {
            return "PatientDeactivated";
        }

        @Override
        public String producer() {
            return "patients-service";
        }

        @Override
        public UUID aggregateId() {
            return patientId;
        }
    }
}
