package com.s3manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.s3manager.common.BizException;
import com.s3manager.config.S3Config;
import com.s3manager.dto.MultipartCompleteRequest;
import com.s3manager.dto.MultipartInitRequest;
import com.s3manager.entity.FileInfo;
import com.s3manager.entity.MultipartRecord;
import com.s3manager.mapper.FileInfoMapper;
import com.s3manager.mapper.MultipartRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileService 单元测试")
class FileServiceTest {

    @Mock
    private S3Service s3Service;
    @Mock
    private S3Config s3Config;
    @Mock
    private FileInfoMapper fileInfoMapper;
    @Mock
    private MultipartRecordMapper multipartRecordMapper;

    @InjectMocks
    private FileService fileService;

    @BeforeEach
    void setUp() {
        lenient().when(s3Config.getBucketName()).thenReturn("test-bucket");
    }

    // ========== 普通上传测试 ==========

    @Nested
    @DisplayName("普通文件上传")
    class UploadTests {

        @Test
        @DisplayName("上传正常文件 - 应成功保存并返回VO")
        void upload_normalFile_shouldSucceed() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.txt", "text/plain", "hello world".getBytes());
            when(fileInfoMapper.insert(any(FileInfo.class))).thenAnswer(inv -> {
                FileInfo fi = inv.getArgument(0);
                fi.setId(1L);
                return 1;
            });

            var result = fileService.upload(file);

            assertNotNull(result);
            assertEquals("test.txt", result.getOriginalName());
            assertEquals(1, result.getStatus());
            verify(s3Service).uploadFile(anyString(), any(), eq((long) "hello world".length()), eq("text/plain"));
            verify(fileInfoMapper).insert(any(FileInfo.class));
            verify(fileInfoMapper).updateById(any(FileInfo.class));
        }

