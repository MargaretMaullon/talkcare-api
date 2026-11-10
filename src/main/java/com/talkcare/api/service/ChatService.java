package com.talkcare.api.service;

import com.talkcare.api.domain.Permission;
import com.talkcare.api.domain.ServiceType;
import com.talkcare.api.domain.account.Account;
import com.talkcare.api.domain.account.Client;
import com.talkcare.api.domain.account.Professional;
import com.talkcare.api.domain.activity.Notification;
import com.talkcare.api.domain.chat.Conversation;
import com.talkcare.api.domain.chat.Message;
import com.talkcare.api.domain.lookup.ServiceTypeInfo;
import com.talkcare.api.error.ConflictException;
import com.talkcare.api.error.ForbiddenException;
import com.talkcare.api.error.NotFoundException;
import com.talkcare.api.error.ServiceUnavailableException;
import com.talkcare.api.error.ValidationException;
import com.talkcare.api.repository.ActivityRepository;
import com.talkcare.api.repository.ConversationRepository;
import com.talkcare.api.repository.LookupRepository;
import com.talkcare.api.repository.MessageRepository;
import com.talkcare.api.repository.MessageRepository.NewMessage;
import com.talkcare.api.repository.NotificationRepository;
import com.talkcare.api.storage.FileStorage;
import com.talkcare.api.storage.UploadPolicy;
import com.talkcare.api.web.dto.SendMessageRequest;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Client ↔ professional conversations. */
@Service
public class ChatService {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final LookupRepository lookups;
    private final FileStorage storage;
    private final NotificationRepository notifications;
    private final TransactionTemplate transaction;
    private final ActivityRepository activity;

    public ChatService(ConversationRepository conversations, MessageRepository messages, LookupRepository lookups,
                       FileStorage storage, NotificationRepository notifications, TransactionTemplate transaction,
                       ActivityRepository activity) {
        this.activity = activity;
        this.conversations = conversations;
        this.messages = messages;
        this.lookups = lookups;
        this.storage = storage;
        this.notifications = notifications;
        this.transaction = transaction;
    }

    /**
     * Returns the client's open conversation for a service, creating it (assigned to the
     * least-loaded professional, with a welcome message) if needed.
     */
    public Conversation openConversation(Account actor, String serviceType) {
        if (!(actor instanceof Client)) {
            throw new ForbiddenException("Only client accounts can open a conversation.");
        }
        InputRules.requireVerifiedClient(actor);
        ServiceType service = ServiceType.fromCode(serviceType)
                .orElseThrow(() -> new ValidationException("Please choose a valid TalkCare service."));
        return conversations.findOpen(actor.id(), service).orElseGet(() -> create(actor, service));
    }

    private Conversation create(Account client, ServiceType service) {
        ServiceTypeInfo info = lookups.findServiceType(service).orElseThrow();
        int professionalId = conversations.pickProfessional(service)
                .orElseThrow(() -> new ServiceUnavailableException("No professional is currently available for this service."));
        try {
            Integer id = transaction.execute(status -> {
                int conversationId = conversations.create(new ConversationRepository.NewConversation(
                        client.id(), professionalId, info.id(), service.conversationTitle()));
                Conversation created = conversations.findById(conversationId).orElseThrow();
                String name = created.professionalName().isEmpty() ? "your assigned professional" : created.professionalName();
                messages.create(NewMessage.from(conversationId, professionalId, service.welcomeMessage(name), null));
                activity.log(new ActivityRepository.NewActivity(client.id(), "CONVERSATION", conversationId, "OPENED",
                        client.displayName() + " started a " + created.title() + " conversation with " + name));
                return conversationId;
            });
            return conversations.findById(id).orElseThrow();
        } catch (DuplicateKeyException ex) {
            // A concurrent request opened it first (conversations_one_open_per_service_uk).
            return conversations.findOpen(client.id(), service).orElseThrow(() -> ex);
        }
    }

    /** Professionals see their own conversations; account managers see all. */
    public List<Conversation> listAssigned(Account actor) {
        if (actor instanceof Professional && actor.can(Permission.CONVERSATIONS_READ_ASSIGNED)) {
            return conversations.listForProfessional(actor.id());
        }
        if (actor.can(Permission.USERS_MANAGE)) {
            return conversations.listAll();
        }
        throw new ForbiddenException();
    }

    public Conversation getConversation(Account actor, String conversationId) {
        return loadAccessible(actor, conversationId);
    }

    public List<Message> listMessages(Account actor, String conversationId) {
        return messages.listByConversation(loadAccessible(actor, conversationId).id());
    }

    /** The client's message plus the automatic acknowledgement, atomically. Returns both. */
    public List<Message> sendClientMessage(Account actor, String conversationId, SendMessageRequest request) {
        Conversation conversation = loadAccessible(actor, conversationId);
        if (!conversation.belongsToClient(actor.id())) {
            throw new ForbiddenException("Only the client can send messages here.");
        }
        InputRules.requireVerifiedClient(actor);
        if (!conversation.isOpen()) {
            throw new ConflictException("This conversation is closed.");
        }
        if (request.messageText() == null && request.attachmentData() == null) {
            throw new ValidationException("Write a message or attach a photo before sending.");
        }
        FileStorage.StoredFile photo = request.attachmentData() == null ? null
                : storage.saveDataUrl(request.attachmentData(), UploadPolicy.photo("chat", "photo"));
        Message.Attachment attachment = photo == null ? null
                : new Message.Attachment(photo.url(), photo.mimeType(), photo.byteSize());
        try {
            List<Integer> ids = transaction.execute(status -> {
                int own = messages.create(NewMessage.from(conversation.id(), actor.id(), request.messageText(), attachment));
                int auto = messages.create(NewMessage.autoReply(conversation.id()));
                conversations.touch(conversation.id());
                notifications.create(conversation.professionalId(), Notification.MESSAGE,
                        actor.displayName() + " sent a message in " + conversation.title() + ".");
                activity.log(new ActivityRepository.NewActivity(actor.id(), "MESSAGE", own, "SENT",
                        actor.displayName() + " sent a message in " + conversation.title()));
                return List.of(own, auto);
            });
            return messages.listByIds(ids);
        } catch (RuntimeException ex) {
            if (photo != null) {
                storage.remove(photo.url());
            }
            throw ex;
        }
    }

    /** The professional's reply; the client gets an in-app notification in the same transaction. */
    public Message sendProfessionalReply(Account actor, String conversationId, String text) {
        Conversation conversation = loadAccessible(actor, conversationId);
        if (text == null) {
            throw new ValidationException("Write a reply before sending.");
        }
        Integer id = transaction.execute(status -> {
            int messageId = messages.create(NewMessage.from(conversation.id(), actor.id(), text, null));
            conversations.touch(conversation.id());
            notifications.create(conversation.clientId(), Notification.MESSAGE,
                    actor.displayName() + " replied to your " + conversation.title() + " conversation.");
            activity.log(new ActivityRepository.NewActivity(actor.id(), "MESSAGE", messageId, "REPLIED",
                    actor.displayName() + " replied in " + conversation.title()));
            return messageId;
        });
        return messages.listByIds(List.of(id)).getFirst();
    }

    /** Not-found rather than forbidden, so ids of other people's conversations are not revealed. */
    private Conversation loadAccessible(Account actor, String conversationId) {
        return conversations.findById(InputRules.positiveId(conversationId, "conversation id"))
                .filter(actor::canAccessConversation)
                .orElseThrow(() -> new NotFoundException("The conversation could not be found."));
    }
}
