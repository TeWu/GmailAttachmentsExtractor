package pl.geek.tewu.gmail_attachments_extractor;

import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
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
    public static final String DEFAULT_FILENAME_REGEX_STR = ".*";
    public static final String DEFAULT_MIME_TYPE_REGEX_STR = "^.*";
    public static final Pattern SIZE_STR_PATTERN = Pattern.compile("^([0-9.]+)([kMG]?)B?$");


    @Parameters(
            index = "0", arity = "1",
            paramLabel = "QUERY_STRING", description = "Only try to extract attachments from emails that match this query. Supports the same query format as the Gmail search box. For example, \"label:big-emails\" or \"from:someuser@example.com has:attachment larger:5M after:2020/12/31 before:2021/01/25\"."
    )
    public String queryString;

    @Parameters(
            index = "1", arity = "0..1",
            defaultValue = "Gmail Extracted Attachments",
            paramLabel = "OUTPUT_DIRECTORY", description = "Save attachments to this directory. Must be a patch to a non-existing directory."
    )
    public Path outputDir;

    @Option(names = {"-l", "--labels-prefix"},
            defaultValue = "cleanup",
            paramLabel = "OUTPUT_LABEL_PREFIX", description = "Create labels which name start with this prefix, and mark affected emails with them."
    )
    public String outputLabelsPrefix;

    @Option(
            names = {"-C", "--credentials-file"},
            defaultValue = "credentials.json",
            paramLabel = "CREDENTIALS_FILE", description = "Path to file with Gmail API credentials (typically named credentials.json). How to generate this file: https://developers.google.com/gmail/api/quickstart/java#step_1_turn_on_the"
    )
    public Path credentialsFilePath;

    @Option(
            names = {"--tokens-dir"},
            defaultValue = "tokens",
            paramLabel = "TOKENS_DIR", description = "Path to directory, where Gmail API authorization data get stored"
    )
    public Path tokensDirectoryPath;


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
        outputDir = outputDir.toAbsolutePath();

        // Attachment Filter Options
        if (!filter.mimeTypeRegexStr.startsWith("^"))
            filter.mimeTypeRegexStr = "^" + filter.mimeTypeRegexStr;
        if (!filter.mimeTypeRegexStr.endsWith(".*"))
            filter.mimeTypeRegexStr = filter.mimeTypeRegexStr + ".*";
        filter.mimeTypeRegex = Pattern.compile(filter.mimeTypeRegexStr, Pattern.DOTALL);
        filter.filenameRegex = Pattern.compile(filter.filenameRegexStr, Pattern.DOTALL);
        filter.minSize = sizeStrToLong(filter.minSizeStr);
        filter.maxSize = sizeStrToLong(filter.maxSizeStr);
        if (filter.minSize != 0 && filter.maxSize != 0 && filter.minSize > filter.maxSize)
            throw new ParameterException(spec.commandLine(), "Invalid argument value: min-size can't be greater than max-size");
    }

    private long sizeStrToLong(String str) {
        Matcher m = SIZE_STR_PATTERN.matcher(str);
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