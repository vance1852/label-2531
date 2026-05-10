package com.s3manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.s3manager.common.BizException;
import com.s3manager.config.S3Config;
import com.s3manager.dto.*;
import com.s3manager.entity.FileInfo;
import com.s3manager.entity.MultipartRecord;
import com.s3manager.mapper.FileInfoMapper;
import com.s3manager.mapper.MultipartRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final S3Service s3Service;
    private final S3Config s3Config;
    private final FileInfoMapper fileInfoMapper;
    private final MultipartRecordMapper multipartRecordMapper;

    /**
     * 普通文件上传
     */
    @Transactional
    public FileInfoVO upload(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException(400, "上传文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        String storageKey = generateStorageKey(originalName);
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

        // 先写数据库（可回滚），再上传 S3（不可回滚）
        FileInfo fileInfo = new FileInfo();
        fileInfo.setOriginalName(originalName);
        fileInfo.setStorageKey(storageKey);
        fileInfo.setContentType(contentType);
        fileInfo.setFileSize(file.getSize());
        fileInfo.setBucketName(s3Config.getBucketName());
        fileInfo.setStatus(0); // 上传中
        fileInfoMapper.insert(fileInfo);

        try {
            s3Service.uploadFile(storageKey, file.getInputStream(), file.getSize(), contentType);
        } catch (IOException e) {
            log.error("File upload failed: {}", e.getMessage());
        }

        fileInfo.setStatus(1); // 已完成
        fileInfoMapper.updateById(fileInfo);

        log.info("File uploaded: id={}, name={}, size={}", fileInfo.getId(), originalName, file.getSize());
        return FileInfoVO.from(fileInfo);
    }

    /**
     * 分页查询文件列表
     */
    public Page<FileInfoVO> listFiles(int page, int size, String keyword) {
        Page<FileInfo> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<FileInfo> wrapper = new LambdaQueryWrapper<FileInfo>()
                .ne(FileInfo::getStatus, 2)
                .like(keyword != null && !keyword.isBlank(), FileInfo::getOriginalName, keyword)
                .orderByDesc(FileInfo::getCreatedAt);

        Page<FileInfo> result = fileInfoMapper.selectPage(pageParam, wrapper);

        Page<FileInfoVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(FileInfoVO::from).toList());
        return voPage;
    }

    /**
     * 获取下载预签名 URL
     */
    public String getDownloadUrl(Long id) {
        FileInfo fileInfo = getFileOrThrow(id);
        return s3Service.generatePresignedDownloadUrl(
                fileInfo.getBucketName(), fileInfo.getStorageKey(), fileInfo.getOriginalName());
    }

    /**
     * 删除文件
     */
    @Transactional
    public void deleteFile(Long id) {
        FileInfo fileInfo = getFileOrThrow(id);

        s3Service.deleteFile(fileInfo.getBucketName(), fileInfo.getStorageKey());

        fileInfo.setStatus(2);
        fileInfoMapper.updateById(fileInfo);
        log.info("File deleted: id={}, name={}", id, fileInfo.getOriginalName());
    }

    // ========== 分片上传 ==========

    /**
     * 初始化分片上传
     */
    @Transactional
    public MultipartInitResponse initMultipartUpload(MultipartInitRequest request) {
        String storageKey = generateStorageKey(request.getFileName());
        String uploadId = s3Service.initiateMultipartUpload(storageKey, request.getContentType());

        FileInfo fileInfo = new FileInfo();
        fileInfo.setOriginalName(request.getFileName());
        fileInfo.setStorageKey(storageKey);
        fileInfo.setContentType(request.getContentType());
        fileInfo.setFileSize(request.getFileSize());
        fileInfo.setBucketName(s3Config.getBucketName());
        fileInfo.setUploadId(uploadId);
        fileInfo.setStatus(0);
        fileInfoMapper.insert(fileInfo);

        log.info("Multipart upload initiated: fileId={}, uploadId={}", fileInfo.getId(), uploadId);

        return MultipartInitResponse.builder()
                .fileId(fileInfo.getId())
                .uploadId(uploadId)
                .storageKey(storageKey)
                .build();
    }

    /**
     * 获取分片预签名 URL
     */
    public String getPresignedPartUrl(Long fileId, int partNumber) {
        FileInfo fileInfo = getFileOrThrow(fileId);
        if (fileInfo.getUploadId() == null) {
            throw new BizException(400, "该文件不是分片上传类型");
        }
        return s3Service.generatePresignedUploadPartUrl(
                fileInfo.getStorageKey(), fileInfo.getUploadId(), partNumber);
    }

    /**
     * 完成分片上传
     */
    @Transactional
    public FileInfoVO completeMultipartUpload(MultipartCompleteRequest request) {
        FileInfo fileInfo = getFileOrThrow(request.getFileId());
        if (fileInfo.getUploadId() == null) {
            throw new BizException(400, "该文件不是分片上传类型");
        }

        List<CompletedPart> completedParts = request.getParts().stream()
                .map(p -> CompletedPart.builder()
                        .partNumber(p.getPartNumber())
                        .eTag(p.getEtag())
                        .build())
                .toList();

        s3Service.completeMultipartUpload(fileInfo.getStorageKey(), fileInfo.getUploadId(), completedParts);

        // 保存分片记录
        for (MultipartCompleteRequest.PartInfo part : request.getParts()) {
            MultipartRecord record = new MultipartRecord();
            record.setFileId(fileInfo.getId());
            record.setPartNumber(part.getPartNumber());
            record.setEtag(part.getEtag());
            record.setStatus(1);
            multipartRecordMapper.insert(record);
        }

        fileInfo.setStatus(1);
        fileInfoMapper.updateById(fileInfo);

        log.info("Multipart upload completed: fileId={}, parts={}", fileInfo.getId(), request.getParts().size());
        return FileInfoVO.from(fileInfo);
    }

    /**
     * 取消分片上传
     */
    @Transactional
    public void abortMultipartUpload(Long fileId) {
        FileInfo fileInfo = getFileOrThrow(fileId);
        if (fileInfo.getUploadId() == null) {
            throw new BizException(400, "该文件不是分片上传类型");
        }

        s3Service.abortMultipartUpload(fileInfo.getStorageKey(), fileInfo.getUploadId());

        fileInfo.setStatus(2);
        fileInfoMapper.updateById(fileInfo);
        log.info("Multipart upload aborted: fileId={}", fileId);
    }

    // ========== Private ==========

    private FileInfo getFileOrThrow(Long id) {
        FileInfo fileInfo = fileInfoMapper.selectById(id);
        if (fileInfo == null || fileInfo.getStatus() == 2) {
            throw new BizException(404, "文件不存在");
        }
        return fileInfo;
    }

    private String generateStorageKey(String originalName) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }
        return date + "/" + uuid + ext;
    }
}
