package ru.truconf.proxydb.files;

public record StoredFile(
    String originalFileName,
    String mimeType,
    long sizeBytes,
    String filePath) {
}
