package com.example.hms.hl7.mllp;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Encodes and decodes HL7 v2 messages on the wire using the
 * <strong>Minimum Lower Layer Protocol</strong> (MLLP) framing:
 *
 * <pre>
 * &lt;VT=0x0B&gt; &lt;HL7 message body&gt; &lt;FS=0x1C&gt; &lt;CR=0x0D&gt;
 * </pre>
 *
 * <p>This is the framing every commodity lab analyzer (Mindray, Sysmex,
 * Roche Cobas) speaks. A single TCP connection may carry many frames
 * back-to-back, so {@link #readFrame(InputStream, int)} returns one frame
 * per call and is meant to be used in a loop.
 */
public final class MllpFrameCodec {

    public static final byte START_BLOCK = 0x0B;   // <VT>
    public static final byte END_BLOCK   = 0x1C;   // <FS>
    public static final byte CARRIAGE_RETURN = 0x0D; // <CR>

    private MllpFrameCodec() {}

    /**
     * Reads bytes from {@code in} until a complete MLLP frame is parsed,
     * then returns the inner HL7 body without the framing octets.
     *
     * @return the decoded body, or {@code null} if EOF is hit before any
     *         start-block byte is seen (graceful end-of-stream).
     * @throws MllpProtocolException if framing is malformed or the body
     *         exceeds {@code maxBytes}.
     * @throws IOException on underlying I/O errors.
     */
    public static byte[] readFrame(InputStream in, int maxBytes) throws IOException {
        // Skip junk until we see <VT>. Tolerate idle bytes per common analyzer behavior.
        int b;
        do {
            b = in.read();
            if (b == -1) return null;       // graceful EOF before any frame
        } while (b != START_BLOCK);

        ByteArrayOutputStream body = new ByteArrayOutputStream(4 * 1024);
        boolean sawEndBlock = false;
        while (true) {
            b = in.read();
            if (b == -1) {
                throw new EOFException("MLLP frame truncated: EOF before <FS><CR>");
            }
            if (sawEndBlock) {
                if (b == CARRIAGE_RETURN) return body.toByteArray();
                throw new MllpProtocolException("Expected <CR> after <FS>, got 0x" + Integer.toHexString(b));
            }
            if (b == END_BLOCK) {
                sawEndBlock = true;
                continue;
            }
            if (body.size() >= maxBytes) {
                throw new MllpProtocolException("MLLP frame exceeds limit of " + maxBytes + " bytes");
            }
            body.write(b);
        }
    }

    /**
     * Writes a complete MLLP frame for the given HL7 v2 message body.
     * The message is encoded with {@code charset}.
     */
    public static void writeFrame(OutputStream out, String message, Charset charset) throws IOException {
        out.write(START_BLOCK);
        out.write(message.getBytes(charset));
        out.write(END_BLOCK);
        out.write(CARRIAGE_RETURN);
        out.flush();
    }
}
