package com.revesoft.tms.storage;

/** A file saved by {@link FileStorage}: its storage key plus what the client sent. */
public record StoredFile(String key, String contentType, long sizeBytes, String originalName) {
}
