package pl.geek.tewu.gmail_attachments_extractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


public class DigestUtils {
    public static final String UTF_8 = "UTF-8";
    public static final Charset UTF_8_CHARSET = Charset.forName(UTF_8);
    public static final int STREAM_BUFFER_LENGTH = 1024;
    public static final char[] DIGITS_LOWER = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    public static class Algorithms {
        public static final String MD2 = "MD2";
        public static final String MD5 = "MD5";
        public static final String SHA_1 = "SHA-1";
        public static final String SHA_224 = "SHA-224";
        public static final String SHA_256 = "SHA-256";
        public static final String SHA_384 = "SHA-384";
        public static final String SHA_512 = "SHA-512";
        public static final String SHA_512_224 = "SHA-512/224";
        public static final String SHA_512_256 = "SHA-512/256";
        public static final String SHA3_224 = "SHA3-224";
        public static final String SHA3_256 = "SHA3-256";
        public static final String SHA3_384 = "SHA3-384";
        public static final String SHA3_512 = "SHA3-512";

        public static String[] all() {
            return new String[]{
                    MD2, MD5, SHA_1, SHA_224, SHA_256, SHA_384, SHA_512, SHA_512_224, SHA_512_256, SHA3_224, SHA3_256, SHA3_384, SHA3_512
            };
        }
    }


    ///// Convenience methods for  MD5 /////

    public static String md5Hex(final String data) {
        return digestHex(data, Algorithms.MD5);
    }

    public static String md5Hex(final byte[] data) {
        return digestHex(data, Algorithms.MD5);
    }

    public static String md5Hex(final InputStream data) throws IOException {
        return digestHex(data, Algorithms.MD5);
    }

    public static byte[] md5(final String data) {
        return digestBytes(data, Algorithms.MD5);
    }

    public static byte[] md5(final byte[] data) {
        return digestBytes(data, Algorithms.MD5);
    }

    public static byte[] md5(final InputStream data) throws IOException {
        return digestBytes(data, Algorithms.MD5);
    }


    ///// Convenience methods for  SHA-1 /////

    public static String sha1Hex(final String data) {
        return digestHex(data, Algorithms.SHA_1);
    }

    public static String sha1Hex(final byte[] data) {
        return digestHex(data, Algorithms.SHA_1);
    }

    public static String sha1Hex(final InputStream data) throws IOException {
        return digestHex(data, Algorithms.SHA_1);
    }

    public static byte[] sha1(final String data) {
        return digestBytes(data, Algorithms.SHA_1);
    }

    public static byte[] sha1(final byte[] data) {
        return digestBytes(data, Algorithms.SHA_1);
    }

    public static byte[] sha1(final InputStream data) throws IOException {
        return digestBytes(data, Algorithms.SHA_1);
    }


    ///// Convenience methods for  SHA-256 /////

    public static String sha256Hex(final String data) {
        return digestHex(data, Algorithms.SHA_256);
    }

    public static String sha256Hex(final byte[] data) {
        return digestHex(data, Algorithms.SHA_256);
    }

    public static String sha256Hex(final InputStream data) throws IOException {
        return digestHex(data, Algorithms.SHA_256);
    }

    public static byte[] sha256(final String data) {
        return digestBytes(data, Algorithms.SHA_256);
    }

    public static byte[] sha256(final byte[] data) {
        return digestBytes(data, Algorithms.SHA_256);
    }

    public static byte[] sha256(final InputStream data) throws IOException {
        return digestBytes(data, Algorithms.SHA_256);
    }


    ///// Common /////

    public static String digestHex(final String data, final String algorithm) {
        return encodeHexString(digestBytes(data, algorithm));
    }

    public static String digestHex(final byte[] data, final String algorithm) {
        return encodeHexString(digestBytes(data, algorithm));
    }

    public static String digestHex(final InputStream data, final String algorithm) throws IOException {
        return encodeHexString(digestBytes(data, algorithm));
    }

    public static byte[] digestBytes(final String data, final String algorithm) {
        return digestBytes(getStringBytes(data, UTF_8_CHARSET), algorithm);
    }

    public static byte[] digestBytes(final byte[] data, final String algorithm) {
        return getDigest(algorithm).digest(data);
    }

    public static byte[] digestBytes(final InputStream data, final String algorithm) throws IOException {
        return digestBytes(getDigest(algorithm), data);
    }

    public static byte[] digestBytes(final MessageDigest messageDigest, final InputStream data) throws IOException {
        return updateDigest(messageDigest, data).digest();
    }

    public static MessageDigest getDigest(final String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static MessageDigest updateDigest(final MessageDigest digest, final InputStream data) throws IOException {
        final byte[] buffer = new byte[STREAM_BUFFER_LENGTH];
        int read = data.read(buffer, 0, STREAM_BUFFER_LENGTH);

        while (read > -1) {
            digest.update(buffer, 0, read);
            read = data.read(buffer, 0, STREAM_BUFFER_LENGTH);
        }

        return digest;
    }

    public static String encodeHexString(final byte[] data) {
        return new String(encodeHex(data, DIGITS_LOWER));
    }


    private static byte[] getStringBytes(final String string, final Charset charset) {
        if (string == null)
            return null;
        return string.getBytes(charset);
    }

    private static char[] encodeHex(final byte[] data, final char[] toDigits) {
        final int len = data.length;
        final char[] out = new char[len << 1];
        // two characters form the hex value.
        for (int i = 0, j = 0; i < len; i++) {
            out[j++] = toDigits[(0xF0 & data[i]) >>> 4];
            out[j++] = toDigits[0x0F & data[i]];
        }
        return out;
    }

}