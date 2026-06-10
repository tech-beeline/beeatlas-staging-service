package ru.beeline.staging.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class S3Config {

    @Value("${staging.s3.endpoint}")
    private String endpoint;

    @Value("${staging.s3.access-key}")
    private String accessKey;

    @Value("${staging.s3.secret-key}")
    private String secretKey;

    @Value("${staging.s3.region:us-east-1}")
    private String region;

    @Value("${staging.s3.bucket:staging-raw-data}")
    private String bucket;

    @Bean
    public MinioClient minioClient() {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .region(region)
                .build();
        ensureBucket(client);
        return client;
    }

    private void ensureBucket(MinioClient client) {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket '{}'", bucket);
            } else {
                log.info("MinIO bucket '{}' already exists", bucket);
            }
        } catch (Exception e) {
            log.warn("Could not verify MinIO bucket '{}': {}", bucket, e.getMessage());
        }
    }
}
