package pl.geek.tewu.clean_gmail_attachments;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;

import javax.mail.MessagingException;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;


public class Main {
    public static final String APP_NAME = "Clean Gmail Attachments";
    public static final String CREDENTIALS_FILE_PATH = "/credentials.json";  // Put 'credentials.json' file in 'resources' folder. How to generate this file: https://developers.google.com/gmail/api/quickstart/java#step_1_turn_on_the
    public static final List<String> SCOPES = Collections.singletonList(GmailScopes.MAIL_GOOGLE_COM);


    public static void main(String[] args) throws IOException, GeneralSecurityException, MessagingException, ParseException {
        try {
            // TODO: remove CleanerException - print to System.out and exit instead
            File rootOutput = new File("Exported Gmail Attachments");
            if (rootOutput.exists())
                throw new CleanerException("Output directory '" + rootOutput.getAbsolutePath() + "' already exists - move it or provide different output directory path");
            if (!rootOutput.mkdirs())
                throw new CleanerException("Can't create output directory '" + rootOutput.getAbsolutePath() + "'");

            Gmail gmail = GmailInit.getGmail(APP_NAME, CREDENTIALS_FILE_PATH, SCOPES);
            new Cleaner(gmail, "me", rootOutput.toPath()).clean("label:abc", "cleanup");
        } catch (CleanerException exc) {
            System.err.println("ERROR: " + exc.getMessage());
            System.exit(1);
        }
    }
}
