package pl.geek.tewu.gmail_attachments_extractor;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.common.collect.HashMultiset;

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
import java.util.stream.Collectors;

import static pl.geek.tewu.gmail_attachments_extractor.Options.DEFAULT_FILENAME_REGEX_STR;
import static pl.geek.tewu.gmail_attachments_extractor.Options.DEFAULT_MIME_TYPE_REGEX_STR;


public class GmailAttachmentsExtractor {
    public static final String PRE_LABEL_SUFFIX = " [pre]";
    public static final String POST_LABEL_SUFFIX = " [post]";
    public static final String DELETED_FILE_PREFIX = "Deleted ";

    private Gmail.Users.Labels gmailLabels;
    private Gmail.Users.Messages gmailMessages;
    private String userId;
    private Map<String, Label> labelsByName;
    private Map<String, Label> labelsById;
    private Options options;
    private int globalUniqueNum;

    // Summary statistics
    private int msgProcessedCount;
    private int msgExtractedCount;
    private int extractedAttCount;
    private long totalExtractedAttSize;
    private HashMultiset<String> extractedAttMimeTypes;
    private HashMultiset<String> filteredAttMimeTypes;


    public GmailAttachmentsExtractor(Gmail gmail, String userId, Options options) {
        this.userId = userId;
        this.gmailLabels = gmail.users().labels();
        this.gmailMessages = gmail.users().messages();
        this.options = options;
        this.globalUniqueNum = 0;
    }

    public boolean extractAttachments() throws IOException, MessagingException, ParseException {
        resetStats();
        printStartMessage();

        // Check if main output directory already exists
        if (options.outputDir.toFile().exists()) {
            System.err.println("Output directory '" + options.outputDir + "' already exists - move it or provide different output directory path - Terminating.");
            return false;
        }

        // Get email messages matching queryString
        List<Message> msgs = gmailMessages.list(userId).setQ(options.queryString).execute().getMessages();
        if (msgs == null || msgs.isEmpty()) {
            System.out.println("No messages matched query '" + options.queryString + "' - Terminating.");
            return false;
        }

        // Build label dictionaries
        buildLabelDictionaries();

        // Create output labels
        Label preLabel = null;
        Label postLabel = null;
        if (options.modifyGmail) {
            String preLabelName = options.outputLabelsPrefix + PRE_LABEL_SUFFIX;
            String postLabelName = options.outputLabelsPrefix + POST_LABEL_SUFFIX;
            if (labelsByName.containsKey(preLabelName) || labelsByName.containsKey(postLabelName)) {
                System.err.println("Labels '" + preLabelName + "' and/or '" + postLabelName + "' already exist. Running this program when this labels already exist might lead to confusing results. Please provide different output labels prefix and try again. Note that removing those labels is probably not a good solution, as it may prevent you from distinguishing between emails with attachments and its copies without attachments - Terminating.");
                return false;
            }
            System.out.printf("Creating output labels '%s' and '%s'\n", preLabelName, postLabelName);
            preLabel = createLabel(preLabelName);
            postLabel = createLabel(postLabelName);
        }

        System.out.println("Query '" + options.queryString + "' matched " + msgs.size() + " email messages");

        // Process email messages
        for (Message msgIds : msgs) {
            msgProcessedCount++;
            List<Long> attachmentSizes = new LinkedList<>();
            Message msg = gmailMessages.get(userId, msgIds.getId()).execute();

            Optional<String> maybeSubject = msg.getPayload().getHeaders().stream().filter(h -> Objects.equals(h.getName(), "Subject")).map(h -> h.getValue()).findFirst();
            int percentProgress = 100 * msgProcessedCount / msgs.size();
            System.out.println(msgProcessedCount + "/" + msgs.size() + " (" + percentProgress + "%) | Processing email with " + (maybeSubject.isPresent() ? "subject '" + maybeSubject.get() + "'" : "id: " + msg.getId()));

            int attachmentToExtractCount = 0;
            List<String> mimeTypes = new LinkedList<>();
            if (msg.getPayload().getParts() != null) {  // If msg's MIME type is multipart
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
            }
            if (attachmentToExtractCount == 0) {
                System.out.println("    Email doesn't contain attachments that satisfy the filter - proceeding to the next email");
                filteredAttMimeTypes.addAll(mimeTypes);
                continue;
            }

            Message rawMsg = getRawMessage(msgIds.getId());
            AccessibleMimeMessage mimeMsg = rawMessageToMimeMessage(rawMsg);
            String messageId = mimeMsg.generateNextMessageID();
            Instant receiveDate = new MailDateFormat().parse(mimeMsg.getHeader("Date", null)).toInstant();
            Path attachmentsDir = createDirForAttachments(receiveDate, mimeMsg.getSubject());

            System.out.println("    Extracting " + attachmentToExtractCount + " attachment(s) to directory '" + attachmentsDir.getFileName() + "'");

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
                    System.out.println("    Attachment saved: " + fileName);
                    if (options.modifyGmail) {
                        String descriptor = buildDescriptorString(part, messageId, mimeMsg.getSubject(), receiveDate, fileSize);  // buildDescriptorString must be called BEFORE modifying the part
                        part.setFileName(DELETED_FILE_PREFIX + fileName + ".yml");
                        part.setContent(descriptor, "text/plain; charset=\"" + (Utils.isAllPrintableASCII(descriptor) ? "US-ASCII" : "UTF-8") + "\"");
                    }
                    extractedAttCount++;
                    totalExtractedAttSize += fileSize;
                    extractedAttMimeTypes.add(mimeType);
                } else {
                    // If part should not be extracted, delete it from local filesystem
                    Files.delete(filePath);
                    System.out.println("    Attachment NOT saved: " + fileName);
                    filteredAttMimeTypes.add(mimeType);
                }
            }
            if (!attachmentSizes.isEmpty()) throw new RuntimeException("One of attachments hasn't been exported properly");
            setParts(mimeMsg, parts);

