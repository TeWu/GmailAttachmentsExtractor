package pl.geek.tewu.gmail_attachments_extractor;

import java.io.*;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.HashMap;
import java.util.Map;


public class Utils {
    public static final Map<Character, String> JAVA_ESCAPE_SEQ_MAPPING = new HashMap<>();

    static {
        JAVA_ESCAPE_SEQ_MAPPING.put('\t', "\\t");
        JAVA_ESCAPE_SEQ_MAPPING.put('\n', "\\n");
        JAVA_ESCAPE_SEQ_MAPPING.put('\r', "\\r");
        JAVA_ESCAPE_SEQ_MAPPING.put('\b', "\\b");
        JAVA_ESCAPE_SEQ_MAPPING.put('\f', "\\f");
        JAVA_ESCAPE_SEQ_MAPPING.put('\'', "\\\'");
        JAVA_ESCAPE_SEQ_MAPPING.put('\"', "\\\"");
        JAVA_ESCAPE_SEQ_MAPPING.put('\\', "\\\\");
    }


    public static String addJavaEscapeSequences(String input) {
        final StringWriter writer = new StringWriter(input.length() * 2);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            String replacement = JAVA_ESCAPE_SEQ_MAPPING.get(c);
            if (replacement != null)
                writer.write(replacement);
            else
                writer.write(c);
        }
        return writer.toString();
    }

    public static String sanitizeDirname(String dirname) {
        if (dirname.length() > 50)
            dirname = dirname.substring(0, 50);
        dirname = dirname.replaceAll("[^a-zA-ZĄąĆćĘęŁłŃńŚśÓóŻżŹź0-9 _]+", "_");
        stripEnd(dirname, ".", true);
        return dirname;
    }

    public static String stripEnd(final String str, final String stripChars, final boolean stripWhitespace) {
        int end = str == null ? 0 : str.length();
        if (end == 0)
            return str;

        if ((stripChars == null || stripChars.isEmpty()) && !stripWhitespace) {
            return str;
        } else if (stripChars == null || stripChars.isEmpty()) {
            while (end != 0 && Character.isWhitespace(str.charAt(end - 1)))
                end--;
        } else {
            while (end != 0 && (stripChars.indexOf(str.charAt(end - 1)) != -1 || (stripWhitespace && Character.isWhitespace(str.charAt(end - 1)))))
                end--;
        }
        return str.substring(0, end);
    }

    public static String humanReadableByteCount(long bytes) {
        if (-1e3 < bytes && bytes < 1e3)
            return bytes + " bytes";
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (bytes <= -1e6 || bytes >= 1e6) {
            bytes /= 1e3;
            ci.next();
        }
        return String.format("%.2f %cB", bytes / 1000.0, ci.current());
    }

    /***** IO Utils *****/

    public static void copyInputStreamToFile(final InputStream source, final File destination) throws IOException {
        try (OutputStream out = openOutputStream(destination, false)) {
            com.google.api.client.util.IOUtils.copy(source, out);
        }
    }

    public static FileOutputStream openOutputStream(final File file, final boolean append) throws IOException {
        if (file.exists()) {
            if (file.isDirectory()) {
                throw new IOException("File '" + file + "' exists but is a directory");
            }
            if (!file.canWrite()) {
                throw new IOException("File '" + file + "' cannot be written to");
            }
        } else {
            final File parent = file.getParentFile();
            if (parent != null) {
                if (!parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Directory '" + parent + "' could not be created");
                }
            }
        }
        return new FileOutputStream(file, append);
    }

}