        @Test
        @DisplayName("上传空文件 - 应抛出BizException")
        void upload_emptyFile_shouldThrow() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "empty.txt", "text/plain", new byte[0]);

            BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("不能为空"));
        }

        @Test
        @DisplayName("上传文件 - contentType为null时使用默认值")
        void upload_nullContentType_shouldUseDefault() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "data.bin", null, "binary data".getBytes());
            when(fileInfoMapper.insert(any(FileInfo.class))).thenAnswer(inv -> {
                FileInfo fi = inv.getArgument(0);
                fi.setId(2L);
                return 1;
            });

            var result = fileService.upload(file);

            assertNotNull(result);
            verify(s3Service).uploadFile(anyString(), any(), anyLong(), eq("application/octet-stream"));
        }

        @Test
        @DisplayName("S3上传失败 - 应标记失败状态并抛出异常")
        void upload_s3Failure_shouldMarkFailedAndThrow() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "fail.txt", "text/plain", "data".getBytes());
            when(fileInfoMapper.insert(any(FileInfo.class))).thenReturn(1);
            when(fileInfoMapper.updateById(any(FileInfo.class))).thenReturn(1);
            doThrow(new RuntimeException("S3 connection refused"))
                    .when(s3Service).uploadFile(anyString(), any(), anyLong(), anyString());

            BizException ex = assertThrows(BizException.class, () -> fileService.upload(file));
            assertEquals(500, ex.getCode());

            ArgumentCaptor<FileInfo> captor = ArgumentCaptor.forClass(FileInfo.class);
            verify(fileInfoMapper).updateById(captor.capture());
            assertEquals(3, captor.getValue().getStatus());
        }
    }

    // ========== 文件列表测试 ==========

    @Nested
    @DisplayName("文件列表查询")
    class ListTests {

        @Test
        @DisplayName("查询文件列表 - 无关键字")
        @SuppressWarnings("unchecked")
        void listFiles_noKeyword_shouldReturnPage() {
            Page<FileInfo> mockPage = new Page<>(1, 10, 0);
            mockPage.setRecords(List.of());
            when(fileInfoMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);

            var result = fileService.listFiles(1, 10, null);

            assertNotNull(result);
            assertEquals(0, result.getTotal());
            verify(fileInfoMapper).selectPage(any(), any());
        }

        @Test
        @DisplayName("查询文件列表 - 带关键字搜索")
        @SuppressWarnings("unchecked")
        void listFiles_withKeyword_shouldFilter() {
            FileInfo fi = new FileInfo();
            fi.setId(1L);
            fi.setOriginalName("report.pdf");
            fi.setContentType("application/pdf");
            fi.setFileSize(1024L);
            fi.setStatus(1);

            Page<FileInfo> mockPage = new Page<>(1, 10, 1);
            mockPage.setRecords(List.of(fi));
            when(fileInfoMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);

            var result = fileService.listFiles(1, 10, "report");

            assertEquals(1, result.getTotal());
            assertEquals("report.pdf", result.getRecords().get(0).getOriginalName());
        }
    }

    // ========== 下载测试 ==========

    @Nested
    @DisplayName("文件下载")
    class DownloadTests {

        @Test
        @DisplayName("获取下载URL - 文件存在")
        void getDownloadUrl_existingFile_shouldReturnUrl() {
            FileInfo fi = buildFileInfo(1L, "doc.pdf", 1);
            when(fileInfoMapper.selectById(1L)).thenReturn(fi);
            when(s3Service.generatePresignedDownloadUrl("test-bucket", fi.getStorageKey(), "doc.pdf"))
                    .thenReturn("https://s3.amazonaws.com/signed-url");

            String url = fileService.getDownloadUrl(1L);

            assertNotNull(url);
            assertTrue(url.contains("signed-url"));
        }

        @Test
        @DisplayName("获取下载URL - 文件不存在应抛异常")
        void getDownloadUrl_notFound_shouldThrow() {
            when(fileInfoMapper.selectById(999L)).thenReturn(null);

            BizException ex = assertThrows(BizException.class, () -> fileService.getDownloadUrl(999L));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("获取下载URL - 已删除文件应抛异常")
        void getDownloadUrl_deletedFile_shouldThrow() {
            FileInfo fi = buildFileInfo(1L, "deleted.pdf", 2);
            when(fileInfoMapper.selectById(1L)).thenReturn(fi);

            BizException ex = assertThrows(BizException.class, () -> fileService.getDownloadUrl(1L));
            assertEquals(404, ex.getCode());
        }
    }

    // ========== 删除测试 ==========

    @Nested
    @DisplayName("文件删除")
    class DeleteTests {

        @Test
        @DisplayName("删除文件 - 正常删除")
        void deleteFile_existing_shouldSoftDelete() {
            FileInfo fi = buildFileInfo(1L, "remove.txt", 1);
            when(fileInfoMapper.selectById(1L)).thenReturn(fi);
            when(fileInfoMapper.updateById(any())).thenReturn(1);

            fileService.deleteFile(1L);

            verify(s3Service).deleteFile("test-bucket", fi.getStorageKey());
            ArgumentCaptor<FileInfo> captor = ArgumentCaptor.forClass(FileInfo.class);
            verify(fileInfoMapper).updateById(captor.capture());
            assertEquals(2, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("删除文件 - 文件不存在应抛异常")
        void deleteFile_notFound_shouldThrow() {
            when(fileInfoMapper.selectById(999L)).thenReturn(null);

            assertThrows(BizException.class, () -> fileService.deleteFile(999L));
            verify(s3Service, never()).deleteFile(anyString(), anyString());
        }
    }

    // ========== 分片上传测试 ==========

    @Nested
    @DisplayName("分片上传")
    class MultipartTests {

        @Test
        @DisplayName("初始化分片上传 - 应返回fileId和uploadId")
        void initMultipartUpload_shouldReturnResponse() {
            when(s3Service.initiateMultipartUpload(anyString(), eq("application/zip")))
                    .thenReturn("upload-abc-123");
            when(fileInfoMapper.insert(any(FileInfo.class))).thenAnswer(inv -> {
                FileInfo fi = inv.getArgument(0);
                fi.setId(10L);
                return 1;
            });

            MultipartInitRequest request = new MultipartInitRequest();
            request.setFileName("large-file.zip");
            request.setContentType("application/zip");
            request.setFileSize(104857600L);
            request.setTotalParts(10);

            var result = fileService.initMultipartUpload(request);

            assertNotNull(result);
            assertEquals(10L, result.getFileId());
            assertEquals("upload-abc-123", result.getUploadId());
            assertNotNull(result.getStorageKey());
        }

        @Test
        @DisplayName("获取分片预签名URL - 正常获取")
        void getPresignedPartUrl_shouldReturnUrl() {
            FileInfo fi = buildFileInfo(10L, "large.zip", 0);
            fi.setUploadId("upload-abc");
            when(fileInfoMapper.selectById(10L)).thenReturn(fi);
            when(s3Service.generatePresignedUploadPartUrl(fi.getStorageKey(), "upload-abc", 1))
                    .thenReturn("https://s3.amazonaws.com/presigned-part-url");

            String url = fileService.getPresignedPartUrl(10L, 1);

            assertNotNull(url);
            assertTrue(url.contains("presigned-part-url"));
        }

        @Test
        @DisplayName("获取分片预签名URL - 非分片文件应抛异常")
        void getPresignedPartUrl_notMultipart_shouldThrow() {
            FileInfo fi = buildFileInfo(1L, "normal.txt", 1);
            fi.setUploadId(null);
            when(fileInfoMapper.selectById(1L)).thenReturn(fi);

            BizException ex = assertThrows(BizException.class,
                    () -> fileService.getPresignedPartUrl(1L, 1));
            assertEquals(400, ex.getCode());
        }

        @Test
        @DisplayName("完成分片上传 - 应合并分片并更新状态")
        void completeMultipartUpload_shouldComplete() {
            FileInfo fi = buildFileInfo(10L, "large.zip", 0);
            fi.setUploadId("upload-abc");
            when(fileInfoMapper.selectById(10L)).thenReturn(fi);
            when(fileInfoMapper.updateById(any())).thenReturn(1);
            when(multipartRecordMapper.insert(any(MultipartRecord.class))).thenReturn(1);

            MultipartCompleteRequest request = new MultipartCompleteRequest();
            request.setFileId(10L);
            MultipartCompleteRequest.PartInfo p1 = new MultipartCompleteRequest.PartInfo();
            p1.setPartNumber(1);
            p1.setEtag("etag-1");
            MultipartCompleteRequest.PartInfo p2 = new MultipartCompleteRequest.PartInfo();
            p2.setPartNumber(2);
            p2.setEtag("etag-2");
            request.setParts(List.of(p1, p2));

            var result = fileService.completeMultipartUpload(request);

            assertNotNull(result);
            verify(s3Service).completeMultipartUpload(eq(fi.getStorageKey()), eq("upload-abc"), anyList());
            verify(multipartRecordMapper, times(2)).insert(any(MultipartRecord.class));

            ArgumentCaptor<FileInfo> captor = ArgumentCaptor.forClass(FileInfo.class);
            verify(fileInfoMapper).updateById(captor.capture());
            assertEquals(1, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("完成分片上传 - 非分片文件应抛异常")
        void completeMultipartUpload_notMultipart_shouldThrow() {
            FileInfo fi = buildFileInfo(1L, "normal.txt", 1);
            fi.setUploadId(null);
            when(fileInfoMapper.selectById(1L)).thenReturn(fi);

            MultipartCompleteRequest request = new MultipartCompleteRequest();
            request.setFileId(1L);
            request.setParts(List.of());

            assertThrows(BizException.class, () -> fileService.completeMultipartUpload(request));
        }

        @Test
        @DisplayName("取消分片上传 - 应调用abort并更新状态")
        void abortMultipartUpload_shouldAbort() {
            FileInfo fi = buildFileInfo(10L, "large.zip", 0);
            fi.setUploadId("upload-abc");
            when(fileInfoMapper.selectById(10L)).thenReturn(fi);
            when(fileInfoMapper.updateById(any())).thenReturn(1);

            fileService.abortMultipartUpload(10L);

            verify(s3Service).abortMultipartUpload(fi.getStorageKey(), "upload-abc");
            ArgumentCaptor<FileInfo> captor = ArgumentCaptor.forClass(FileInfo.class);
            verify(fileInfoMapper).updateById(captor.capture());
            assertEquals(2, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("取消分片上传 - 非分片文件应抛异常")
        void abortMultipartUpload_notMultipart_shouldThrow() {
            FileInfo fi = buildFileInfo(1L, "normal.txt", 1);
            fi.setUploadId(null);
            when(fileInfoMapper.selectById(1L)).thenReturn(fi);

            assertThrows(BizException.class, () -> fileService.abortMultipartUpload(1L));
        }
    }

    // ========== Helper ==========

    private FileInfo buildFileInfo(Long id, String name, int status) {
        FileInfo fi = new FileInfo();
        fi.setId(id);
        fi.setOriginalName(name);
        fi.setStorageKey("2026/02/25/abc123" + name.substring(name.lastIndexOf(".")));
        fi.setContentType("application/octet-stream");
        fi.setFileSize(1024L);
        fi.setBucketName("test-bucket");
        fi.setStatus(status);
        return fi;
    }
}
