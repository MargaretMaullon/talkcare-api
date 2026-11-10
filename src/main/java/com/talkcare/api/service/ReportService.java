package com.talkcare.api.service;

import com.talkcare.api.domain.Permission;
import com.talkcare.api.domain.ServiceType;
import com.talkcare.api.domain.activity.Notification;
import com.talkcare.api.domain.account.Account;
import com.talkcare.api.domain.account.Client;
import com.talkcare.api.domain.account.Professional;
import com.talkcare.api.domain.lookup.ConcernType;
import com.talkcare.api.domain.lookup.ServiceTypeInfo;
import com.talkcare.api.domain.report.Report;
import com.talkcare.api.domain.report.ReportStatus;
import com.talkcare.api.error.ForbiddenException;
import com.talkcare.api.error.NotFoundException;
import com.talkcare.api.error.ValidationException;
import com.talkcare.api.domain.chat.Conversation;
import com.talkcare.api.repository.ActivityRepository;
import com.talkcare.api.repository.ConversationRepository;
import com.talkcare.api.repository.LookupRepository;
import com.talkcare.api.repository.NotificationRepository;
import com.talkcare.api.repository.ReportRepository;
import com.talkcare.api.web.dto.CreateReportRequest;
import com.talkcare.api.web.dto.UpdateReportStatusRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Concern reports submitted by clients. */
@Service
public class ReportService {

    private final ReportRepository reports;
    private final LookupRepository lookups;
    private final ActivityRepository activity;
    private final NotificationRepository notifications;
    private final ConversationRepository conversations;

    public ReportService(ReportRepository reports, LookupRepository lookups, ActivityRepository activity,
                         NotificationRepository notifications, ConversationRepository conversations) {
        this.conversations = conversations;
        this.reports = reports;
        this.lookups = lookups;
        this.activity = activity;
        this.notifications = notifications;
    }

    /**
     * The chosen recipient (attorney or psychiatrist) decides the service; otherwise it comes from
     * the concern type, then the requested service, else JusticeCare. The report is assigned to the
     * client's professional for that service (or the least-loaded one), who is notified. When no professional is available it goes to the admins instead.
     */
    @Transactional
    public Report submit(Account actor, CreateReportRequest request) {
        // reports.client_id must reference a CLIENT (enforced by the validate_report trigger).
        if (!(actor instanceof Client)) {
            throw new ForbiddenException("Only client accounts can submit a concern report.");
        }
        InputRules.requireVerifiedClient(actor);
        ConcernType concern = lookups.findConcernType(request.concernType())
                .orElseThrow(() -> new ValidationException("\"" + request.concernType() + "\" is not a recognised concern type."));
        ServiceType serviceCode = recipientService(request.recipient());
        if (serviceCode == null) {
            serviceCode = concern.serviceType() != null ? concern.serviceType()
                    : ServiceType.fromCode(request.serviceType()).orElse(ServiceType.JUSTICE_CARE);
        }
        ServiceTypeInfo service = lookups.findServiceType(serviceCode).orElseThrow();
        ServiceType chosen = serviceCode;
        Integer professionalId = conversations.findOpen(actor.id(), chosen)
                .map(Conversation::professionalId)
                .or(() -> conversations.pickProfessional(chosen))
                .orElse(null);

        int id = reports.create(new ReportRepository.NewReport(
                actor.id(), service.id(), concern.id(), request.involvedWitness(), request.concernDetails(),
                professionalId,
                new Report.Reporter(request.fullName(), request.email(), request.contactNo(), request.address())));
        Report created = reports.findById(id).orElseThrow();
        String recipient = chosen == ServiceType.THOMATT ? "psychiatrist" : "attorney";
        String to = created.assignedProfessionalName() != null
                ? " to " + recipient + " " + created.assignedProfessionalName() : " for the " + recipient;
        activity.log(new ActivityRepository.NewActivity(actor.id(), "REPORT", id, "SUBMITTED",
                "Report " + created.reportCode() + " submitted by " + actor.displayName()
                        + " (" + concern.name() + ")" + to));
        if (professionalId != null) {
            notifications.create(professionalId, Notification.REPORT, "New report " + created.reportCode() + " from "
                    + actor.displayName() + " (" + concern.name() + ") was sent to you. Open it to review.");
        } else {
            notifications.notifyAdmins(Notification.REPORT, "New report " + created.reportCode() + " from "
                    + actor.displayName() + " (" + concern.name() + ") has no available " + recipient
                    + ". Please review it.");
        }
        return created;
    }

