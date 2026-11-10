package com.talkcare.api.service;

import com.talkcare.api.config.TalkCareProperties;
import com.talkcare.api.domain.Permission;
import com.talkcare.api.domain.ServiceType;
import com.talkcare.api.domain.account.Account;
import com.talkcare.api.domain.account.Client;
import com.talkcare.api.domain.account.Professional;
import com.talkcare.api.domain.activity.Notification;
import com.talkcare.api.domain.appointment.Appointment;
import com.talkcare.api.domain.appointment.AppointmentStatus;
import com.talkcare.api.domain.chat.Conversation;
import com.talkcare.api.error.ConflictException;
import com.talkcare.api.error.ForbiddenException;
import com.talkcare.api.error.NotFoundException;
import com.talkcare.api.error.ValidationException;
import com.talkcare.api.repository.ActivityRepository;
import com.talkcare.api.repository.AppointmentRepository;
import com.talkcare.api.repository.LookupRepository;
import com.talkcare.api.repository.NotificationRepository;
import com.talkcare.api.web.dto.BookAppointmentRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessions between a client and the professional assigned to them. Clients request a time,
 * the professional confirms, completes or cancels it, and administrators see every session.
 */
@Service
public class AppointmentService {

    private static final Set<Integer> DURATIONS = Set.of(30, 45, 60, 90);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.US);

    private final AppointmentRepository appointments;
    private final ChatService chat;
    private final LookupRepository lookups;
    private final NotificationRepository notifications;
    private final ActivityRepository activity;
    private final ZoneId timezone;

    public AppointmentService(AppointmentRepository appointments, ChatService chat, LookupRepository lookups,
                              NotificationRepository notifications, ActivityRepository activity,
                              TalkCareProperties properties) {
        this.appointments = appointments;
        this.chat = chat;
        this.lookups = lookups;
        this.notifications = notifications;
        this.activity = activity;
        this.timezone = properties.timezone();
    }

    /** {@code start} is a local date-time in the app time zone, e.g. {@code 2026-11-05T14:00}. */
    @Transactional
    public Appointment book(Account actor, BookAppointmentRequest request) {
        if (!(actor instanceof Client) || !actor.can(Permission.APPOINTMENTS_BOOK_OWN)) {
            throw new ForbiddenException("Only client accounts can book a session.");
        }
        InputRules.requireVerifiedClient(actor);
        ServiceType service = ServiceType.fromCode(request.serviceType())
                .orElseThrow(() -> new ValidationException("Please choose a valid TalkCare service."));
        int minutes = request.durationMinutes() == null ? 60 : request.durationMinutes();
        if (!DURATIONS.contains(minutes)) {
            throw new ValidationException("Sessions last 30, 45, 60 or 90 minutes.");
        }
        Instant start = parseStart(request.start());
        if (start.isBefore(Instant.now().plus(Duration.ofMinutes(30)))) {
            throw new ValidationException("Please choose a time at least 30 minutes from now.");
        }
        if (start.isAfter(Instant.now().plus(Duration.ofDays(90)))) {
            throw new ValidationException("Sessions can be booked up to 90 days ahead.");
        }
        // The session is with the professional already assigned to the client for this service.
        Conversation conversation = chat.openConversation(actor, service.name());
        int serviceTypeId = lookups.findServiceType(service).orElseThrow().id();
        int id = appointments.create(new AppointmentRepository.NewAppointment(actor.id(), conversation.professionalId(),
                serviceTypeId, start, start.plus(Duration.ofMinutes(minutes)), request.notes(), actor.id()));
        Appointment created = appointments.findById(id).orElseThrow();
        notifications.create(created.professionalId(), Notification.SYSTEM,
                actor.displayName() + " requested a session on " + when(created) + ".");
        activity.log(new ActivityRepository.NewActivity(actor.id(), "APPOINTMENT", id, "REQUESTED",
                "Session requested by " + actor.displayName() + " for " + when(created)));
        return created;
    }

    /**
     * Clients see their own sessions and professionals the ones assigned to them. Administrators
     * see one day ({@code date=YYYY-MM-DD}) or, without a date, every upcoming session.
     */
    public List<Appointment> list(Account actor, String date) {
        if (actor instanceof Client) {
            return appointments.listForClient(actor.id());
        }
        if (actor instanceof Professional && actor.can(Permission.APPOINTMENTS_READ_ASSIGNED)) {
            return appointments.listForProfessional(actor.id());
        }
        if (!actor.can(Permission.ADMIN_DASHBOARD)) {
            throw new ForbiddenException();
        }
        if (date == null || date.isBlank()) {
            return appointments.listUpcoming(100);
        }
        LocalDate day;
        try {
            day = LocalDate.parse(date.strip());
        } catch (DateTimeParseException ex) {
            throw new ValidationException("Invalid date. Use YYYY-MM-DD.");
        }
        return appointments.listBetween(day.atStartOfDay(timezone).toInstant(),
                day.plusDays(1).atStartOfDay(timezone).toInstant());
    }

    /**
     * The assigned professional confirms, completes, cancels or marks a no-show; the client can only
     * cancel; administrators can do anything. Finished sessions cannot change.
     */
    @Transactional
    public Appointment updateStatus(Account actor, int id, String statusCode) {
        Appointment appointment = appointments.findById(id).orElseThrow(() -> new NotFoundException("Session not found."));
        AppointmentStatus target;
        try {
            target = AppointmentStatus.valueOf(statusCode == null ? "" : statusCode.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Unknown session status.");
        }
        boolean isClient = appointment.clientId() == actor.id();
        boolean isProfessional = appointment.professionalId() == actor.id()
                && actor.can(Permission.APPOINTMENTS_MANAGE_ASSIGNED);
        boolean isAdmin = actor.can(Permission.ADMIN_DASHBOARD);
        if (!isClient && !isProfessional && !isAdmin) {
            throw new NotFoundException("Session not found.");
        }
        if (isClient && !isAdmin && target != AppointmentStatus.CANCELLED) {
            throw new ForbiddenException("You can only cancel your own session.");
        }
        if (target == AppointmentStatus.SCHEDULED) {
            throw new ValidationException("Unknown session status.");
        }
        if (!appointment.status().isActive()) {
            throw new ConflictException("This session is already " + appointment.status().label().toLowerCase(Locale.ROOT) + ".");
        }
        appointments.updateStatus(id, target);
        Appointment updated = appointments.findById(id).orElseThrow();
        String message = "Your session on " + when(updated) + " is now " + target.label().toLowerCase(Locale.ROOT)
                + " (by " + actor.displayName() + ").";
        if (actor.id() != updated.clientId()) {
            notifications.create(updated.clientId(), Notification.SYSTEM, message);
        }
        if (actor.id() != updated.professionalId()) {
            notifications.create(updated.professionalId(), Notification.SYSTEM, message);
        }
        activity.log(new ActivityRepository.NewActivity(actor.id(), "APPOINTMENT", id, target.name(),
                "Session on " + when(updated) + " marked " + target.label() + " by " + actor.displayName()));
        return updated;
    }

    private Instant parseStart(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("Please choose a date and time.");
        }
        try {
            return LocalDateTime.parse(value.strip()).atZone(timezone).toInstant();
        } catch (DateTimeParseException ex) {
            throw new ValidationException("Invalid date and time.");
        }
    }

    private String when(Appointment a) {
        return WHEN.format(a.start().atZone(timezone));
    }
}
