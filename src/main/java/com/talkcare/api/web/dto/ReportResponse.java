package com.talkcare.api.web.dto;

import com.talkcare.api.domain.ServiceType;
import com.talkcare.api.domain.report.Report;
import com.talkcare.api.domain.report.ReportStatus;
import java.time.Instant;

/** {@code userId} duplicates {@code clientId} for older desktop builds. */
public record ReportResponse(
        int id,
        String reportCode,
        int userId,
        int clientId,
        ServiceType serviceType,
        String fullName,
        String email,
        String address,
        String contactNo,
        String concernType,
        String involvedWitness,
        String concernDetails,
        ReportStatus status,
        String statusLabel,
        String remarks,
        Integer assignedProfessionalId,
        String assignedProfessionalName,
        String recipient,
        Instant createdAt,
        Instant updatedAt) {

    public static ReportResponse from(Report r) {
        return new ReportResponse(
                r.id(), r.reportCode(), r.clientId(), r.clientId(), r.serviceType(),
                r.reporter().fullName(), r.reporter().email(), r.reporter().address(), r.reporter().contactNo(),
                r.concernType(), r.involvedWitness(), r.concernDetails(),
                r.status(), r.status().label(), r.remarks(), r.assignedProfessionalId(),
                r.assignedProfessionalName(), r.serviceType() == ServiceType.THOMATT ? "PSYCHIATRIST" : "ATTORNEY",
                r.createdAt(), r.updatedAt());
    }
}
