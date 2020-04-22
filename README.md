Gmail Attachments Extractor 📤
=======

**[Gmail][gmail-home]** is a great, free email service, with many useful features that extend the original vision of The Electronic Mail from 1960s. The one annoyingly missing feature is the ability to delete the attachment from an email, without deleting the email itself. This feature could be particularly useful in a situation where you run out of space in your inbox, but you have emails whose content is important, but attachments have already been downloaded to disk, and could be deleted to free some space.

**Gmail Attachments Extractor** is a simple tool, that allows you to extract the attachments from emails in your Gmail account.


How does it work?
=======
**Gmail Attachments Extractor** processes a set of email messages. For each email that it process it does 3 things:

1. It downloads all attachments from the email being processed.
2. It *inserts* an email message to the inbox, that is a copy of the email being processed, but without attachments that it has just downloaded.
3. It adds the `Cleanup [pre]` label to the email being processed, and the `Cleanup [post]` label to the copy that it has just created.

Gmail Attachments Extractor does **not modify or delete** (or move to Trash) any emails, so that result of its execution is fully (and easily) revertable. You run it, and then inspect the downloaded files and the emails with `Cleanup [pre]` and `Cleanup [post]` labels to verify that the result is as expected.
If you are not happy with the result, and want to revert to the state from before running the extractor, it's  as simple as deleting all the emails with `Cleanup [post]` label, and then deleting `Cleanup [pre]` and `Cleanup [post]` labels.
If you are happy with the result, then you can free up some space in your inbox by deleting the original emails (with attachments) and only leave their copies (without attachments). To do this, delete emails with `Cleanup [pre]` label.

**WARNING:** When deleting all emails with a particular label, make sure you're doing that with **"Conversation view" turned off!** When "Conversation view" is turned on, then clicking on a label will show all CONVERSATIONS which contain emails with that particular label. Those conversations may contain emails without the label you've clicked on, therefore deleting those conversations may lead to data loss.
To [turn off "Conversation view"][gmail-conversation-setting], click on a gear wheel ("Settings") button in a top-right corner of the gmail page, and in the "General" tab click on "Conversation view off", and then "Save Changes" button.


How to use it
=======

Step 0
-------
Download the newest release of **Gmail Attachments Extractor** (`GmailAttachmentsExtractor_vX.X.X.zip` file) from the [releases][releases] page. Unpack the archive, so that you have `GmailAttachmentsExtractor.jar` file.

Step 1
-------
Now you need to generate `credentials.json` file, with Gmail API OAuth2 credentials. You can generate the file however you like, but the easiest way is to go to the [Gmail API Quickstart page][gmail-api-quickstart], click the blue "Enable the Gmail API" button, and follow the wizard (select "Desktop app", then click the "DOWNLOAD CLIENT CONFIGURATION" button).
When you have your `credentials.json` file, put it in the same directory as `GmailAttachmentsExtractor.jar` file, open terminal, change to the directory with `GmailAttachmentsExtractor.jar` file, and run:

```
java -jar GmailAttachmentsExtractor.jar --only-check-auth
```

A browser window should pop up where you need to log in to your Gmail account, and allow the app to access it. After you've done that, you should see the following message in the console:

```
Gmail authorization: OK
```

Step 2
-------
You are now ready to run the **Gmail Attachments Extractor**. You run the app like:

```
java -jar GmailAttachmentsExtractor.jar [OPTIONS] QUERY_STRING [OUTPUT_DIRECTORY]
```

There are two parameters that you can pass to the program:

1. `QUERY_STRING` - is the query string, that selects the email messages from which attachments will be extracted. It  supports the same query format as the Gmail search box. So, for example, to extract attachments from email messages with label `big-attachments` use query string `label:big-attachments`, or to extract attachments from email messages that are larger than 30MB and received before 2014/01/25 use query string `larger:30M before:2014/01/25`. You can find more info about Gmail search operators [here][gmail-search].

2. `OUTPUT_DIRECTORY` - is the path to directory, where attachments will be saved. Must be a path to a non-existing directory. Defaults to `Gmail Extracted Attachments` when not specified.

Step 3
-------

