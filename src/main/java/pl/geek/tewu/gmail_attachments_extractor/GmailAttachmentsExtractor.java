package pl.geek.tewu.gmail_attachments_extractor;

import com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.common.collect.HashMultiset;
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
import java.util.stream.Collectors;


public class GmailAttachmentsExtractor {
    public static final String WITH_ATTACHMENTS_SUFFIX = " [with attachments]";
    public static final String NO_ATTACHMENTS_SUFFIX = " [no attachments]";
    public static final String DELETED_FILE_PREFIX = "Deleted ";

    private Gmail gmail;
    private Gmail.Users.Labels gmailLabels;
    private Gmail.Users.Messages gmailMessages;
    private String userId;
    private Map<String, Label> labelsByName;
    private Map<String, Label> labelsById;
    private Path outputDir;
    private int globalUniqueNum;

    // Summary statistics
    private int msgProcessedCount;
    private int msgExtractedCount;
    private int extractedAttCount;
    private long totalExtractedAttSize;
    private HashMultiset<String> extractedAttMimeTypes;
    private HashMultiset<String> filteredAttMimeTypes;

    public static final String DEFAULT_FILENAME_PATTERN = ".*";
    public static final String DEFAULT_MIME_TYPE_PATTERN = "^.*";
    Pattern filenamePattern = Pattern.compile(DEFAULT_FILENAME_PATTERN, Pattern.DOTALL); // TODO
    String mimetypeSubPattern = ""; //TODO
    Pattern mimetypePattern = Pattern.compile(mimetypeSubPattern.isEmpty() ? DEFAULT_MIME_TYPE_PATTERN : "^" + mimetypeSubPattern + ".*", Pattern.DOTALL); // TODO
    int sizeMin = 0; // TODO
    int sizeMax = 0; // TODO


    public GmailAttachmentsExtractor(Gmail gmail, String userId, Path outputDir) {
        this.gmail = gmail;
        this.userId = userId;
        this.gmailLabels = gmail.users().labels();
        this.gmailMessages = gmail.users().messages();
        this.outputDir = outputDir.toAbsolutePath();
        this.globalUniqueNum = 0;
    }

    public boolean extractAttachments(String queryString, String outLabelsNamePrefix) throws IOException, MessagingException, ParseException {
        resetStats();
        printStartMessage(queryString, outLabelsNamePrefix);

        // Check if main output directory already exists
        if (outputDir.toFile().exists()) {
            System.err.println("Output directory '" + outputDir + "' already exists - move it or provide different output directory path - Terminating.");
            return false;
        }

        // Build label dictionaries
        buildLabelDictionaries();

        // Create output labels
        String withAttLabelName = outLabelsNamePrefix + WITH_ATTACHMENTS_SUFFIX;
        String noAttLabelName = outLabelsNamePrefix + NO_ATTACHMENTS_SUFFIX;
        if (labelsByName.containsKey(withAttLabelName) || labelsByName.containsKey(noAttLabelName)) {
            System.err.println("Labels '" + withAttLabelName + "' and/or '" + noAttLabelName + "' already exist. Running this program when this labels already exist might lead to confusing results. Please provide different output labels prefix and try again. Note that removing those labels is probably not a good solution, as it may prevent you from distinguishing between emails with attachments and its copies without attachments - Terminating.");
            return false;
        }
        System.out.printf("Creating output labels '%s' and '%s'\n", withAttLabelName, noAttLabelName);
        Label withAttLabel = createLabel(withAttLabelName);
        Label noAttLabel = createLabel(noAttLabelName);

        // Get email messages matching queryString
        List<Message> msgs = gmailMessages.list(userId).setQ(queryString).execute().getMessages();
        if (msgs == null || msgs.isEmpty()) {
            System.out.println("No messages matched query '" + queryString + "' - Terminating.");
            return false;
        }
        System.out.println("Query '" + queryString + "' matched " + msgs.size() + " email messages");

        // Create main output directory
        if (!outputDir.toFile().mkdirs()) {
            System.err.println("Can't create output directory '" + outputDir + "' - Terminating.");
            return false;
        }

        // Process email messages
        for (Message msgIds : msgs) {
            msgProcessedCount++;
            List<Long> attachmentSizes = new LinkedList<>();
            Message msg = gmailMessages.get(userId, msgIds.getId()).execute();

            Optional<String> maybeSubject = msg.getPayload().getHeaders().stream().filter(h -> Objects.equals(h.getName(), "Subject")).map(h -> h.getValue()).findFirst();
            System.out.println("Processing email with " + (maybeSubject.isPresent() ? "subject '" + maybeSubject.get() + "'" : "id: " + msg.getId()));

            int attachmentToExtractCount = 0;
            List<String> mimeTypes = new LinkedList<>();
            for (MessagePart part : msg.getPayload().getParts()) {
                if (part.getFilename() != null && !part.getFilename().isEmpty())  // If part doesn't have a filename, then it's not an attachment
                    mimeTypes.add(part.getMimeType());
                if (part.getBody() == null)
                    continue;
                long size = part.getBody().getSize().longValue();
                if (isBodyPartSatisfiesFilter(part.getFilename(), part.getMimeType(), size)) {
                    attachmentToExtractCount++;
                    attachmentSizes.add(size);
                }
            }
            if (attachmentToExtractCount == 0) {
                System.out.println("    Email doesn't contain attachments that satisfy filter - proceeding to the next email");
                filteredAttMimeTypes.addAll(mimeTypes);
                continue;
            }
            System.out.println("    Extracting " + attachmentToExtractCount + " attachment(s)");

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
                String contentType = part.getContentType();
                String mimeType = contentType.indexOf(";") > 0 ?
                        contentType.substring(0, contentType.indexOf(";")) :
                        contentType;
                // Save part to file
                saveToFile(part, filePath);
                // Calculate part/file size
                long fileSize = Files.size(filePath);

                // Check if part should be extracted
                if (isBodyPartSatisfiesFilter(fileName, mimeType, fileSize)) {
                    // If part should be extracted, override its content with descriptor string (effectively deleting it from email message)
                    if (!attachmentSizes.remove(fileSize)) throw new RuntimeException("Incorrect exported file size");
                    System.out.println("    Attachment saved: " + outputDir.relativize(filePath));
                    String descriptor = buildDescriptorString(part, receiveDate, fileSize);  // buildDescriptorString must be called BEFORE modifying the part
                    part.setFileName(DELETED_FILE_PREFIX + fileName + ".txt");
                    part.setText(descriptor);
                    extractedAttCount++;
                    totalExtractedAttSize += fileSize;
                    extractedAttMimeTypes.add(mimeType);
                } else {
                    // If part should not be extracted, delete it from local filesystem
                    Files.delete(filePath);
                    filteredAttMimeTypes.add(mimeType);
                }
            }
            if (!attachmentSizes.isEmpty()) throw new RuntimeException("One of attachments hasn't been exported properly");
            setParts(mimeMsg, parts);

            // Build message based on mimeMsg and rawMsg and insert it to Gmail
            System.out.println("    Inserting copy of email without attachments to Gmail");
            Message newMsg = mimeMessageToMessage(mimeMsg);
            List<String> labelIds = rawMsg.getLabelIds().stream()
                    .filter(id -> {
                        String name = labelsById.get(id).getName();
                        return !name.endsWith(WITH_ATTACHMENTS_SUFFIX) && !name.endsWith(NO_ATTACHMENTS_SUFFIX);
                    })
                    .collect(Collectors.toList());
            labelIds.add(noAttLabel.getId());
            newMsg.setLabelIds(labelIds);
            newMsg.setThreadId(rawMsg.getThreadId());
            insertMessage(newMsg);

            // Add label to the original message
            addLabelToMessage(rawMsg, withAttLabel);

            msgExtractedCount++;
        }

