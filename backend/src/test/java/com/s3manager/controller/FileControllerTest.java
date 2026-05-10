package com.s3manager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.s3manager.common.BizException;
import com.s3manager.common.GlobalExceptionHandler;
import com.s3manager.dto.*;
import com.s3manager.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileController 接口测试")
class FileControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private FileService fileService;

    @InjectMocks
    private FileController fileController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(fileController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ========== 普通上传 ==========

    @Nested
    @DisplayName("POST /api/files/upload")
    class UploadEndpoint {

        @Test
        @DisplayName("上传文件 - 200 成功")
        void upload_shouldReturn200() throws Exception {
            FileInfoVO vo = buildVO(1L, "test.txt", 1024L, 1);
            when(fileService.upload(any())).thenReturn(vo);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.txt", "text/plain", "hello".getBytes());

            mockMvc.perform(multipart("/api/files/upload").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.originalName").value("test.txt"))
                    .andExpect(jsonPath("$.data.fileSize").value(1024));
        }

        @Test
        @DisplayName("上传空文件 - 业务异常")
        void upload_emptyFile_shouldReturnError() throws Exception {
            when(fileService.upload(any())).thenThrow(new BizException(400, "上传文件不能为空"));

            MockMultipartFile file = new MockMultipartFile(
                    "file", "empty.txt", "text/plain", new byte[0]);

            mockMvc.perform(multipart("/api/files/upload").file(file))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("上传文件不能为空"));
        }
    }

    // ========== 文件列表 ==========

    @Nested
    @DisplayName("GET /api/files")
    class ListEndpoint {

        @Test
        @DisplayName("查询文件列表 - 默认分页")
        void list_default_shouldReturn200() throws Exception {
            Page<FileInfoVO> page = new Page<>(1, 10, 2);
            page.setRecords(List.of(
                    buildVO(1L, "a.txt", 100L, 1),
                    buildVO(2L, "b.pdf", 2048L, 1)
            ));
            when(fileService.listFiles(1, 10, null)).thenReturn(page);

            mockMvc.perform(get("/api/files"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.total").value(2))
                    .andExpect(jsonPath("$.data.records").isArray())
                    .andExpect(jsonPath("$.data.records.length()").value(2));
        }

        @Test
        @DisplayName("查询文件列表 - 带关键字和分页参数")
        void list_withParams_shouldPassParams() throws Exception {
            Page<FileInfoVO> page = new Page<>(2, 5, 1);
            page.setRecords(List.of(buildVO(3L, "report.pdf", 5000L, 1)));
            when(fileService.listFiles(2, 5, "report")).thenReturn(page);

            mockMvc.perform(get("/api/files")
                            .param("page", "2")
                            .param("size", "5")
                            .param("keyword", "report"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.records[0].originalName").value("report.pdf"));
        }
    }

    // ========== 下载 ==========

    @Nested
    @DisplayName("GET /api/files/download/{id}")
    class DownloadEndpoint {

        @Test
        @DisplayName("获取下载URL - 200 成功")
        void download_shouldReturnUrl() throws Exception {
            when(fileService.getDownloadUrl(1L)).thenReturn("https://s3.amazonaws.com/signed-url");

            mockMvc.perform(get("/api/files/download/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("https://s3.amazonaws.com/signed-url"));
        }

        @Test
        @DisplayName("获取下载URL - 文件不存在")
        void download_notFound_shouldReturn404() throws Exception {
            when(fileService.getDownloadUrl(999L)).thenThrow(new BizException(404, "文件不存在"));

            mockMvc.perform(get("/api/files/download/999"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("文件不存在"));
        }
    }

    // ========== 删除 ==========

    @Nested
    @DisplayName("DELETE /api/files/{id}")
    class DeleteEndpoint {

        @Test
        @DisplayName("删除文件 - 200 成功")
        void delete_shouldReturn200() throws Exception {
            doNothing().when(fileService).deleteFile(1L);

            mockMvc.perform(delete("/api/files/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(fileService).deleteFile(1L);
        }

        @Test
        @DisplayName("删除文件 - 文件不存在")
        void delete_notFound_shouldReturnError() throws Exception {
            doThrow(new BizException(404, "文件不存在")).when(fileService).deleteFile(999L);

            mockMvc.perform(delete("/api/files/999"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(404));
        }
    }

    // ========== 分片上传 ==========

    @Nested
    @DisplayName("分片上传接口")
    class MultipartEndpoints {

        @Test
        @DisplayName("POST /api/files/multipart/init - 初始化分片上传")
        void initMultipart_shouldReturn200() throws Exception {
            MultipartInitResponse resp = MultipartInitResponse.builder()
                    .fileId(10L).uploadId("upload-123").storageKey("2026/02/25/abc.zip").build();
            when(fileService.initMultipartUpload(any())).thenReturn(resp);

            MultipartInitRequest req = new MultipartInitRequest();
            req.setFileName("large.zip");
            req.setContentType("application/zip");
            req.setFileSize(104857600L);
            req.setTotalParts(10);

            mockMvc.perform(post("/api/files/multipart/init")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.fileId").value(10))
                    .andExpect(jsonPath("$.data.uploadId").value("upload-123"));
        }

        @Test
        @DisplayName("GET /api/files/multipart/presign - 获取分片预签名URL")
        void presignPart_shouldReturnUrl() throws Exception {
            when(fileService.getPresignedPartUrl(10L, 1))
                    .thenReturn("https://s3.amazonaws.com/presigned-part");

            mockMvc.perform(get("/api/files/multipart/presign")
                            .param("fileId", "10")
                            .param("partNumber", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("https://s3.amazonaws.com/presigned-part"));
        }

        @Test
        @DisplayName("POST /api/files/multipart/complete - 完成分片上传")
        void completeMultipart_shouldReturn200() throws Exception {
            FileInfoVO vo = buildVO(10L, "large.zip", 104857600L, 1);
            when(fileService.completeMultipartUpload(any())).thenReturn(vo);

            MultipartCompleteRequest req = new MultipartCompleteRequest();
            req.setFileId(10L);
            MultipartCompleteRequest.PartInfo p1 = new MultipartCompleteRequest.PartInfo();
            p1.setPartNumber(1);
            p1.setEtag("etag-1");
            req.setParts(List.of(p1));

            mockMvc.perform(post("/api/files/multipart/complete")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.originalName").value("large.zip"));
        }

        @Test
        @DisplayName("POST /api/files/multipart/abort - 取消分片上传")
        void abortMultipart_shouldReturn200() throws Exception {
            doNothing().when(fileService).abortMultipartUpload(10L);

            mockMvc.perform(post("/api/files/multipart/abort")
                            .param("fileId", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(fileService).abortMultipartUpload(10L);
        }
    }

    // ========== Helper ==========

    private FileInfoVO buildVO(Long id, String name, Long size, int status) {
        FileInfoVO vo = new FileInfoVO();
        vo.setId(id);
        vo.setOriginalName(name);
        vo.setContentType("application/octet-stream");
        vo.setFileSize(size);
        vo.setStatus(status);
        vo.setCreatedAt(LocalDateTime.now());
        return vo;
    }
}
