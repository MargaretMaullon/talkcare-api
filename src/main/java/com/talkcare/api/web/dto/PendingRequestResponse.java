package com.talkcare.api.web.dto;

import com.talkcare.api.domain.account.PendingPasswordReset;
import com.talkcare.api.domain.report.Report;
import com.talkcare.api.domain.report.ReportStatus;
import com.talkcare.api.domain.verification.PendingVerification;
import com.talkcare.api.domain.verification.VerificationStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** An item in the admin "pending requests" queue: a verification, an under-review report or a password reset. */
public sealed interface PendingRequestResponse {

    String type();

    Instant submittedAt();

    /** All kinds merged, newest first. */
    static List<PendingRequestResponse> merge(List<PendingVerification> verifications, List<Report> reports,
                                              List<PendingPasswordReset> passwordResets) {
        return Stream.of(
                        verifications.stream().map(PendingRequestResponse::of),
                        reports.stream().map(PendingRequestResponse::of),
                        passwordResets.stream().map(PendingRequestResponse::of))
                .<PendingRequestResponse>flatMap(s -> s)
                .sorted(Comparator.comparing(PendingRequestResponse::submittedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    static Verification of(PendingVerification v) {
        return new Verification("VERIFICATION", v.id(), v.userId(), v.role(), v.licenseNo(), v.fullName(), v.email(), v.idType(), v.idNumber(),
                v.address(), v.contactNo(), v.documentUrl(), v.status(), v.submittedAt());
    }

    static ReportItem of(Report r) {
        return new ReportItem("REPORT", r.id(), r.clientId(), r.reporter().fullName(), r.reporter().email(),
                r.reporter().address(), r.reporter().contactNo(), r.status(), r.createdAt(), r.reportCode(),
                r.concernType(), r.concernDetails(), r.involvedWitness(), r.remarks());
    }

    static PasswordReset of(PendingPasswordReset p) {
        return new PasswordReset("PASSWORD_RESET", p.id(), p.userId(), p.role(), p.fullName(), p.username(), p.email(),
                p.submittedAt());
    }

    record PasswordReset(String type, int id, int userId, String role, String name, String username, String email,
                         Instant submittedAt)
            implements PendingRequestResponse {
    }

    record Verification(
            String type, int id, int userId, String role, String licenseNo, String name, String email, String idType,
            String idNumber, String address, String contactNo, String imageUrl, VerificationStatus status,
            Instant submittedAt)
            implements PendingRequestResponse {
    }

    record ReportItem(
            String type, int id, int userId, String name, String email, String address, String contactNo,
            ReportStatus status, Instant submittedAt, String reportCode, String concernType, String details,
            String witness, String remarks)
            implements PendingRequestResponse {
    }
}
