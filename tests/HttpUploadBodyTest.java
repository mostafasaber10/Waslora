package com.waslora.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class HttpUploadBodyTest {
    public static void main(String[] args) throws Exception {
        copiesFixedLengthBody();
        copiesChunkedBodyAndTrailers();
        rejectsOversizedBody();
        rejectsMissingLength();
        rejectsAmbiguousLengthHeaders();
        System.out.println("Local-transfer upload tests passed.");
    }

    private static void copiesFixedLengthBody() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long copied = HttpUploadBody.copy(bytes("hello"), output, "5", null, 20);
        check(copied == 5 && "hello".equals(output.toString("UTF-8")), "fixed-length upload");
    }

    private static void copiesChunkedBodyAndTrailers() throws Exception {
        String body = "4;part=one\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Upload: complete\r\n\r\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long copied = HttpUploadBody.copy(bytes(body), output, null, "gzip, Chunked", 20);
        check(copied == 9 && "Wikipedia".equals(output.toString("UTF-8")), "chunked upload");
    }

    private static void rejectsOversizedBody() throws Exception {
        try {
            HttpUploadBody.copy(bytes("123"), new ByteArrayOutputStream(), "3", null, 2);
            throw new AssertionError("oversized body should be rejected");
        } catch (HttpUploadBody.UploadException expected) { check(expected.status == 413, "oversized body status"); }
    }

    private static void rejectsMissingLength() throws Exception {
        try {
            HttpUploadBody.copy(bytes("x"), new ByteArrayOutputStream(), null, null, 20);
            throw new AssertionError("missing body length should be rejected");
        } catch (HttpUploadBody.UploadException expected) { check(expected.status == 411, "missing length status"); }
    }

    private static void rejectsAmbiguousLengthHeaders() throws Exception {
        try {
            HttpUploadBody.copy(bytes("x"), new ByteArrayOutputStream(), "1", "chunked", 20);
            throw new AssertionError("conflicting body length headers should be rejected");
        } catch (HttpUploadBody.UploadException expected) { check(expected.status == 400, "ambiguous header status"); }
    }

    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