        printSummary();
        return true;
    }


    private void resetStats() {
        msgProcessedCount = 0;
        msgExtractedCount = 0;
        extractedAttCount = 0;
        totalExtractedAttSize = 0;
        extractedAttMimeTypes = HashMultiset.create();
        filteredAttMimeTypes = HashMultiset.create();
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
        Path outDir = outputDir.resolve(dirName);
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


    private void buildLabelDictionaries() throws IOException {
        labelsByName = new HashMap<>();
        labelsById = new HashMap<>();
        List<Label> labels = gmailLabels.list(userId).execute().getLabels();
        for (Label label : labels) {
            labelsByName.put(label.getName(), label);
            labelsById.put(label.getId(), label);
        }
    }

    private Label createLabel(String name) throws IOException {
        Label label = new Label()
                .setName(name)
                .setLabelListVisibility("labelShow")
                .setMessageListVisibility("show");
        Label created = gmailLabels.create(userId, label).execute();
        labelsByName.put(created.getName(), created);
        labelsById.put(created.getId(), created);
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

    private void printStartMessage(String queryString, String outLabelsNamePrefix) {
        System.out.println("Starting Gmail Attachment Extractor");
        System.out.println(
                "Parameters:\n" +
                        "    Query string: " + queryString + "\n" +
                        "    Output labels prefix: " + outLabelsNamePrefix + "\n" +
                        "    Output directory: " + outputDir
        );

        StringBuilder sb = new StringBuilder("    Attachment filter:\n");
        int initLen = sb.length();
        if (!Objects.equals(filenamePattern.pattern(), DEFAULT_FILENAME_PATTERN)) sb.append("        Filename pattern: " + filenamePattern.pattern());
        if (!Objects.equals(mimetypePattern.pattern(), DEFAULT_MIME_TYPE_PATTERN)) sb.append("        MIME type pattern: " + mimetypePattern.pattern());
        List<String> sizeStrs = new LinkedList<>();
        if (sizeMin > 0) sizeStrs.add("min " + sizeMin + " bytes");
        if (sizeMax > 0) sizeStrs.add("max " + sizeMax + " bytes");
        if (!sizeStrs.isEmpty()) sb.append("        File size: " + String.join(", ", sizeStrs));
        if (sb.length() > initLen)
            System.out.println(sb.toString());
    }

    private void printSummary() {
        System.out.println(
                "\n=== SUMMARY ===\n" +
                        "Processed " + msgProcessedCount + " email(s)\n" +
                        "Extracted attachments from " + msgExtractedCount + " email(s)\n" +
                        "Extracted " + extractedAttCount + " attachment(s)\n" +
                        "Total extracted attachments size: " + Utils.humanReadableByteCount(totalExtractedAttSize) + "\n" +
                        "Extracted attachments types: " + extractedAttMimeTypes
        );
        if (!filteredAttMimeTypes.isEmpty())
            System.out.println("NOT extracted (filtered) attachments types: " + filteredAttMimeTypes);
        System.out.println();
    }
}