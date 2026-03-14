package com.inboxintelligence.ingester.persistence.storage;

import com.inboxintelligence.ingester.config.EmailStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Slf4j
@RequiredArgsConstructor
public class LocalEmailStorageProvider implements EmailStorageProvider {

    private final EmailStorageProperties properties;

    @Override
    public String storeRawMessage(Long mailboxId, String messageId, String rawMessage) {

        if (rawMessage == null) return null;

        try {
            Path dir = buildContentStoragePath(mailboxId, messageId);
            Files.createDirectories(dir);

            Path p = dir.resolve("raw_message.json");
            Files.writeString(p, rawMessage, StandardCharsets.UTF_8);

            log.info("Raw message stored locally for message {}", messageId);
            return p.toString();

        } catch (IOException e) {
            log.error("Failed to store raw message for mailbox {} message {}", mailboxId, messageId, e);
            throw new RuntimeException("Failed to store raw message", e);
        }
    }

    @Override
    public String storeTextBody(Long mailboxId, String messageId, String textBody) {

        if (textBody == null) return null;

        try {
            Path dir = buildContentStoragePath(mailboxId, messageId);
            Files.createDirectories(dir);

            Path p = dir.resolve("body.txt");
            Files.writeString(p, textBody, StandardCharsets.UTF_8);

            log.info("Text body stored locally for message {}", messageId);
            return p.toString();

        } catch (IOException e) {
            log.error("Failed to store text body for mailbox {} message {}", mailboxId, messageId, e);
            throw new RuntimeException("Failed to store text body", e);
        }
    }

    @Override
    public String storeHtmlBody(Long mailboxId, String messageId, String htmlBody) {

        if (htmlBody == null) return null;

        try {
            Path dir = buildContentStoragePath(mailboxId, messageId);
            Files.createDirectories(dir);

            Path p = dir.resolve("body.html");
            Files.writeString(p, htmlBody, StandardCharsets.UTF_8);

            log.info("HTML body stored locally for message {}", messageId);
            return p.toString();

        } catch (IOException e) {
            log.error("Failed to store html body for mailbox {} message {}", mailboxId, messageId, e);
            throw new RuntimeException("Failed to store html body", e);
        }
    }

    @Override
    public String storeAttachment(Long mailboxId, String messageId, String fileName, byte[] data) {

        try {

            Path dir = buildContentStoragePath(mailboxId, messageId).resolve("attachment");
            Files.createDirectories(dir);

            String safeFileName = Path.of(fileName).getFileName().toString();
            Path filePath = dir.resolve(safeFileName);

            if (Files.exists(filePath)) {
                String name = safeFileName;
                String ext = "";
                int dotIdx = safeFileName.lastIndexOf('.');

                if (dotIdx > 0) {
                    name = safeFileName.substring(0, dotIdx);
                    ext = safeFileName.substring(dotIdx);
                }

                int counter = 1;
                while (Files.exists(filePath)) {
                    filePath = dir.resolve(name + "_" + counter + ext);
                    counter++;
                }
            }

            Files.write(filePath, data);
            return filePath.toString();

        } catch (IOException e) {
            log.error("Failed to store attachment {} for message {}", fileName, messageId, e);
            throw new RuntimeException("Failed to store attachment", e);
        }
    }

    @Override
    public String providerName() {
        return "local";
    }

    private Path buildContentStoragePath(Long mailboxId, String messageId) {
        return Path.of(properties.localBasePath())
                .resolve(String.valueOf(mailboxId))
                .resolve(messageId);
    }

}
