package pl.geek.tewu.gmail_attachments_extractor;

import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Command(
        mixinStandardHelpOptions = true,
        abbreviateSynopsis = true,
        sortOptions = false,
        showDefaultValues = true,
        parameterListHeading = "%nParameters:%n",
        optionListHeading = "%nOptions:%n",
        separator = " "
)
public class Options {
    public static final Pattern INVALID_CASE_OPERATORS_REGEX = Pattern.compile(".*?\\b(or|and|around)\\b.*", Pattern.DOTALL);
    public static final String DEFAULT_FILENAME_REGEX_STR = ".*";
    public static final String DEFAULT_MIME_TYPE_REGEX_STR = "^.*";
    public static final Pattern SIZE_STR_REGEX = Pattern.compile("^([0-9.]+)([kMG]?)B?$");
    public static final String GMAIL_SEARCH_OPERATORS_HELP_URL = "https://support.google.com/mail/answer/7190";
    public static final String GMAIL_API_CREDENTIALS_FILE_GENERATION_URL = "https://developers.google.com/gmail/api/quickstart/java#step_1_turn_on_the";


    @Parameters(
            index = "0", arity = "1",
            paramLabel = "QUERY_STRING", description = "Only try to extract attachments from emails that match this query. Supports the same query format as the Gmail search box. For example, \"label:big-emails\" or \"from:someuser@example.com has:attachment larger:5M after:2020/12/31 before:2021/01/25\". More info about Gmail search operators: " + GMAIL_SEARCH_OPERATORS_HELP_URL
    )
    public String queryString;

    @Parameters(
            index = "1", arity = "0..1",
            defaultValue = "Gmail Extracted Attachments",
            paramLabel = "OUTPUT_DIRECTORY", description = "Save attachments to this directory. Must be a path to a non-existing directory."
    )
    public Path outputDir;

    @Option(names = {"-l", "--labels-prefix"},
            defaultValue = "Cleanup",
            paramLabel = "OUTPUT_LABEL_PREFIX", description = "Create labels which name start with this prefix, and mark affected emails with them."
    )
    public String outputLabelsPrefix;

    @Option(
            names = {"-C", "--credentials-file"},
            defaultValue = "credentials.json",
            paramLabel = "CREDENTIALS_FILE", description = "Path to file with Gmail API credentials (typically named credentials.json). How to generate this file: " + GMAIL_API_CREDENTIALS_FILE_GENERATION_URL
    )
    public Path credentialsFilePath;

    @Option(
            names = {"--tokens-dir"},
            defaultValue = "tokens",
            paramLabel = "TOKENS_DIR", description = "Path to directory, where Gmail API authorization data get stored"
    )
    public Path tokensDirectoryPath;

    @Option(
            names = {"--no-modify-gmail"},
            description = "Only download attachments. Don't modify Gmail (don't create labels, don't insert copies of emails without extracted attachments, etc.)."
    )
    public boolean noModifyGmail;
    public boolean modifyGmail;

    @Option(
            names = {"--fail-late"},
            description = "If processing email message is unsuccessful (results in an error), ignore that error and proceed to the next email. All ignored errors are listed at the end of the program execution. Running the program with --fail-late switch is STRONGLY DISCOURAGED! Ignoring errors shouldn't cause any immediate problems, but it may confuse You about which actions program performed successfully, and which not, and in result You may get tricked to act in a way that can lead to data loss, email duplication and other unforeseen consequences. Please use --fail-late switch only for debugging purposes, and not to do actual work of extracting attachments."
    )
    public boolean failLate;

    @Option(
            names = {"--unsafe"},
            description = "Try some unsafe/not fully tested behaviours, to work around some errors. This switch may cause the program to execute without errors, but the results of it's execution are not guaranteed to be correct, and must be manually verified. Running the program with --unsafe switch is STRONGLY DISCOURAGED! If this switch is necessary, then it should be paired with query string that targets a single, problematic email via its Message-ID (e.g. \"rfc822msgid:<MESSAGE-ID@gmail.com>\")."
    )
    public boolean unsafe;

    @Option(
            names = {"--no-validate"}, negatable = true,
            defaultValue = "true",
            description = "Performs validations, to make sure that attachment extraction has been performed correctly. If the validations result in false negatives (which from unknown reasons can sometimes happen), then disable validations, and validate manually that the attachments has been downloaded correctly. It is recommended to, when necessary, run the program with validations disabled only for a single, problematic email, targeting it via its Message-ID (e.g. \"rfc822msgid:<MESSAGE-ID@gmail.com>\")."
    )
    public boolean validate;

