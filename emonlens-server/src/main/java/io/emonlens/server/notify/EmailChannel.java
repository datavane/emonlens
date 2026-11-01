package io.emonlens.server.notify;

import java.util.Map;
import java.util.Properties;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.storage.entity.NotifyRecordEntity;

/**
 * EMAIL 渠道（javax.mail SMTP）。
 * config: {"host":"smtp.exmail.com","port":465,"ssl":true,"username":"...","password":"...","from":"...","to":"a@b.com,c@d.com"}
 */
@Component
public class EmailChannel implements NotifyChannel {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    public String send(NotifyRecordEntity record, String configJson, String subject, String content,
                       Map<String, Object> variables) throws Exception {
        JsonNode config = objectMapper.readTree(configJson == null ? "{}" : configJson);
        String host = config.path("host").asText();
        String to = config.path("to").asText();
        if (host.isEmpty() || to.isEmpty()) {
            throw new IllegalStateException("email channel misconfigured: host/to required");
        }
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(config.path("port").asInt(465)));
        props.put("mail.smtp.auth", "true");
        // 事件消费线程同步发信：SMTP 无响应时必须尽快失败，否则整条通知/Webhook 管线都被挂住
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");
        if (config.path("ssl").asBoolean(true)) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        String username = config.path("username").asText("");
        String password = config.path("password").asText("");
        javax.mail.Session session;
        if (!username.isEmpty() && !password.isEmpty()) {
            session = javax.mail.Session.getInstance(props, new javax.mail.Authenticator() {
                @Override
                protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new javax.mail.PasswordAuthentication(username, password);
                }
            });
        } else {
            session = javax.mail.Session.getInstance(props);
        }
        javax.mail.internet.MimeMessage message = new javax.mail.internet.MimeMessage(session);
        message.setFrom(new javax.mail.internet.InternetAddress(config.path("from").asText(to)));
        for (String recipient : to.split(",")) {
            message.addRecipient(javax.mail.Message.RecipientType.TO,
                    new javax.mail.internet.InternetAddress(recipient.trim()));
        }
        message.setSubject(subject == null ? "EmonLens 通知" : subject, "UTF-8");
        message.setText(content == null ? "" : content, "UTF-8");
        javax.mail.Transport.send(message);
        return to;
    }
}
