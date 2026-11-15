package com.waslora.app;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Reads fixed-length and HTTP/1.1 chunked request bodies into a temporary upload file. */
final class HttpUploadBody {
    private static final int BUFFER_SIZE = 512 * 1024;

    static final class UploadException extends Exception {
        final int status;
        UploadException(int status, String message) { super(message); this.status = status; }
    }

    private HttpUploadBody() {}

    static boolean isChunked(String transferEncoding) {
        if (transferEncoding == null) return false;
        for (String value : transferEncoding.split(",")) {
            if ("chunked".equals(value.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    static long copy(InputStream input, OutputStream output, String contentLength, String transferEncoding, long maxBytes) throws Exception {
        boolean chunked = isChunked(transferEncoding);
        if (chunked && contentLength != null) throw new UploadException(400, "conflicting upload length headers");
        if (chunked) return copyChunked(input, output, maxBytes);
        if (contentLength == null) throw new UploadException(411, "Content-Length required");

        final long length;
        try { length = Long.parseLong(contentLength); }
        catch (NumberFormatException e) { throw new UploadException(400, "invalid Content-Length"); }
        if (length < 0) throw new UploadException(400, "invalid Content-Length");
        if (length > maxBytes) throw new UploadException(413, "upload too large");

        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException("upload ended early");
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return length;
    }

    private static long copyChunked(InputStream input, OutputStream output, long maxBytes) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        while (true) {
            String line = readLine(input, 1024);
            if (line == null) throw new EOFException("chunk header ended early");
            int extension = line.indexOf(';');
            String sizeText = (extension >= 0 ? line.substring(0, extension) : line).trim();
            final long chunkSize;
            try { chunkSize = Long.parseLong(sizeText, 16); }
            catch (NumberFormatException e) { throw new UploadException(400, "invalid chunk size"); }
            if (chunkSize < 0) throw new UploadException(400, "invalid chunk size");
            if (chunkSize > maxBytes - total) throw new UploadException(413, "upload too large");
            if (chunkSize == 0) {
                while ((line = readLine(input, 8192)) != null && !line.isEmpty()) { }
                return total;
            }

            long remaining = chunkSize;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) throw new EOFException("upload chunk ended early");
                output.write(buffer, 0, read);
                remaining -= read;
                total += read;
            }
            if (input.read() != '\r' || input.read() != '\n') throw new UploadException(400, "invalid chunk ending");
        }
    }

    private static String readLine(InputStream input, int maxLength) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (bytes.size() <= maxLength) {
            int current = input.read();
            if (current < 0) return bytes.size() == 0 ? null : bytes.toString("UTF-8");
            if (previous == '\r' && current == '\n') {
                byte[] value = bytes.toByteArray();
                int size = value.length;
                if (size > 0 && value[size - 1] == '\r') size--;
                return new String(value, 0, size, "UTF-8");
            }
            bytes.write(current);
            previous = current;
        }
        throw new UploadException(400, "chunk header line too long");
    }
}
