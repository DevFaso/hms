package com.example.hms.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bytes that carry the real signature of each upload type, for tests of the
 * upload paths: the content check reads the file, so a test file must be what
 * its name says.
 */
public final class UploadSamples {

    private UploadSamples() {
    }

    public static byte[] jpeg() {
        return jpeg(64);
    }

    /** A JPEG signature padded to {@code size} bytes. */
    public static byte[] jpeg(int size) {
        return padded(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0},
            size);
    }

    public static byte[] png() {
        return padded(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 64);
    }

    public static byte[] gif() {
        return padded("GIF89a".getBytes(StandardCharsets.US_ASCII), 64);
    }

    public static byte[] webp() {
        return padded(new byte[] {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '}, 64);
    }

    public static byte[] pdf() {
        return "%PDF-1.7\n1 0 obj\n<< >>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] ogg() {
        return ogg(64);
    }

    /** An Ogg signature padded to {@code size} bytes. */
    public static byte[] ogg(int size) {
        return padded("OggS".getBytes(StandardCharsets.US_ASCII), size);
    }

    /** An HTML page: what a spoofed file holds. */
    public static byte[] html() {
        return "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] svg() {
        return ("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"><circle r=\"4\"/></svg>")
            .getBytes(StandardCharsets.UTF_8);
    }

    /** A word-processing document: the two parts every one has, and a macro project when asked. */
    public static byte[] docx(boolean withMacro) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "[Content_Types].xml", "<Types/>");
            entry(zip, "word/document.xml", "<w:document/>");
            if (withMacro) {
                entry(zip, "word/vbaProject.bin", "macro");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static byte[] padded(byte[] signature, int size) {
        byte[] bytes = Arrays.copyOf(signature, Math.max(size, signature.length));
        Arrays.fill(bytes, signature.length, bytes.length, (byte) 0x20);
        return bytes;
    }
}