After you are done using Gmail Attachments Extractor, you should take few actions to make sure your Gmail account will remain secure.
You should go to [this page][api-console-gmail-creds], and delete the OAuth2 credentials that you created to use with Gmail Attachments Extractor.
If you don't access Gmail API from any other app, then, you should go to [this page][api-console-gmail], and click "DISABLE API" button at the top of the page.

Finally if you've used the [Gmail API Quickstart page][gmail-api-quickstart] to create OAuth2 credentials, then you've also created "Quickstart" project, that you no longer need. To delete the "Quickstart" project, go to [this page][api-console-proj-settings], make sure that "Project name" is "Quickstart" (if not, select "Quickstart" project from the top-left menu) and click on "SHUT DOWN" button at the top of the page.


Customize
=======
You can customize some aspects of the program execution by using options. For example you can:
* Specify `--min-size 1M` option to only extract attachments larger than 1MB
* Specify `--mime-type 'image|video|audio'` option to only extract multimedia files
* Specify `--filename '.*\.pdf$'` option to only extract attachments with extension `.pdf`

You can see all the available options by running the program with `--help` option:

```
$ java -jar GmailAttachmentsExtractor.jar --help
Gmail Attachments Extractor vX.X.X
Downloads attachments from Gmail emails, then creates copy of emails but without extracted attachments.
https://github.com/TeWu/GmailAttachmentsExtractor

Usage: java -jar GmailAttachmentsExtractor.jar [OPTIONS] QUERY_STRING [OUTPUT_DIRECTORY]

Parameters:
      QUERY_STRING          Only try to extract attachments from emails that match this query. Supports the same query
                              format as the Gmail search box. For example, "label:big-emails" or "from:someuser@example.
                              com has:attachment larger:5M after:2020/12/31 before:2021/01/25". More info about Gmail
                              search operators: https://support.google.com/mail/answer/7190
      [OUTPUT_DIRECTORY]    Save attachments to this directory. Must be a path to a non-existing directory.
                              Default: Gmail Extracted Attachments

Options:
  -l, --labels-prefix OUTPUT_LABEL_PREFIX
                            Create labels which name start with this prefix, and mark affected emails with them.
                              Default: Cleanup
  -C, --credentials-file CREDENTIALS_FILE
                            Path to file with Gmail API credentials (typically named credentials.json). How to generate
                              this file: https://developers.google.com/gmail/api/quickstart/java#step_1_turn_on_the
                              Default: credentials.json
      --tokens-dir TOKENS_DIR
                            Path to directory, where Gmail API authorization data get stored
                              Default: tokens
      --no-modify-gmail     Only download attachments. Don't modify Gmail (don't create labels, don't insert copies of
                              emails without extracted attachments, etc.).
      --only-check-auth     Only check if authorization information are correct, by trying to access the Gmail account,
                              and exit immediately.
  -h, --help                Show this help message and exit.
  -V, --version             Print version information and exit.

Attachment Filter Options:
      --filename FILENAME_REGEX
                            Extract only attachments with filenames matching this regular expression.
                              Default: .*
      --mime-type MIME_TYPE_PREFIX_REGEX
                            Extract only attachments with mime types matching regular expression
                              '^MIME_TYPE_PREFIX_REGEX.*'.
                              Default: ^.*
      --min-size MIN_SIZE   Don't extract attachment that are smaller than MIN_SIZE. Specify value in bytes or use
                              suffix k, M or G.
                              Default: 0
      --max-size MAX_SIZE   Don't extract attachment that are larger than MAX_SIZE. Specify value in bytes or use
                              suffix k, M or G.
                              Default: 0
```


[gmail-home]: https://www.google.com/gmail/
[gmail-conversation-setting]: https://support.google.com/mail/answer/5900
[releases]: https://github.com/TeWu/GmailAttachmentsExtractor/releases
[gmail-api-quickstart]: https://developers.google.com/gmail/api/quickstart/java#step_1_turn_on_the
[gmail-search]: https://support.google.com/mail/answer/7190
[api-console-gmail-creds]: https://console.developers.google.com/apis/api/gmail.googleapis.com/credentials
[api-console-gmail]: https://console.developers.google.com/apis/api/gmail.googleapis.com/overview
[api-console-proj-settings]: https://console.developers.google.com/iam-admin/settings