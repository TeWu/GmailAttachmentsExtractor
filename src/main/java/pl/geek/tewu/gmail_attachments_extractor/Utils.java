package pl.geek.tewu.gmail_attachments_extractor;

import java.io.*;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.HashMap;
import java.util.Map;


public class Utils {
    public static final int DIR_NAME_MAX_LEN = 100;
    public static final int FILE_NAME_NO_EXT_MAX_LEN = 100;
    public static final int FILE_EXT_MAX_LEN = 15;
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


    public static boolean isAllPrintableASCII(String str) {
        for (int i = 0; i < str.length(); i++)
            if (!isPrintableASCII((int) str.charAt(i)))
                return false;
        return true;
    }

    public static boolean isPrintableASCII(int c) {
        return c < 127 && (c >= 32 || c == '\r' || c == '\n' || c == '\t');
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

    public static String removeFileSeparatorChars(String str) {
        StringBuilder sb = new StringBuilder(str.length());
        char c;
        for (int i = 0; i < str.length(); i++) {
            c = str.charAt(i);
            if (c != '/' && c != '\\') sb.append(c);
            else sb.append('_');
        }
        return sb.toString();
    }

    public static String sanitizeFileName(String name) {
        final int dot = name.lastIndexOf('.');
        if (dot == -1) return sanitizeFSName(name, FILE_NAME_NO_EXT_MAX_LEN);
        return sanitizeFSName(name.substring(0, dot), FILE_NAME_NO_EXT_MAX_LEN) +
                "." +
                sanitizeFSName(name.substring(dot + 1), FILE_EXT_MAX_LEN);
    }

    public static String sanitizeDirName(String name) {
        return sanitizeFSName(name, DIR_NAME_MAX_LEN);
    }

    public static String sanitizeFSName(String name, int maxLen) {
        if (name.length() > maxLen)
            name = name.substring(0, maxLen);
        name = name.replaceAll("[^a-zA-ZĄąĆćĘęŁłŃńŚśÓóŻżŹź0-9 _]+", "_");
        stripEnd(name, ".", true);
        return name;
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