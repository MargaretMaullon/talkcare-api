package com.talkcare.api.domain.report;

import com.talkcare.api.domain.ServiceType;
import java.time.Instant;

/**
 * A concern report. Reporter details come from the immutable {@code report_snapshots} row when
 * present, otherwise from the client's current profile.
 */
public record Report(
        int id,
        String reportCode,
        int clientId,
        ServiceType serviceType,
        String concernType,
        ReportStatus status,
        String involvedWitness,
        String concernDetails,
        String remarks,
        Integer assignedProfessionalId,
        String assignedProfessionalName,
        Reporter reporter,
        Instant createdAt,
        Instant updatedAt) {

    public record Reporter(String fullName, String email, String contactNo, String address) {
    }
}
