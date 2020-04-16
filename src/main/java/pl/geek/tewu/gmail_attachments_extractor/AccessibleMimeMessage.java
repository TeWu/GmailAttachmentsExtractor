package pl.geek.tewu.gmail_attachments_extractor;

import javax.mail.Folder;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeMessage;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * AccessibleMimeMessage is MimeMessage with different algorithm for generating Message-ID header and
 * ability to pre-generate Message-ID header, so you can reference it in the content of the email message
 * (in MimeMessage class, Message-ID is generated when you're done modifying the message, and call saveChanges).
 */
public class AccessibleMimeMessage extends MimeMessage {
    /**
     * A global unique number, to ensure uniqueness of generated strings.
     **/
    private static final AtomicInteger ID = new AtomicInteger();
    private static final Random RANDOM = new Random();
    private static final char[] MESSAGE_ID_ALPHABET = new char[]{
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
            'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '_', '+'
    };
    private static final int MAX_MESSAGE_ID_LOCAL_PART_LENGTH = 100;


    private String nextMessageID = null;


    public AccessibleMimeMessage(Session session) {
        super(session);
    }

    public AccessibleMimeMessage(Session session, InputStream is) throws MessagingException {
        super(session, is);
    }

    public AccessibleMimeMessage(MimeMessage source) throws MessagingException {
        super(source);
    }


    protected AccessibleMimeMessage(Folder folder, int msgnum) {
        super(folder, msgnum);
    }

    protected AccessibleMimeMessage(Folder folder, InputStream is, int msgnum) throws MessagingException {
        super(folder, is, msgnum);
    }

    protected AccessibleMimeMessage(Folder folder, InternetHeaders headers, byte[] content, int msgnum) throws MessagingException {
        super(folder, headers, content, msgnum);
    }


    public String getNextMessageID() {
        return nextMessageID;
    }

    public String generateNextMessageID() throws MessagingException {
        nextMessageID = generateMessageID();
        return nextMessageID;
    }


    protected void updateMessageID() throws MessagingException {
        setHeader("Message-ID", nextMessageID != null ? nextMessageID : generateMessageID());
        nextMessageID = null;
    }

    protected String generateMessageID() throws MessagingException {
        String prevMessageId = getHeader("Message-ID", null);
        if (prevMessageId == null || prevMessageId.isEmpty() ||
                prevMessageId.lastIndexOf('@') == -1 || prevMessageId.charAt(0) != '<' || prevMessageId.charAt(prevMessageId.length() - 1) != '>')
            throw new IllegalStateException("Updating message with incorrect or absent Message-ID header");

        // prevMessageId is  <(prev-local)@(prev-domain)>
        // New Message-ID is <(prev-local).(hashcode).(currentTime).(id)@(prev-domain)>
        int at = prevMessageId.lastIndexOf('@');
        String prevLocal = prevMessageId.substring(1, at);
        String newLocal = prevLocal + "." +
                convertDecToBase64(RANDOM.nextInt() & Integer.MAX_VALUE) + "." +
                convertDecToBase64(System.currentTimeMillis()) + "." +
                convertDecToBase64(ID.getAndIncrement());

        if (newLocal.length() > MAX_MESSAGE_ID_LOCAL_PART_LENGTH) {
            char randomChar = MESSAGE_ID_ALPHABET[RANDOM.nextInt(MESSAGE_ID_ALPHABET.length)];  // To avoid a dot (.) as first char in new local part
            newLocal = randomChar + newLocal.substring(newLocal.length() - (MAX_MESSAGE_ID_LOCAL_PART_LENGTH - 1));
        }

        return "<" + newLocal + prevMessageId.substring(at);
    }


    private String convertDecToBase64(long num) {
        if (num == 0) return "0";
        final int base = MESSAGE_ID_ALPHABET.length;
        StringBuilder res = new StringBuilder();
        while (num > 0) {
            res.append(MESSAGE_ID_ALPHABET[(int) (num % base)]);
            num /= base;
        }
        return res.reverse().toString();
    }

}