            if (options.modifyGmail) {
                assert preLabel != null && postLabel != null;
                // Build message based on mimeMsg and rawMsg and insert it to Gmail
                System.out.println("    Inserting copy of email without extracted attachments to Gmail");
                List<String> labelIds = rawMsg.getLabelIds().stream()
                        .filter(id -> {
                            String name = labelsById.get(id).getName();
                            return !name.endsWith(PRE_LABEL_SUFFIX) && !name.endsWith(POST_LABEL_SUFFIX);
                        })
                        .collect(Collectors.toList());
                labelIds.add(postLabel.getId());
                insertMessage(mimeMsg, labelIds, rawMsg.getThreadId());

                // Add label to the original message
                addLabelToMessage(rawMsg, preLabel);
            }

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
        return options.filter.filenameRegex.matcher(filename).matches() &&
                options.filter.mimeTypeRegex.matcher(mimeType).matches() &&
                (options.filter.minSize == 0 || size >= options.filter.minSize) &&
                (options.filter.maxSize == 0 || size <= options.filter.maxSize);
    }

    private Path createDirForAttachments(Instant receiveDate, String messageSubject) {
        final String receiveDateStr = DateTimeFormatter.ofPattern("yyyy.MM.dd HH_mm_ss").withZone(ZoneId.systemDefault())
                .format(receiveDate);
        final String dirName = receiveDateStr + " " + Utils.sanitizeDirname(messageSubject);
        Path attDir = options.outputDir.resolve(dirName);
        int i = 2;

        // Find unique name for attachments directory
        while (attDir.toFile().exists() && i < 10)
            attDir = attDir.resolveSibling(dirName + " " + i++);
        if (attDir.toFile().exists()) attDir = attDir.resolveSibling(receiveDateStr);
        if (attDir.toFile().exists()) attDir = attDir.resolveSibling(receiveDateStr + " " + globalUniqueNum++);
        if (attDir.toFile().exists()) throw new RuntimeException("Can't find unique name for attachments directory");

        // Create attachments directory
        if (!attDir.toFile().mkdirs()) throw new RuntimeException("Can't create attachments directory '" + attDir + "'");
        return attDir;
    }

    private String buildDescriptorString(BodyPart part, String id, String subject, Instant receiveDate, long fileSize) throws IOException, MessagingException {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss O").withZone(ZoneId.systemDefault());
        return "#\r\n" +
                "# The attachment has been deleted from this email message.\r\n" +
                "#\r\n" +
                "Date Deleted: " + formatter.format(ZonedDateTime.now()) + "\r\n" +
                "Email:\r\n" +
                "    ID: \"" + Utils.addJavaEscapeSequences(id) + "\"\r\n" +
                "    Subject: \"" + Utils.addJavaEscapeSequences(subject) + "\"\r\n" +
                "    Date Received: " + formatter.format(receiveDate) + "\r\n" +
                "Attachment file:\r\n" +
                "    Name: \"" + Utils.addJavaEscapeSequences(part.getFileName()) + "\"\r\n" +
                "    Size in bytes: " + fileSize + "\r\n" +
                "    SHA1: " + DigestUtils.sha1Hex(part.getInputStream()) + "\r\n" +
                "    MD5:  " + DigestUtils.md5Hex(part.getInputStream()) + "\r\n";
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

    private Message insertMessage(MimeMessage mimeMessage, List<String> labelIds, String threadId) throws IOException, MessagingException {
        // Create Message instance containing email message metadata
        Message metadata = new Message()
                .setLabelIds(labelIds)
                .setThreadId(threadId);

        // Create byte array containing email message data (in RFC 2822 format)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mimeMessage.writeTo(baos);
        ByteArrayContent rawMessageBytes = new ByteArrayContent("message/rfc822", baos.toByteArray());

        return gmailMessages.insert(userId, metadata, rawMessageBytes)
                .setInternalDateSource("dateHeader")  // The GMail internal message time is based on the Date header in the email, when valid.
                .execute();
    }


    private Message getRawMessage(String messageId) throws IOException {
        return gmailMessages.get(userId, messageId)
                .setFormat("raw")
                .execute();
    }

    private AccessibleMimeMessage rawMessageToMimeMessage(Message message) throws MessagingException {
        Session session = Session.getDefaultInstance(new Properties(), null);
        return new AccessibleMimeMessage(session, new ByteArrayInputStream(Base64.decodeBase64(message.getRaw())));
    }

    private void printStartMessage() {
        System.out.println("Starting " + AppInfo.NAME);
        System.out.println(
                "Parameters:\n" +
                        "    Query string: " + options.queryString + "\n" +
                        "    Output directory: " + options.outputDir + "\n" +
                        "    Output labels prefix: " + options.outputLabelsPrefix
        );

        StringBuilder sb = new StringBuilder("    Attachment filter:\n");
        int initLen = sb.length();
        if (!Objects.equals(options.filter.filenameRegex.pattern(), DEFAULT_FILENAME_REGEX_STR)) sb.append("        Filename regex: ").append(options.filter.filenameRegex.pattern()).append("\n");
        if (!Objects.equals(options.filter.mimeTypeRegex.pattern(), DEFAULT_MIME_TYPE_REGEX_STR)) sb.append("        MIME type regex: ").append(options.filter.mimeTypeRegex.pattern()).append("\n");
        List<String> sizeStrs = new LinkedList<>();
        if (options.filter.minSize > 0) sizeStrs.add("min " + String.format("%,d", options.filter.minSize) + " bytes");
        if (options.filter.maxSize > 0) sizeStrs.add("max " + String.format("%,d", options.filter.maxSize) + " bytes");
        if (!sizeStrs.isEmpty()) sb.append("        File size: ").append(String.join(", ", sizeStrs));
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
        if (options.noModifyGmail)
            System.out.println("GMAIL DATA NOT MODIFIED");
        System.out.println();
    }
}