    @Option(
            names = {"--inter-message-wait"},
            defaultValue = "0",
            description = "Waits that many milliseconds before processing each email massage. Slowing down the program might help to avoid exceeding gmail API quota."
    )
    public int interMessageWait;

    @Option(
            names = {"--only-check-auth"},
            help = true,  // Disable requested option validation
            description = "Only check if authorization information are correct, by trying to access the Gmail account, and exit immediately."
    )
    public boolean onlyCheckAuth;


    @ArgGroup(validate = false, heading = "%nAttachment Filter Options:%n")
    AttachmentFilter filter = new AttachmentFilter();

    static class AttachmentFilter {
        @Option(
                names = {"--filename"},
                defaultValue = DEFAULT_FILENAME_REGEX_STR,
                paramLabel = "FILENAME_REGEX", description = "Extract only attachments with filenames matching this regular expression."
        )
        String filenameRegexStr;
        Pattern filenameRegex;

        @Option(
                names = {"--mime-type"},
                defaultValue = DEFAULT_MIME_TYPE_REGEX_STR,
                paramLabel = "MIME_TYPE_PREFIX_REGEX", description = "Extract only attachments with mime types matching regular expression '^MIME_TYPE_PREFIX_REGEX.*'."
        )
        String mimeTypeRegexStr;
        Pattern mimeTypeRegex;

        @Option(
                names = {"--min-size"},
                defaultValue = "0",
                paramLabel = "MIN_SIZE", description = "Don't extract attachment that are smaller than MIN_SIZE. Specify value in bytes or use suffix k, M or G."
        )
        public String minSizeStr;
        public long minSize;

        @Option(
                names = {"--max-size"},
                defaultValue = "0",
                paramLabel = "MAX_SIZE", description = "Don't extract attachment that are larger than MAX_SIZE. Specify value in bytes or use suffix k, M or G."
        )
        String maxSizeStr;
        public long maxSize;
    }


    @Spec
    private CommandSpec spec;  // injected by PicoCLI


    public void process() {
        if (!credentialsFilePath.toFile().exists()) {
            System.err.println("File '" + credentialsFilePath + "' doesn't exist. You need to generate file with Gmail API OAuth2 credentials, to let this program access your Gmail account. How to generate this file: " + GMAIL_API_CREDENTIALS_FILE_GENERATION_URL + " . Then either name this file 'credentials.json' and put in current working directory, or provide a path to this file using --credentials-file option.");
            System.exit(1);
        }

        if (queryString != null) {
            Matcher invalidQueryStringMatcher = INVALID_CASE_OPERATORS_REGEX.matcher(queryString);
            if (invalidQueryStringMatcher.matches()) {
                System.err.println("Operators in QUERY_STRING, like '" + invalidQueryStringMatcher.group(1) + "', should be upper case. See more info about Gmail search operators: " + GMAIL_SEARCH_OPERATORS_HELP_URL);
                System.exit(1);
            }
        }

        outputDir = outputDir.toAbsolutePath();
        modifyGmail = !noModifyGmail;

        // Attachment Filter Options
        if (!Objects.equals(filter.mimeTypeRegexStr, DEFAULT_MIME_TYPE_REGEX_STR) && !filter.mimeTypeRegexStr.startsWith("^("))
            filter.mimeTypeRegexStr = "^(" + filter.mimeTypeRegexStr + ").*";
        filter.mimeTypeRegex = Pattern.compile(filter.mimeTypeRegexStr, Pattern.DOTALL);
        filter.filenameRegex = Pattern.compile(filter.filenameRegexStr, Pattern.DOTALL);
        filter.minSize = sizeStrToLong(filter.minSizeStr);
        filter.maxSize = sizeStrToLong(filter.maxSizeStr);
        if (filter.minSize != 0 && filter.maxSize != 0 && filter.minSize > filter.maxSize)
            throw new ParameterException(spec.commandLine(), "Invalid argument value: min-size can't be greater than max-size");
    }

    private long sizeStrToLong(String str) {
        Matcher m = SIZE_STR_REGEX.matcher(str);
        if (!m.matches())
            throw new ParameterException(spec.commandLine(), "Invalid argument value '" + str + "' (valid suffixes: k, M and G)");
        double value = Double.parseDouble(m.group(1));
        char suffix = m.group(2).isEmpty() ? '\0' : m.group(2).charAt(0);
        switch (suffix) {
            case 'k':  // Kilo
                value *= 1e3;
                break;
            case 'M':  // Mega
                value *= 1e6;
                break;
            case 'G':  // Giga
                value *= 1e9;
                break;
        }
        return (long) value;
    }
}