package com.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * File storage service: handles image upload storage and retrieval.
 * Keeps HTTP handler clean by encapsulating file I/O logic.
 */
public class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path uploadDir;

    public FileStorageService(String uploadDir) {
        this.uploadDir = Paths.get(uploadDir != null ? uploadDir : "upload").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            log.warn("Failed to create upload directory: {}", e.getMessage());
        }
    }

    /**
     * Store an uploaded file. Returns the generated filename (without path prefix).
     *
     * @param tempFile     The temporary file from multipart upload
     * @param originalName Original filename (used to extract extension)
     * @return Saved filename (e.g., "uuid.jpg")
     * @throws IOException if storage fails
     */
    public String store(File tempFile, String originalName) throws IOException {
        String ext = extractExtension(originalName);
        String savedName = UUID.randomUUID() + ext;
        Path dest = uploadDir.resolve(savedName);
        Files.copy(tempFile.toPath(), dest);
        log.debug("Stored file: {} -> {}", originalName, savedName);
        return savedName;
    }

    /**
     * Read a stored file by name.
     *
     * @param filename The filename (without path)
     * @return File content as byte array
     * @throws FileNotFoundException if file doesn't exist or path traversal attempted
     * @throws IOException           if read fails
     */
    public byte[] read(String filename) throws IOException {
        String sanitized = sanitizeFilename(filename);
        if (sanitized.isEmpty()) {
            throw new FileNotFoundException("Invalid filename");
        }
        Path file = uploadDir.resolve(sanitized).normalize();
        if (!file.startsWith(uploadDir)) {
            throw new FileNotFoundException("Path traversal not allowed");
        }
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("File not found: " + filename);
        }
        return Files.readAllBytes(file);
    }

    /**
     * Guess content type from filename extension.
     */
    public String contentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) return ".jpg";
        String ext = filename.substring(filename.lastIndexOf('.'));
        if (!ext.matches("(?i)\\.(jpe?g|png|gif|webp)")) return ".jpg";
        return ext.toLowerCase();
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "";
        return name.replaceAll("[^a-zA-Z0-9._-]", "");
    }

    /**
     * Thrown when a requested file is not found or access is denied.
     */
    public static class FileNotFoundException extends IOException {
        public FileNotFoundException(String message) {
            super(message);
        }
    }
}
