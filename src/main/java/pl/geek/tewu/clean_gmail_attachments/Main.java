package pl.geek.tewu.clean_gmail_attachments;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;

import javax.mail.MessagingException;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;


public class Main {
    public static final String APP_NAME = "Clean Gmail Attachments";
    public static final String CREDENTIALS_FILE_PATH = "/credentials.json";  // Put 'credentials.json' file in 'resources' folder. How to generate this file: https://developers.google.com/gmail/api/quickstart/java#step_1_turn_on_the
    public static final List<String> SCOPES = Collections.singletonList(GmailScopes.MAIL_GOOGLE_COM);


    public static void main(String[] args) throws IOException, GeneralSecurityException, MessagingException, ParseException {
        String outputLabelPrefix = "cleanup";
        String queryString = "label:abc";
        String outputDirectoryPath = "Exported Gmail Attachments";

        Gmail gmail = GmailInit.getGmail(APP_NAME, CREDENTIALS_FILE_PATH, SCOPES);
        new Cleaner(gmail, "me", Paths.get(outputDirectoryPath)).clean(queryString, outputLabelPrefix);
    }
}
