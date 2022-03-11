package com.ykrenz.fileserver.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ykrenz.fastdfs.common.Crc32;
import com.ykrenz.fileserver.entity.FileInfo;
import com.ykrenz.fileserver.entity.FilePartInfo;
import com.ykrenz.fileserver.ex.ApiException;
import com.ykrenz.fileserver.mapper.FileInfoMapper;
import com.ykrenz.fileserver.mapper.FilePartInfoMapper;
import com.ykrenz.fileserver.model.ErrorCode;
import com.ykrenz.fileserver.model.request.CancelPartRequest;
import com.ykrenz.fileserver.model.request.CompletePartRequest;
import com.ykrenz.fileserver.model.request.InitPartRequest;
import com.ykrenz.fileserver.model.request.SimpleUploadRequest;
import com.ykrenz.fileserver.model.request.UploadPartRequest;
import com.ykrenz.fileserver.model.result.InitPartResult;
import com.ykrenz.fastdfs.FastDfs;
import com.ykrenz.fastdfs.model.CompleteMultipartRequest;
import com.ykrenz.fastdfs.model.FileInfoRequest;
import com.ykrenz.fastdfs.model.InitMultipartUploadRequest;
import com.ykrenz.fastdfs.model.UploadFileRequest;
import com.ykrenz.fastdfs.model.UploadMultipartPartRequest;
import com.ykrenz.fastdfs.model.fdfs.StorePath;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ykren
 * @date 2022/3/1
 */
@Slf4j
public class FastDfsServerClient implements FileServerClient {

    private FastDfs fastDfs;

    @Autowired
    private FileInfoMapper fileInfoMapper;

    @Autowired
    private FilePartInfoMapper filePartInfoMapper;

    public FastDfsServerClient(FastDfs fastDfs) {
        this.fastDfs = fastDfs;
    }

    @Override
    public FileInfo upload(SimpleUploadRequest request) throws IOException {
        MultipartFile file = request.getFile();
        String originalFilename = file.getOriginalFilename();
        UploadFileRequest uploadFileRequest = UploadFileRequest.builder()
                .stream(file.getInputStream(), file.getSize(),
                        FilenameUtils.getExtension(originalFilename))
                .build();
        StorePath storePath = fastDfs.uploadFile(uploadFileRequest);
        checkCrc32(request.getCrc32(), storePath);
        FileInfo fileInfo = new FileInfo();
        fileInfo.setBucketName(storePath.getGroup());
        fileInfo.setObjectName(storePath.getPath());
        // 注意: 这里保存的是有符号crc32 和fastdfs返回的不一致
        fileInfo.setCrc32(request.getCrc32());
        fileInfo.setMd5(request.getMd5());

        fileInfoMapper.insert(fileInfo);
        return fileInfo;
    }

