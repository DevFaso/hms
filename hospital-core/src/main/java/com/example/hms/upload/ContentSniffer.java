package com.example.hms.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Recognises a file from its own bytes. The extension and the declared
 * Content-Type are the client's claim and are never the answer; markup a
 * browser would run (HTML, SVG, script) is no type at all.
 */
public final class ContentSniffer {

    /** The bytes of the head a signature is read from. */
    public static final int HEAD_BYTES = 4096;

    private static final int MAX_ARCHIVE_ENTRIES = 5000;
    private static final long MAX_ARCHIVE_INFLATED_BYTES = 200L * 1024 * 1024;

    private static final byte[] PDF_MAGIC = ascii("%PDF-");
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] OLE_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};
    private static final byte[] EBML_MAGIC = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final byte[] VBA_STORAGE = "_VBA_PROJECT".getBytes(StandardCharsets.UTF_16LE);
    private static final String[] MARKUP = {"<html", "<script", "<!doctype", "<svg", "<iframe", "<body",
        "<head", "<object", "<embed", "<?xml", "javascript:"};

    private ContentSniffer() {
    }

    /**
     * The type the head of a file shows, or empty when it is none of
     * {@link UploadType}. Text has no signature: bytes that read as plain text
     * and carry no markup are {@link UploadType#CSV} under a {@code .csv} name
     * and {@link UploadType#TEXT} otherwise.
     */
    public static Optional<UploadType> detect(byte[] head, String extension) {
        if (head == null || head.length == 0) {
            return Optional.empty();
        }
        UploadType binary = binaryTypeOf(head);
        if (binary != null) {
            return Optional.of(binary);
        }
        if (isPlainText(head) && !looksLikeMarkup(head)) {
            return Optional.of(UploadType.CSV.extensions().contains(extension) ? UploadType.CSV : UploadType.TEXT);
        }
        return Optional.empty();
    }

    /** {@link #detect(byte[], String)} for bytes with no file name to go by. */
    public static Optional<UploadType> detect(byte[] head) {
        return detect(head, "");
    }

    private static UploadType binaryTypeOf(byte[] head) {
        if (startsWith(head, PDF_MAGIC)) {
            return UploadType.PDF;
        }
        if (startsWith(head, PNG_MAGIC)) {
            return UploadType.PNG;
        }
        if (startsWith(head, JPEG_MAGIC)) {
            return UploadType.JPEG;
        }
        if (startsWith(head, ascii("GIF87a")) || startsWith(head, ascii("GIF89a"))) {
            return UploadType.GIF;
        }
        if (startsWith(head, ascii("RIFF")) && hasAt(head, 8, ascii("WEBP"))) {
            return UploadType.WEBP;
        }
        if (startsWith(head, new byte[] {'I', 'I', 0x2A, 0x00})
                || startsWith(head, new byte[] {'M', 'M', 0x00, 0x2A})) {
            return UploadType.TIFF;
        }
        if (isBitmap(head)) {
            return UploadType.BMP;
        }
        if (startsWith(head, OLE_MAGIC)) {
            return UploadType.DOC;
        }
        if (startsWith(head, ZIP_MAGIC)) {
            return UploadType.DOCX;
        }
        if (startsWith(head, ascii("{\\rtf"))) {
            return UploadType.RTF;
        }
        return audioTypeOf(head);
    }

    private static UploadType audioTypeOf(byte[] head) {
        // A UTF-16 byte-order mark (FF FE) reads as an MPEG frame sync: it is text.
        if (utf16Of(head) != null) {
            return null;
        }
        if (startsWith(head, EBML_MAGIC) && indexOf(head, ascii("webm"), 64) >= 0) {
            return UploadType.WEBM_AUDIO;
        }
        if (startsWith(head, ascii("OggS"))) {
            return UploadType.OGG_AUDIO;
        }
        if (hasAt(head, 4, ascii("ftyp"))) {
            return UploadType.MP4_AUDIO;
        }
        if (startsWith(head, ascii("ID3"))) {
            return UploadType.MP3;
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
            int layer = (head[1] >> 1) & 0x03;
            if (layer != 0) {
                return isMpegAudioFrame(head) ? UploadType.MP3 : null;
            }
            // An ADTS frame: twelve sync bits, layer 00.
            return (head[1] & 0xF0) == 0xF0 ? UploadType.AAC : null;
        }
        return null;
    }

    /** A real MPEG audio frame header: a defined version, bit rate and sampling rate. */
    private static boolean isMpegAudioFrame(byte[] head) {
        if (head.length < 4) {
            return false;
        }
        int version = (head[1] >> 3) & 0x03;
        int bitRate = (head[2] >> 4) & 0x0F;
        int samplingRate = (head[2] >> 2) & 0x03;
        return version != 1 && bitRate != 0 && bitRate != 0x0F && samplingRate != 3;
    }

    /** "BM", then the file size and two reserved zero words: two letters alone are not a bitmap. */
    private static boolean isBitmap(byte[] head) {
        return head.length >= 14 && head[0] == 'B' && head[1] == 'M'
            && head[6] == 0 && head[7] == 0 && head[8] == 0 && head[9] == 0;
    }

    /** Why a file whose signature was accepted is still refused. */
    public enum Flaw {
        /** The content is not the type its signature announced. */
        NOT_THE_TYPE,
        /** A document that carries a macro project. */
        MACRO
    }

    /**
     * What the whole file must also hold for its type, beyond the signature:
     * a word-processing document is a real one and carries no macro project,
     * and a text file is text to its last byte.
     *
     * @return {@code null} when the content stands, else why it does not
     */
    public static Flaw flawOf(UploadType type, InputStream content) throws IOException {
        return switch (type) {
            case DOCX -> flawOfWordArchive(content);
            case DOC -> contains(content, VBA_STORAGE) ? Flaw.MACRO : null;
            case TEXT, CSV -> flawOfText(content);
            default -> null;
        };
    }

    private static Flaw flawOfWordArchive(InputStream content) throws IOException {
        boolean document = false;
        boolean contentTypes = false;
        int entries = 0;
        long inflated = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(content)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    return Flaw.NOT_THE_TYPE;
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin") || name.endsWith("vbadata.xml")) {
                    return Flaw.MACRO;
                }
                document |= "word/document.xml".equals(name);
                contentTypes |= "[content_types].xml".equals(name);
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    inflated += read;
                    if (inflated > MAX_ARCHIVE_INFLATED_BYTES) {
                        return Flaw.NOT_THE_TYPE;
                    }
                }
            }
        } catch (java.util.zip.ZipException | java.io.EOFException | IllegalArgumentException malformed) {
            // A truncated or malformed archive is not a document.
            return Flaw.NOT_THE_TYPE;
        }
        return document && contentTypes ? null : Flaw.NOT_THE_TYPE;
    }

    private static Flaw flawOfText(InputStream content) throws IOException {
        byte[] all = content.readAllBytes();
        return isPlainText(all) && !looksLikeMarkup(all) ? null : Flaw.NOT_THE_TYPE;
    }

    /** No control byte a text file never holds; UTF-16 is read through its byte-order mark. */
    static boolean isPlainText(byte[] bytes) {
        if (utf16Of(bytes) != null) {
            return true;
        }
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean control = c < 0x09 || c == 0x0B || (c > 0x0D && c < 0x20 && c != 0x1B) || c == 0x7F;
            if (control) {
                return false;
            }
        }
        return true;
    }

    static boolean looksLikeMarkup(byte[] bytes) {
        Charset charset = utf16Of(bytes);
        String text = new String(bytes, charset != null ? charset : StandardCharsets.ISO_8859_1)
            .toLowerCase(Locale.ROOT);
        for (String marker : MARKUP) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return opensATag(text);
    }

    /**
     * Whether a {@code <} opens a tag, a comment or a processing instruction: followed by a letter, {@code !},
     * {@code ?} or {@code /}. A {@code <} before a digit, a space or {@code =} (« TA <120 », « a < b ») is text.
     */
    private static boolean opensATag(String text) {
        for (int at = text.indexOf('<'); at >= 0 && at + 1 < text.length(); at = text.indexOf('<', at + 1)) {
            char next = text.charAt(at + 1);
            if ((next >= 'a' && next <= 'z') || next == '!' || next == '?' || next == '/') {
                return true;
            }
        }
        return false;
    }

    private static Charset utf16Of(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    /** Whether the stream holds the pattern anywhere, read in one pass. */
    private static boolean contains(InputStream content, byte[] pattern) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int kept = 0;
        int read;
        while ((read = content.read(buffer, kept, buffer.length - kept)) != -1) {
            int filled = kept + read;
            if (indexOf(buffer, pattern, filled) >= 0) {
                return true;
            }
            // Keep the tail, so a pattern across two reads is still seen.
            kept = Math.min(pattern.length - 1, filled);
            System.arraycopy(buffer, filled - kept, buffer, 0, kept);
        }
        return false;
    }

    private static int indexOf(byte[] bytes, byte[] pattern, int limit) {
        int end = Math.min(limit, bytes.length) - pattern.length;
        for (int i = 0; i <= end; i++) {
            if (hasAt(bytes, i, pattern)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] head, byte[] magic) {
        return hasAt(head, 0, magic);
    }

    private static boolean hasAt(byte[] bytes, int offset, byte[] magic) {
        if (bytes.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
