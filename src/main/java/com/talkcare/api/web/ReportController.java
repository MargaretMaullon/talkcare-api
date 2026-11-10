package com.talkcare.api.web;

import com.talkcare.api.domain.Permission;
import com.talkcare.api.domain.account.Account;
import com.talkcare.api.service.ReportService;
import com.talkcare.api.web.dto.CreateReportRequest;
import com.talkcare.api.web.dto.ReportResponse;
import com.talkcare.api.web.dto.UpdateReportStatusRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permission.REPORTS_CREATE + "')")
    public ReportResponse submit(@AuthenticationPrincipal Account actor, @Valid @RequestBody CreateReportRequest request) {
        return ReportResponse.from(reports.submit(actor, request));
    }

    /** {@code ?status=} (or the older {@code ?filter=}): a status code/label, or "all". */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('" + Permission.REPORTS_READ_OWN + "', '" + Permission.REPORTS_READ_ASSIGNED
            + "', '" + Permission.REPORTS_READ_ALL + "')")
    public List<ReportResponse> list(@AuthenticationPrincipal Account actor,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String filter) {
        return reports.list(actor, status != null ? status : filter).stream().map(ReportResponse::from).toList();
    }

    /** Visible to the owning client, the assigned professional, and admins. */
    @GetMapping("/{id}")
    public ReportResponse get(@AuthenticationPrincipal Account actor, @PathVariable String id) {
        return ReportResponse.from(reports.get(actor, id));
    }

    /** Admins update any report; attorneys and psychiatrists the reports sent to them. */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('" + Permission.REPORTS_UPDATE_STATUS + "', '" + Permission.REPORTS_READ_ASSIGNED + "')")
    public ReportResponse updateStatus(@AuthenticationPrincipal Account actor, @PathVariable String id,
                                       @Valid @RequestBody UpdateReportStatusRequest request) {
        return ReportResponse.from(reports.updateStatus(actor, id, request));
    }
}
