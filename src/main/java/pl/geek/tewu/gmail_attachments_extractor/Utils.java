package pl.geek.tewu.gmail_attachments_extractor;

import java.io.*;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;


public class Utils {
    public static final int DIR_NAME_MAX_LEN = 100;
    public static final int FILE_NAME_NO_EXT_MAX_LEN = 100;
    public static final int FILE_EXT_MAX_LEN = 15;
    public static final Pattern NON_UNICODE_FS_NAME_PATTERN = Pattern.compile("[^A-Za-z0-9 _.]+");
    public static final Pattern UNICODE_FS_NAME_PATTERN = Pattern.compile("[^\\p{Alpha}0-9 _.]+", Pattern.UNICODE_CHARACTER_CLASS);
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

    public static String resolvingSanitizeFileName(Path base, String fileName) {
        Path sanitizedFilePath = sanitizingResolve(base, fileName, true);
        return sanitizedFilePath.getFileName().toString();
    }

    public static String resolvingSanitizeDirName(Path base, String dirName) {
        Path sanitizedDirPath = sanitizingResolve(base, dirName, false);
        return sanitizedDirPath.getFileName().toString();
    }

    private static Path sanitizingResolve(Path base, String fsObjectName, boolean isFile) {
        fsObjectName = removeFileSeparatorChars(fsObjectName);
        fsObjectName = stripEnd(fsObjectName, ".", true);
        try {
            return resolveAndCheck(base, fsObjectName);  // Try using potentially invalid name first - e.g. Windows don't allow question mark (and some other characters) in filename, but Linux do
        } catch (IOException | InvalidPathException e1) {
            // If the name is invalid, apply increasingly conservative sanitization, until it becomes valid
            try {
                return resolveAndCheck(base, sanitize(fsObjectName, isFile, false));
            } catch (IOException | InvalidPathException e2) {
                try {
                    return resolveAndCheck(base, sanitize(fsObjectName, isFile, true));
                } catch (IOException | InvalidPathException e3) {
                    throw new RuntimeException(e3);
                }
            }
        }
    }

    private static String sanitize(String name, boolean isFile, boolean extraSafe) {
        if (isFile) return sanitizeFileName(name, extraSafe);
        else return sanitizeDirName(name, extraSafe);
    }

    private static String sanitizeFileName(String name, boolean extraSafe) {
        final int dot = name.lastIndexOf('.');
        if (dot == -1) return sanitizeFSName(name, FILE_NAME_NO_EXT_MAX_LEN, extraSafe);
        return sanitizeFSName(name.substring(0, dot), FILE_NAME_NO_EXT_MAX_LEN, extraSafe) +
                "." +
                sanitizeFSName(name.substring(dot + 1), FILE_EXT_MAX_LEN, extraSafe);
    }

    private static String sanitizeDirName(String name, boolean extraSafe) {
        return sanitizeFSName(name, DIR_NAME_MAX_LEN, extraSafe);
    }

    private static String sanitizeFSName(String name, int maxLen, boolean extraSafe) {
        if (name.length() > maxLen)
            name = name.substring(0, maxLen);
        name = (extraSafe ? NON_UNICODE_FS_NAME_PATTERN : UNICODE_FS_NAME_PATTERN).matcher(name).replaceAll("_");
        name = stripEnd(name, ".", true);
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

    private static Path resolveAndCheck(Path base, String fsObjectName) throws IOException {
        Path path = base.resolve(fsObjectName);  // Resolve
        path.toFile().getCanonicalFile();  // Check, and throw exception if invalid
        return path;
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