    private void checkCrc32(Long crc32, StorePath storePath) {
        if (crc32 != null && crc32 > 0) {
            com.ykrenz.fastdfs.model.fdfs.FileInfo fileInfo = fastDfs.queryFileInfo(storePath.getGroup(), storePath.getPath());
            log.debug("check crc32 client crc32={} fastdfs crc32 ={} convert crc32={}",
                    crc32, fileInfo.getCrc32(), Crc32.convertUnsigned(fileInfo.getCrc32()));
            if (crc32 != Crc32.convertUnsigned(fileInfo.getCrc32())) {
                throw new ApiException(ErrorCode.FILE_CRC32_ERROR);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InitPartResult initMultipart(InitPartRequest request) {
        String uploadId = request.getUploadId();
        if (StringUtils.isNotBlank(uploadId)) {
            return check(request);
        }
        StorePath storePath = fastDfs.initMultipartUpload(request.getFileSize(),
                FilenameUtils.getExtension(request.getFileName()));
        FilePartInfo filePartInfo = new FilePartInfo();
        filePartInfo.setFileName(request.getFileName());
        filePartInfo.setBucketName(storePath.getGroup());
        filePartInfo.setObjectName(storePath.getPath());
        filePartInfo.setPartNumber(0);
        filePartInfo.setPartSize(request.getPartSize());
        filePartInfo.setFileSize(request.getFileSize());

        filePartInfo.setUploadId(request.getUploadId());
        filePartInfoMapper.insert(filePartInfo);
        return new InitPartResult(filePartInfo.getId(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FilePartInfo uploadMultipart(UploadPartRequest request) throws IOException {
        MultipartFile file = request.getFile();
        Integer partNumber = request.getPartNumber();
        FilePartInfo initPart = getInitPart(request.getUploadId());
        if (initPart == null) {
            throw new ApiException(ErrorCode.UPLOAD_ID_NOT_FOUND);
        }
        UploadMultipartPartRequest multipartPartRequest = UploadMultipartPartRequest.builder()
                .groupName(initPart.getBucketName())
                .path(initPart.getObjectName())
                .streamPart(file.getInputStream(), file.getSize(), partNumber, initPart.getPartSize())
                .build();
        fastDfs.uploadMultipart(multipartPartRequest);

        FilePartInfo filePartInfo = new FilePartInfo();
        filePartInfo.setUploadId(request.getUploadId());
        filePartInfo.setBucketName(initPart.getBucketName());
        filePartInfo.setObjectName(initPart.getObjectName());
        filePartInfo.setFileName(initPart.getFileName());
        filePartInfo.setPartNumber(partNumber);
        filePartInfo.setPartSize(initPart.getPartSize());
        filePartInfo.setFileSize(file.getSize());

        filePartInfoMapper.insert(filePartInfo);
        return filePartInfo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileInfo completeMultipart(CompletePartRequest request) {
        FilePartInfo initPart = getInitPart(request.getUploadId());
        if (initPart == null) {
            throw new ApiException(ErrorCode.UPLOAD_ID_NOT_FOUND);
        }

        StorePath storePath = new StorePath(initPart.getBucketName(), initPart.getObjectName());
        try {
            CompleteMultipartRequest multipartRequest = CompleteMultipartRequest.builder()
                    .groupName(storePath.getGroup())
                    .path(storePath.getPath())
                    // 6.0.2以下版本设置为false
                    .regenerate(true)
                    .build();
            storePath = fastDfs.completeMultipartUpload(multipartRequest);

            checkCrc32(request.getFileCrc32(), storePath);

            FileInfo fileInfo = new FileInfo();
            fileInfo.setBucketName(storePath.getGroup());
            fileInfo.setObjectName(storePath.getPath());
            fileInfo.setCrc32(request.getFileCrc32());
            fileInfo.setMd5(request.getFileMd5());
            fileInfo.setFileSize(initPart.getFileSize());
            fileInfo.setFileName(initPart.getFileName());
            fileInfoMapper.insert(fileInfo);

            //清空所有分片记录
            List<String> parts = filePartInfoMapper.selectList(Wrappers.<FilePartInfo>lambdaQuery()
                    .eq(FilePartInfo::getUploadId, request.getUploadId())
                    .select(FilePartInfo::getId)).stream().map(FilePartInfo::getId).collect(Collectors.toList());
            filePartInfoMapper.deleteBatchIds(parts);
            filePartInfoMapper.deleteById(request.getUploadId());
            return fileInfo;
        } catch (Exception e) {
            CancelPartRequest cancelPartRequest = new CancelPartRequest();
            cancelPartRequest.setUploadId(request.getUploadId());
            cancelMultipart(cancelPartRequest);
            if (e instanceof ApiException) {
                throw e;
            }
            throw new ApiException(ErrorCode.UPLOAD_ERROR);
        }
    }

    private FilePartInfo getInitPart(String uploadId) {
        //TODO 加入缓存
        return filePartInfoMapper.selectOne(Wrappers.<FilePartInfo>lambdaQuery()
                .eq(FilePartInfo::getId, uploadId)
                .eq(FilePartInfo::getPartNumber, 0)
                .eq(FilePartInfo::getStatus, 1)
        );
    }

    private InitPartResult check(InitPartRequest request) {
        String uploadId = request.getUploadId();
        FilePartInfo initPart = getInitPart(uploadId);
        if (initPart == null) {
            throw new ApiException(ErrorCode.UPLOAD_ID_NOT_FOUND);
        }

        Long crc32 = request.getFileCrc32();
        String md5 = request.getFileMd5();
        if (StringUtils.isNotBlank(md5)) {
            LambdaQueryWrapper<FileInfo> wrapper = Wrappers.<FileInfo>lambdaQuery();
            if (crc32 != null) {
                wrapper.eq(FileInfo::getCrc32, crc32);
            }
            FileInfo fileInfo = fileInfoMapper.selectOne(wrapper
                    .eq(FileInfo::getMd5, md5).last(" limit 1"));
            if (fileInfo != null) {
                return new InitPartResult(request.getUploadId(), true);
            }
        }

        if (StringUtils.isNotBlank(request.getUploadId())) {
            List<FilePartInfo> partList = filePartInfoMapper.selectList(Wrappers.<FilePartInfo>lambdaQuery()
                    .eq(FilePartInfo::getUploadId, request.getUploadId())
                    .ne(FilePartInfo::getPartNumber, 0)
                    .select(FilePartInfo::getId, FilePartInfo::getPartNumber)
            );

            List<Integer> list = partList.stream()
                    .map(FilePartInfo::getPartNumber)
                    .distinct()
                    .collect(Collectors.toList());
            return new InitPartResult(request.getUploadId(), list);
        }
        return new InitPartResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelMultipart(CancelPartRequest request) {
        FilePartInfo initPart = getInitPart(request.getUploadId());
        if (initPart == null) {
            throw new ApiException(ErrorCode.UPLOAD_ID_NOT_FOUND);
        }
        fastDfs.deleteFile(initPart.getBucketName(), initPart.getObjectName());
        //清空所有分片记录
        List<String> parts = filePartInfoMapper.selectList(Wrappers.<FilePartInfo>lambdaQuery()
                .eq(FilePartInfo::getUploadId, request.getUploadId())
                .select(FilePartInfo::getId)).stream().map(FilePartInfo::getId).collect(Collectors.toList());
        if (!parts.isEmpty()) {
            filePartInfoMapper.deleteBatchIds(parts);
        }
        filePartInfoMapper.deleteById(request.getUploadId());
    }

    @Override
    public void deleteAllFiles() {
        List<FilePartInfo> filePartInfos = filePartInfoMapper.selectList(Wrappers.emptyWrapper());
        List<FileInfo> fileInfos = fileInfoMapper.selectList(Wrappers.emptyWrapper());
        for (FilePartInfo file : filePartInfos) {
            fastDfs.deleteFile(file.getBucketName(), file.getObjectName());
            if (fastDfs.queryFileInfo(file.getBucketName(), file.getObjectName()) != null) {
                throw new ApiException(ErrorCode.SERVER_ERROR, "删除失败");
            }
            filePartInfoMapper.deleteById(file.getId());
        }

        for (FileInfo file : fileInfos) {
            fastDfs.deleteFile(file.getBucketName(), file.getObjectName());
            if (fastDfs.queryFileInfo(file.getBucketName(), file.getObjectName()) != null) {
                throw new ApiException(ErrorCode.SERVER_ERROR, "删除失败");
            }
            fileInfoMapper.deleteById(file.getId());
        }
    }
}
