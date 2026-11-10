package com.talkcare.api.web;

import com.talkcare.api.domain.Permission;
import com.talkcare.api.domain.account.Account;
import com.talkcare.api.service.AdminService;
import com.talkcare.api.service.InputRules;
import com.talkcare.api.web.dto.AccountResponse;
import com.talkcare.api.web.dto.ActivityResponse;
import com.talkcare.api.web.dto.PendingRequestResponse;
import com.talkcare.api.web.dto.ProcessRequestRequest;
import com.talkcare.api.web.dto.SimpleResponses.Metrics;
import com.talkcare.api.web.dto.SimpleResponses.Schedule;
import com.talkcare.api.web.dto.SimpleResponses.Success;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final String CAN_REVIEW = "hasAnyAuthority('" + Permission.VERIFICATIONS_REVIEW + "', '"
            + Permission.REPORTS_UPDATE_STATUS + "', '" + Permission.USERS_MANAGE + "')";

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/metrics")
    @PreAuthorize("hasAuthority('" + Permission.ADMIN_DASHBOARD + "')")
    public Metrics metrics() {
        AdminService.Metrics m = admin.metrics();
        return new Metrics(m.totalUsers(), m.pendingApprovals(), m.sessionsToday());
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('" + Permission.USERS_READ + "')")
    public List<AccountResponse> users() {
        return admin.users().stream().map(AccountResponse::from).toList();
    }

    /** Body: {@code {"action": "SUSPEND" | "DEACTIVATE" | "REACTIVATE"}}. */
    @PatchMapping("/users/{id}/status")
    @PreAuthorize("hasAuthority('" + Permission.USERS_MANAGE + "')")
    public AccountResponse setUserStatus(@AuthenticationPrincipal Account actor, @PathVariable String id,
                                         @RequestBody java.util.Map<String, String> body) {
        return AccountResponse.from(admin.setUserStatus(actor, InputRules.positiveId(id, "user id"),
                body == null ? null : body.get("action")));
    }

    @GetMapping("/pending-requests")
    @PreAuthorize(CAN_REVIEW)
    public List<PendingRequestResponse> pendingRequests(@AuthenticationPrincipal Account actor) {
        AdminService.PendingRequests pending = admin.pendingRequests(actor);
        return PendingRequestResponse.merge(pending.verifications(), pending.reports(), pending.passwordResets());
    }

    @PostMapping("/pending-requests/action")
    @PreAuthorize(CAN_REVIEW)
    public Success processRequest(@AuthenticationPrincipal Account actor, @Valid @RequestBody ProcessRequestRequest request) {
        admin.processRequest(actor, request);
        return Success.OK;
    }

    @GetMapping("/activity")
    @PreAuthorize("hasAuthority('" + Permission.ADMIN_ACTIVITY_READ + "')")
    public List<ActivityResponse> activity() {
        return admin.recentActivity().stream().map(ActivityResponse::from).toList();
    }

    @GetMapping("/schedule")
    @PreAuthorize("hasAuthority('" + Permission.ADMIN_DASHBOARD + "')")
    public Schedule schedule(@RequestParam(required = false) String year, @RequestParam(required = false) String month) {
        AdminService.Schedule s = admin.schedule(year, month);
        return new Schedule(s.year(), s.month(), s.monthName(), s.days());
    }
}
