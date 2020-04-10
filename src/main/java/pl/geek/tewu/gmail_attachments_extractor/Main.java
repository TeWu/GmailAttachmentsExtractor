package pl.geek.tewu.gmail_attachments_extractor;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;


@Command(
        name = AppInfo.COMMAND,
        version = AppInfo.VERSION,
        header = AppInfo.NAME + " v" + AppInfo.VERSION + "%n" + AppInfo.SHORT_DESCRIPTION + "%n",
        usageHelpWidth = 120
)
public class Main implements Callable<Integer> {
    public static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_MODIFY);


    @Mixin
    private Options options;


    public static void main(String[] args) throws Exception {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        options.process();

        // Init Gmail API
        Gmail gmail = GmailInit.getGmail(AppInfo.NAME, options.credentialsFilePath, SCOPES, options.tokensDirectoryPath);
        // Check authorization and exit if requested
        if (options.onlyCheckAuth) {
            gmail.users().labels().list("me");
            System.out.println("Gmail authorization: OK");
            System.exit(0);
        }
        // Extract attachments
        boolean success = new GmailAttachmentsExtractor(gmail, "me", options).extractAttachments();
        return success ? 0 : 1;
    }
}
