package pl.geek.tewu.clean_gmail_attachments;

import java.io.*;


public class IOUtils {

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