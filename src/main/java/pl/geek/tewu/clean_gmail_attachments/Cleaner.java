package pl.geek.tewu.clean_gmail_attachments;

import com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.ModifyMessageRequest;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;


public class Cleaner {
    private Gmail gmail;
    private Gmail.Users.Labels gmailLabels;
    private Gmail.Users.Messages gmailMessages;
    private String userId;
    private Map<String, Label> labelsByName;


    public Cleaner(Gmail gmail, String userId) {
        this.gmail = gmail;
        this.userId = userId;
        this.gmailLabels = gmail.users().labels();
        this.gmailMessages = gmail.users().messages();
    }

    public boolean clean(String queryString, String outLabelNamePrefix) throws IOException, MessagingException {
        buildLabelsByName();
        Label outLabelWithAttachments = getOrCreateLabel(outLabelNamePrefix + " [with attachments]");
        Label outLabelNoAttachments = getOrCreateLabel(outLabelNamePrefix + " [no attachments]");

        List<Message> msgs = gmailMessages.list(userId).setQ(queryString).execute().getMessages();
        if (msgs == null || msgs.isEmpty()) {
            System.out.println("No messages matched query '" + queryString + "' - Terminating.");
            return false;
        }
        System.out.println("Query matched " + msgs.size() + " messages");

        for (Message msgIds : Collections.singletonList(msgs.get(0))) {  // TODO: <-- process all messages, not only first one
            System.out.println(msgIds);
            Message rawMsg = getRawMessage(msgIds.getId());
            MimeMessage mimeMsg = rawMessageToMimeMessage(rawMsg);
//          System.out.println(new String(com.google.api.client.repackaged.org.apache.commons.codec.binary.Base64.decodeBase64(rawMsg.getRaw())));

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

        return true;
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