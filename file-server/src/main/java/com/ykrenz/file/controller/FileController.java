package com.ykrenz.file.controller;

import com.ykrenz.file.model.ResultUtil;
import com.ykrenz.file.model.result.InitUploadMultipartResult;
import com.ykrenz.file.model.request.*;
import com.ykrenz.file.model.result.FileResult;
import com.ykrenz.file.model.result.ListMultipartResult;
import com.ykrenz.file.service.FileService;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * @Description 文件接口
 * @Author ren
 * @Since 1.0
 */
@RestController
@Api(tags = "文件")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @ApiOperation(value = "简单上传", notes = "文件大小10Mb以内")
    @ApiOperationSupport(order = 1)
    @PostMapping("/simpleUpload")
    public ResultUtil<FileResult> upload(@Validated SimpleUploadRequest request) throws IOException {
        return ResultUtil.success(fileService.upload(request));
    }

    @ApiOperation(value = "极速秒传", notes = "匹配hash(具体看服务器是否支持) errno=403不支持 404文件不存在")
    @ApiOperationSupport(order = 2)
    @PostMapping("/fastUpload")
    public ResultUtil<FileResult> upload(@Validated FastUploadRequest request) {
        return ResultUtil.success(fileService.fastUpload(request));
    }

    @ApiOperation("初始化分片上传")
    @ApiOperationSupport(order = 3)
    @PostMapping("/initMultipart")
    public ResultUtil<InitUploadMultipartResult> initMultipart(@Validated InitUploadMultipartRequest request) {
        return ResultUtil.success(fileService.initMultipart(request));
    }

    @ApiOperation(value = "查询已经上传的分片", notes = "可用于续传,跳过已经上传的分片")
    @ApiOperationSupport(order = 4)
    @PostMapping("/listMultipart")
    public ResultUtil<ListMultipartResult> initMultipart(@Validated ListMultipartRequest request) {
        return ResultUtil.success(fileService.listMultipart(request));
    }

    @ApiOperation("上传文件分片")
    @ApiOperationSupport(order = 5)
    @PostMapping("/uploadMultipart")
    public ResultUtil<Object> uploadMultipart(@Validated UploadMultipartRequest uploadMultipartRequest) throws IOException {
        fileService.uploadMultipart(uploadMultipartRequest);
        return ResultUtil.success();
    }

    @ApiOperation("完成分片上传")
    @ApiOperationSupport(order = 6)
    @PostMapping("/completeMultipart")
    public ResultUtil<FileResult> completeMultipart(@Validated CompleteMultipartRequest request) {
        return ResultUtil.success(fileService.completeMultipart(request));
    }

    @ApiOperation("取消分片上传")
    @ApiOperationSupport(order = 7)
    @PostMapping("/cancelMultipart")
    public ResultUtil<Object> cancelMultipart(@Validated CancelMultipartRequest request) {
        fileService.cancelMultipart(request);
        return ResultUtil.success();
    }

    @ApiOperation("查询文件信息")
    @ApiOperationSupport(order = 8)
    @PostMapping("/getFile")
    public ResultUtil<FileResult> getFile(@Validated FileInfoRequest request) {
        return ResultUtil.success(fileService.info(request));
    }
}
