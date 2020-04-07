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
import java.util.regex.Pattern;


public class Cleaner {
    public static final String DELETED_FILE_PREFIX = "Deleted ";

    private Gmail gmail;
    private Gmail.Users.Labels gmailLabels;
    private Gmail.Users.Messages gmailMessages;
    private String userId;
    private Map<String, Label> labelsByName;
    private Path rootOutput;
    private int globalUniqueNum;

    Pattern filenamePattern = Pattern.compile(".*", Pattern.DOTALL); // TODO
    String mimetypeSubPattern = ""; //TODO
    Pattern mimetypePattern = Pattern.compile("^" + mimetypeSubPattern + ".*", Pattern.DOTALL); // TODO
    int sizeMin = 0; // TODO
    int sizeMax = 0; // TODO


    public Cleaner(Gmail gmail, String userId, Path rootOutput) {
        this.gmail = gmail;
        this.userId = userId;
        this.gmailLabels = gmail.users().labels();
        this.gmailMessages = gmail.users().messages();
        this.rootOutput = rootOutput.toAbsolutePath();
        this.globalUniqueNum = 0;
    }

    public boolean clean(String queryString, String outLabelNamePrefix) throws IOException, MessagingException, ParseException {
        // Check if main output directory already exists
        if (rootOutput.toFile().exists()) {
            System.err.println("Output directory '" + rootOutput + "' already exists - move it or provide different output directory path - Terminating.");
            return false;
        }

        // Create output labels
        buildLabelsByName();
        String withAttLabelName = outLabelNamePrefix + " [with attachments]";
        String noAttLabelName = outLabelNamePrefix + " [no attachments]";
        if (labelsByName.containsKey(withAttLabelName) || labelsByName.containsKey(noAttLabelName)) {
            System.err.println("Labels '" + withAttLabelName + "' and/or '" + noAttLabelName + "' already exist. Running this program when this labels already exist might lead to confusing results. Please provide different output labels prefix and try again. Note that removing those labels is probably not a good solution, as it may prevent you from distinguishing between emails with attachments and its copies without attachments - Terminating.");
            return false;
        }
        Label withAttLabel = createLabel(withAttLabelName);
        Label noAttLabel = createLabel(noAttLabelName);

        // Get email messages matching queryString
        List<Message> msgs = gmailMessages.list(userId).setQ(queryString).execute().getMessages();
        if (msgs == null || msgs.isEmpty()) {
            System.out.println("No messages matched query '" + queryString + "' - Terminating.");
            return false;
        }
        System.out.println("Query matched " + msgs.size() + " messages");

        // Create main output directory
        if (!rootOutput.toFile().mkdirs()) {
            System.err.println("Can't create output directory '" + rootOutput + "' - Terminating.");
            return false;
        }

        // Process email messages
        for (Message msgIds : msgs) {
            System.out.println(msgIds);
            Message msg = gmailMessages.get(userId, msgIds.getId()).execute();
            boolean msgContainsAttachmentToExtract = msg.getPayload().getParts().stream()
                    .anyMatch(part -> {
                        if (part.getBody() == null)
                            return false;
                        return isBodyPartSatisfiesFilter(part.getFilename(), part.getMimeType(), part.getBody().getSize().longValue());
                    });
            if (!msgContainsAttachmentToExtract)
                continue;

            Message rawMsg = getRawMessage(msgIds.getId());
            MimeMessage mimeMsg = rawMessageToMimeMessage(rawMsg);
            Instant receiveDate = new MailDateFormat().parse(mimeMsg.getHeader("Date", null)).toInstant();
            Path attachmentsDir = createDirForAttachments(receiveDate, mimeMsg.getSubject());

            BodyPart[] parts = getParts(mimeMsg);
            for (BodyPart part : parts) {
                // Extract information about body part
                String fileName = part.getFileName();
                if (fileName == null || fileName.isEmpty()) // If part doesn't have a filename, then it's not an attachment - skip it (don't extract it)
                    continue;
                Path filePath = attachmentsDir.resolve(fileName);
                String mimeType = part.getContentType();
                // Save part to file
                saveToFile(part, filePath);
                // Calculate part/file size
                long fileSize = Files.size(filePath);

                // Check if part should be extracted
                if (isBodyPartSatisfiesFilter(fileName, mimeType, fileSize)) {
                    // If part should be extracted, override its content with descriptor string (effectively deleting it from email message)
                    String descriptor = buildDescriptorString(part, receiveDate, fileSize);  // buildDescriptorString must be called BEFORE modifying the part
                    part.setFileName(DELETED_FILE_PREFIX + fileName + ".txt");
                    part.setText(descriptor);
                } else {
                    // If part should not be extracted, delete it from local filesystem
                    Files.delete(filePath);
                }
            }
            setParts(mimeMsg, parts);

            // if (true) continue; // TODO: remove me

            // Build message based on mimeMsg and rawMsg and insert it to Gmail
            Message newMsg = mimeMessageToMessage(mimeMsg);
            List<String> labelIds = rawMsg.getLabelIds();
            labelIds.add(noAttLabel.getId());
            newMsg.setLabelIds(labelIds);
            newMsg.setThreadId(rawMsg.getThreadId());
            Message insertedMsg = insertMessage(newMsg);
            System.out.println("Inserted: " + insertedMsg);
            // TODO: Check if inserted successfully

            // Add label to the original message
            addLabelToMessage(rawMsg, withAttLabel);
        }

        return true;
    }


    private boolean isBodyPartSatisfiesFilter(String filename, String mimeType, Long size) {
        if (filename == null || filename.isEmpty() ||
                mimeType == null || mimeType.isEmpty() || mimeType.contains("multipart") ||
                size == null || size == 0)
            return false;
        return filenamePattern.matcher(filename).matches() &&
                mimetypePattern.matcher(mimeType).matches() &&
                (sizeMin == 0 || size >= sizeMin) && (sizeMax == 0 || size <= sizeMax);
    }

    private Path createDirForAttachments(Instant receiveDate, String messageSubject) {
        final String receiveDateStr = DateTimeFormatter.ofPattern("yyyy.MM.dd HH_mm_ss").withZone(ZoneId.systemDefault())
                .format(receiveDate);
        final String dirName = receiveDateStr + " " + Utils.sanitizeDirname(messageSubject);
        Path outDir = rootOutput.resolve(dirName);
        int i = 2;

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

    private Label createLabel(String name) throws IOException {
        Label label = new Label()
                .setName(name)
                .setLabelListVisibility("labelShow")
                .setMessageListVisibility("show");
        Label created = gmailLabels.create(userId, label).execute();
        labelsByName.put(name, created);
        return created;
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