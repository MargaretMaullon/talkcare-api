package com.talkcare.api.mail;

import com.talkcare.api.config.TalkCareProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Plain-text email over SMTP, configured by the {@code TALKCARE_SMTP_*} variables. Port 465 uses
 * implicit TLS; any other port requires STARTTLS. When {@code TALKCARE_BREVO_API_KEY} is set, mail
 * is sent through Brevo's HTTPS API instead (for hosts that block outbound SMTP).
 */
@Service
public class EmailService {

    private static final String TIMEOUT_MILLIS = "15000";

    private static final String BREVO_URL = "https://api.brevo.com/v3/smtp/email";

    private final TalkCareProperties.Smtp smtp;
    private final JavaMailSenderImpl sender;
    private final RestClient brevo;

    public EmailService(TalkCareProperties properties) {
        this.smtp = properties.smtp();
        this.brevo = smtp.usesBrevo() ? createBrevoClient() : null;
        this.sender = brevo == null && smtp.isConfigured() ? createSender(smtp) : null;
    }

    public boolean isConfigured() {
        return brevo != null || sender != null;
    }

    public void send(String to, String subject, String text) {
        if (brevo != null) {
            brevo.post()
                    .uri(BREVO_URL)
                    .header("api-key", smtp.brevoApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "sender", Map.of("email", smtp.from(), "name", "TalkCare"),
                            "to", List.of(Map.of("email", to)),
                            "subject", subject,
                            "textContent", text))
                    .retrieve()
                    .toBodilessEntity();
            return;
        }
        if (sender == null) {
            throw new IllegalStateException("SMTP is not configured.");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(smtp.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        sender.send(message);
    }

    private static RestClient createBrevoClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(Long.parseLong(TIMEOUT_MILLIS)));
        return RestClient.builder().requestFactory(factory).build();
    }

    private static JavaMailSenderImpl createSender(TalkCareProperties.Smtp smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setUsername(smtp.username());
        sender.setPassword(smtp.password());
        boolean implicitTls = smtp.port() == 465;
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", Boolean.toString(implicitTls));
        props.put("mail.smtp.starttls.enable", Boolean.toString(!implicitTls));
        props.put("mail.smtp.starttls.required", Boolean.toString(!implicitTls));
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        return sender;
    }
}