    /** ATTORNEY → JusticeCare, PSYCHIATRIST → ThoMatt; null when not given. */
    private static ServiceType recipientService(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return null;
        }
        return switch (recipient.strip().toUpperCase(Locale.ROOT)) {
            case "ATTORNEY", "JUSTICE_CARE" -> ServiceType.JUSTICE_CARE;
            case "PSYCHIATRIST", "THOMATT" -> ServiceType.THOMATT;
            default -> throw new ValidationException("Choose whether the report goes to an attorney or a psychiatrist.");
        };
    }

    /**
     * Reports visible to the actor: all (admin), assigned (professional) or own (client).
     * {@code filter} is a status code/label, or "all"/empty for every status.
     */
    @Transactional(readOnly = true)
    public List<Report> list(Account actor, String filter) {
        ReportStatus status = null;
        if (filter != null && !filter.isEmpty() && !filter.equalsIgnoreCase("all")) {
            status = ReportStatus.parse(filter).orElseThrow(() -> new ValidationException("Unknown report status filter."));
        }
        return reports.list(scopeFor(actor), status);
    }

    @Transactional(readOnly = true)
    public Report get(Account actor, String id) {
        return reports.findById(InputRules.positiveId(id, "report id"))
                .filter(actor::canAccessReport)
                .orElseThrow(() -> new NotFoundException("Report not found."));
    }

    /**
     * Sets the status and/or appends remarks. Admins may update any report; attorneys and
     * psychiatrists only the reports sent to them. The status can be changed again at any time.
     */
    @Transactional
    public Report updateStatus(Account actor, String id, UpdateReportStatusRequest request) {
        return update(actor, InputRules.positiveId(id, "report id"), request.status(), request.remarks());
    }

    /** {@code status} null/blank means remarks only. */
    @Transactional
    public Report update(Account actor, int reportId, String statusValue, String remarks) {
        boolean admin = actor.can(Permission.REPORTS_UPDATE_STATUS);
        boolean professional = actor instanceof Professional && actor.can(Permission.REPORTS_READ_ASSIGNED);
        if (!admin && !professional) {
            throw new ForbiddenException();
        }
        ReportStatus status = null;
        if (statusValue != null && !statusValue.isBlank()) {
            status = ReportStatus.parse(statusValue)
                    .orElseThrow(() -> new ValidationException("Choose a valid report status."));
        }
        if (status == null && remarks == null) {
            throw new ValidationException("Choose a status or enter remarks before saving.");
        }
        ReportRepository.LockedReport report = reports.lock(reportId, null)
                .orElseThrow(() -> new NotFoundException("Report not found."));
        if (!admin && (report.assignedProfessionalId() == null || report.assignedProfessionalId() != actor.id())) {
            throw new NotFoundException("Report not found.");
        }
        if (status != null && status != report.status()) {
            reports.updateStatus(reportId, status);
        }
        if (remarks != null) {
            reports.appendRemarks(reportId, remarks);
        }
        String change = status != null ? "marked " + status.label() : "updated with remarks";
        String details = "Report " + report.reportCode() + " " + change + " by " + actor.displayName()
                + (remarks != null ? ". Remarks: " + remarks : "");
        activity.log(new ActivityRepository.NewActivity(actor.id(), "REPORT", reportId,
                status != null ? status.name() : "ADD_REMARKS", details));
        notifications.create(report.clientId(), Notification.REPORT,
                "Your report " + report.reportCode() + " was " + change + ".");
        Integer assigned = report.assignedProfessionalId();
        if (assigned != null && assigned != actor.id()) {
            notifications.create(assigned, Notification.REPORT, details);
        }
        return reports.findById(reportId).orElseThrow();
    }

    private static ReportRepository.Scope scopeFor(Account actor) {
        if (actor.can(Permission.REPORTS_READ_ALL)) {
            return new ReportRepository.Scope.All();
        }
        if (actor instanceof Professional && actor.can(Permission.REPORTS_READ_ASSIGNED)) {
            return new ReportRepository.Scope.AssignedTo(actor.id());
        }
        if (actor.can(Permission.REPORTS_READ_OWN)) {
            return new ReportRepository.Scope.OwnedBy(actor.id());
        }
        throw new ForbiddenException();
    }
}
