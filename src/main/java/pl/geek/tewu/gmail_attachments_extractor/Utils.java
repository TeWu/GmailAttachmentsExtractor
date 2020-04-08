package pl.geek.tewu.gmail_attachments_extractor;

import java.io.*;


public class Utils {

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