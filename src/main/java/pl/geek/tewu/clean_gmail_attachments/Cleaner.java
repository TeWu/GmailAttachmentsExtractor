package pl.geek.tewu.clean_gmail_attachments;

import com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import org.apache.commons.codec.digest.DigestUtils;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.MailDateFormat;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class Cleaner {
    public static final String DELETED_FILE_PREFIX = "Deleted ";

    private Gmail gmail;
    private Gmail.Users.Labels gmailLabels;
    private Gmail.Users.Messages gmailMessages;
    private String userId;
    private Map<String, Label> labelsByName;
    private Path rootOutput;
    private int globalUniqueNum;


    public Cleaner(Gmail gmail, String userId, Path rootOutput) {
        this.gmail = gmail;
        this.userId = userId;
        this.gmailLabels = gmail.users().labels();
        this.gmailMessages = gmail.users().messages();
        this.rootOutput = rootOutput.toAbsolutePath();
        this.globalUniqueNum = 0;
    }

    public boolean clean(String queryString, String outLabelNamePrefix) throws IOException, MessagingException, ParseException {
        buildLabelsByName();
        Label outLabelWithAttachments = getOrCreateLabel(outLabelNamePrefix + " [with attachments]");
        Label outLabelNoAttachments = getOrCreateLabel(outLabelNamePrefix + " [no attachments]");

        List<Message> msgs = gmailMessages.list(userId).setQ(queryString).execute().getMessages();
        if (msgs == null || msgs.isEmpty()) {
            System.out.println("No messages matched query '" + queryString + "' - Terminating.");
            return false;
        }
        System.out.println("Query matched " + msgs.size() + " messages");

//        for (Message msgIds : Collections.singletonList(msgs.get(0))) {  // TODO: <-- process all messages, not only first one
        for (Message msgIds : msgs) {
            System.out.println(msgIds);
            // TODO: Check if msg has attachments that require re-uploading before downloading raw message (with all attachments)
            Message rawMsg = getRawMessage(msgIds.getId());
            MimeMessage mimeMsg = rawMessageToMimeMessage(rawMsg);
//          System.out.println(new String(com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64.decodeBase64(rawMsg.getRaw())));


            Instant receiveDate = new MailDateFormat().parse(mimeMsg.getHeader("Date", null)).toInstant();
            BodyPart[] parts = getParts(mimeMsg);
            List<Integer> attachmentPartIndexes = new ArrayList<>();
            Path attachmentsDir = createDirForAttachments(receiveDate, mimeMsg.getSubject());
            for (int i = 0; i < parts.length; i++) {
                String fileName = parts[i].getFileName();
                if (fileName != null && !fileName.isEmpty())
                    attachmentPartIndexes.add(i);
            }
            for (int idx : attachmentPartIndexes) {
                BodyPart part = parts[idx];
                String fileName = part.getFileName();
                Path filePath = attachmentsDir.resolve(fileName);

                saveToFile(part, filePath);

                String descriptor = buildDescriptorString(part, receiveDate, Files.size(filePath));  // buildDescriptorString must be called BEFORE modifying the part
                part.setFileName(DELETED_FILE_PREFIX + fileName + ".txt");
                part.setText(descriptor);
            }
            if (!attachmentPartIndexes.isEmpty()) // TODO
                setParts(mimeMsg, parts);


            // TODO
            if (false && !attachmentPartIndexes.isEmpty()) {
                // Build message based on mimeMsg and rawMsg and insert it to Gmail
                Message msg = mimeMessageToMessage(mimeMsg);
                List<String> labelIds = rawMsg.getLabelIds();
                labelIds.add(outLabelNoAttachments.getId());
                msg.setLabelIds(labelIds);
                msg.setThreadId(rawMsg.getThreadId());
                Message insertedMsg = insertMessage(msg);
                System.out.println("Inserted: " + insertedMsg);

                // Add label to the original message
                addLabelToMessage(rawMsg, outLabelWithAttachments);
            }
        }

        return true;
    }

    private Path createDirForAttachments(Instant receiveDate, String messageSubject) {
        final String receiveDateStr = DateTimeFormatter.ofPattern("yyyy.MM.dd HH_mm_ss").withZone(ZoneId.systemDefault())
                .format(receiveDate);
        final String dirName = receiveDateStr + " " + Utils.sanitizeDirname(messageSubject);
        Path outDir = rootOutput.resolve(dirName);
        int i = 0;

        // Find unique name for attachments directory
        while (outDir.toFile().exists() && i < 10)
            outDir = outDir.resolveSibling(dirName + " " + i++);
        if (outDir.toFile().exists()) outDir = outDir.resolveSibling(receiveDateStr);
        if (outDir.toFile().exists()) outDir = outDir.resolveSibling(receiveDateStr + " " + globalUniqueNum++);
        if (outDir.toFile().exists()) throw new RuntimeException("Can't find unique name for attachments directory");

        // Create attachments directory
        if (!outDir.toFile().mkdir()) throw new RuntimeException("Can't create attachments directory '" + outDir + "'");
        return outDir;
    }

    private String buildDescriptorString(BodyPart part, Instant receiveDate, long fileSize) throws IOException, MessagingException {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss O").withZone(ZoneId.systemDefault());
        return "File has been deleted from email on " + formatter.format(ZonedDateTime.now()) + "\r\n" +
                "Email received on " + formatter.format(receiveDate) + "\r\n" +
                "== File info ==\r\n" +
                "Name: " + part.getFileName() + "\r\n" +
                "Size: " + fileSize + " bytes\r\n" +
                "SHA1: " + DigestUtils.sha1Hex(part.getInputStream()) + "\r\n" +
                "MD5: " + DigestUtils.md5Hex(part.getInputStream()) + "\r\n";
    }


    private void saveToFile(BodyPart part, Path filePath) throws IOException, MessagingException {
        Utils.copyInputStreamToFile(part.getInputStream(), filePath.toFile());
    }


    private BodyPart[] getParts(MimeMessage mimeMessage) throws IOException, MessagingException {
        Object content = mimeMessage.getContent();
        if (content instanceof Multipart) {
            Multipart multipart = (Multipart) content;
            BodyPart[] result = new BodyPart[multipart.getCount()];
            for (int i = 0; i < multipart.getCount(); i++)
                result[i] = multipart.getBodyPart(i);
            return result;
        }
        return new BodyPart[0];
    }

    private void setParts(MimeMessage mimeMessage, BodyPart... parts) throws IOException, MessagingException {
        Object content = mimeMessage.getContent();
        if (content instanceof Multipart) {
            Multipart oldMultipart = (Multipart) content;
            String contentType = oldMultipart.getContentType();
            String subType = contentType.substring(contentType.indexOf("/") + 1, contentType.indexOf(";"));
            mimeMessage.setContent(new MimeMultipart(subType, parts));
            mimeMessage.saveChanges();
        } else throw new IllegalStateException("mimeMessage should have Multipart content");
    }


    private void buildLabelsByName() throws IOException {
        labelsByName = new HashMap<>();
        ListLabelsResponse labelsResp = gmailLabels.list(userId).execute();
        if (!labelsResp.isEmpty())
            for (Label label : labelsResp.getLabels())
                labelsByName.put(label.getName(), label);
    }

    private Label getOrCreateLabel(String name) throws IOException {
        return labelsByName.containsKey(name) ? labelsByName.get(name) : createLabel(name);
    }

    private Label createLabel(String name) throws IOException {
        Label label = new Label()
                .setName(name)
                .setLabelListVisibility("labelShow")
                .setMessageListVisibility("show");
        return gmailLabels.create(userId, label).execute();
    }

    private void addLabelToMessage(Message message, Label label) throws IOException {
        ModifyMessageRequest modReq = new ModifyMessageRequest().setAddLabelIds(Collections.singletonList(label.getId()));
        gmailMessages.modify(userId, message.getId(), modReq).execute();
    }

    private Message insertMessage(Message message) throws IOException {
        return gmailMessages.insert(userId, message)
                .setInternalDateSource("dateHeader")  // The GMail internal message time is based on the Date header in the email, when valid.
                .execute();
    }

    private Message getRawMessage(String messageId) throws IOException {
        return gmailMessages.get(userId, messageId)
                .setFormat("raw")
                .execute();
    }

    private MimeMessage rawMessageToMimeMessage(Message message) throws MessagingException {
        Session session = Session.getDefaultInstance(new Properties(), null);
        return new MimeMessage(session, new ByteArrayInputStream(Base64.decodeBase64(message.getRaw())));
    }

    private Message mimeMessageToMessage(MimeMessage mimeMessage) throws MessagingException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mimeMessage.writeTo(baos);
        String encodedEmail = Base64.encodeBase64URLSafeString(baos.toByteArray());
        Message message = new Message();
        message.setRaw(encodedEmail);
        return message;
    }
}