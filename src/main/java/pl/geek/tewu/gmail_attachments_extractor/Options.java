package pl.geek.tewu.gmail_attachments_extractor;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.nio.file.Paths;


@Command(
        mixinStandardHelpOptions = true,
        abbreviateSynopsis = true,
        sortOptions = false,
        showDefaultValues = true,
        parameterListHeading = "%nParameters:%n",
        optionListHeading = "%nOptions:%n"
)
public class Options {
    @Parameters(index = "0", arity = "1", paramLabel = "QUERY_STRING", description = "Only try to extract attachments from emails that match this query. Supports the same query format as the Gmail search box. For example, \"label:big-emails\" or \"from:someuser@example.com has:attachment larger:5M after:2020/12/31 before:2021/01/25\".")
    public String queryString;

    @Parameters(index = "1", arity = "0..1", paramLabel = "OUTPUT_DIRECTORY", description = "Save attachments to this directory. Must be a patch to a non-existing directory.")
    public Path outputDir = Paths.get("Gmail Extracted Attachments");

    @Option(names = {"-l", "--labels-prefix"}, arity = "0..1", description = "Create labels which name start with this prefix, and mark affected emails with them.")
    public String outputLabelsPrefix = "cleanup";
}
