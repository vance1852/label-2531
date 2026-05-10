package com.s3manager.service;

import com.s3manager.config.S3Config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Config s3Config;

    /**
     * 上传文件到 S3
     */
    public void uploadFile(String key, InputStream inputStream, long contentLength, String contentType) {
        log.info("Uploading file to S3: key={}, size={}, type={}", key, contentLength, contentType);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(s3Config.getBucketName())
                        .key(key)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build(),
                RequestBody.fromInputStream(inputStream, contentLength)
        );
    }

    /**
     * 从 S3 获取文件流
     */
    public software.amazon.awssdk.core.ResponseInputStream<GetObjectResponse> downloadFile(String bucket, String key) {
        log.info("Downloading file from S3: bucket={}, key={}", bucket, key);
        return s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build()
        );
    }

    /**
     * 删除 S3 文件
     */
    public void deleteFile(String bucket, String key) {
        log.info("Deleting file from S3: bucket={}, key={}", bucket, key);
        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build()
        );
    }

    /**
     * 生成下载预签名 URL（强制下载，不预览）
     */
    public String generatePresignedDownloadUrl(String bucket, String key, String originalName) {
        String encodedName = URLEncoder.encode(originalName, StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName;
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(s3Config.getPresignExpirationMinutes()))
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .responseContentDisposition(disposition)
                                .build())
                        .build()
        );
        return presigned.url().toString();
    }

    // ========== 分片上传 ==========

    /**
     * 初始化分片上传
     */
    public String initiateMultipartUpload(String key, String contentType) {
        log.info("Initiating multipart upload: key={}, type={}", key, contentType);
        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(s3Config.getBucketName())
                        .key(key)
                        .contentType(contentType)
                        .build()
        );
        return response.uploadId();
    }

    /**
     * 生成分片上传预签名 URL
     */
    public String generatePresignedUploadPartUrl(String key, String uploadId, int partNumber) {
        PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(
                UploadPartPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(s3Config.getPresignExpirationMinutes()))
                        .uploadPartRequest(UploadPartRequest.builder()
                                .bucket(s3Config.getBucketName())
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .build())
                        .build()
        );
        return presigned.url().toString();
    }

    /**
     * 完成分片上传
     */
    public void completeMultipartUpload(String key, String uploadId, List<CompletedPart> parts) {
        log.info("Completing multipart upload: key={}, uploadId={}, parts={}", key, uploadId, parts.size());
        s3Client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                        .bucket(s3Config.getBucketName())
                        .key(key)
                        .uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder()
                                .parts(parts)
                                .build())
                        .build()
        );
    }

    /**
     * 取消分片上传
     */
    public void abortMultipartUpload(String key, String uploadId) {
        log.info("Aborting multipart upload: key={}, uploadId={}", key, uploadId);
        s3Client.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                        .bucket(s3Config.getBucketName())
                        .key(key)
                        .uploadId(uploadId)
                        .build()
        );
    }
}
