package ru.beeline.staging.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService {

    private final MinioClient minioClient;

    @Value("${staging.s3.bucket:staging-raw-data}")
    private String bucket;

    /**
     * Gzip-compresses rawBytes and uploads to MinIO under s3Key.
     * Returns the number of compressed bytes written.
     */
    public long putGzip(byte[] rawBytes, String s3Key) throws Exception {
        byte[] compressed = gzip(rawBytes);
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(s3Key)
                        .stream(new ByteArrayInputStream(compressed), compressed.length, -1)
                        .contentType("application/gzip")
                        .build()
        );
        log.info("Uploaded {} bytes (gzip) to s3://{}/{}", compressed.length, bucket, s3Key);
        return compressed.length;
    }

    public String getBucket() {
        return bucket;
    }

    /**
     * Downloads the gzip object at s3Key and returns the decompressed bytes.
     */
    public byte[] getGunzip(String s3Key) throws Exception {
        try (InputStream s3Stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(s3Key).build());
             GZIPInputStream gis = new GZIPInputStream(s3Stream);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gis.transferTo(out);
            return out.toByteArray();
        }
    }

    /** SHA-256 hex digest of raw (uncompressed) bytes. */
    public static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(data);
        }
        return bos.toByteArray();
    }
}
