package com.talkcare.api.service;

import com.talkcare.api.domain.account.Account;
import com.talkcare.api.domain.activity.Notification;
import com.talkcare.api.repository.AccountRepository;
import com.talkcare.api.repository.ActivityRepository;
import com.talkcare.api.repository.LookupRepository;
import com.talkcare.api.web.dto.ContactRequest;
import com.talkcare.api.repository.NotificationRepository;
import com.talkcare.api.web.dto.LookupsResponse;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Small read-mostly features: database health, reference data, notifications. */
@Service
public class SupportService {

    private final JdbcClient jdbc;
    private final LookupRepository lookups;
    private final NotificationRepository notifications;
    private final AccountRepository accounts;
    private final ActivityRepository activity;

    public SupportService(JdbcClient jdbc, LookupRepository lookups, NotificationRepository notifications,
                          AccountRepository accounts, ActivityRepository activity) {
        this.jdbc = jdbc;
        this.lookups = lookups;
        this.notifications = notifications;
        this.accounts = accounts;
        this.activity = activity;
    }

    /** Throws {@link DataAccessException} when the database is unreachable. */
    public void checkDatabase() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
    }

    @Transactional(readOnly = true)
    public LookupsResponse lookups() {
        return LookupsResponse.of(lookups.listServiceTypes(), lookups.listConcernTypes(), lookups.listIdTypes());
    }

    public List<Notification> notifications(Account actor) {
        return notifications.findForUser(actor.id());
    }

    /**
     * A contact-form message: every active administrator gets it as a notification, and it is
     * written to the activity log. {@code actor} is null for visitors who are not signed in.
     */
    @Transactional
    public void contact(Account actor, ContactRequest request) {
        String text = "Contact message from " + request.name() + " <" + request.email() + ">: " + request.message();
        activity.log(new ActivityRepository.NewActivity(actor == null ? null : actor.id(), "CONTACT",
                actor == null ? 0 : actor.id(), "MESSAGE", text.length() > 500 ? text.substring(0, 500) + "…" : text));
    }

    public void markNotificationsRead(Account actor) {
        notifications.markAllRead(actor.id());
    }
}
