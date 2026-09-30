package com.parkenergyplatform.controller.system;
import com.parkenergyplatform.service.system.RbacService;

import java.util.Map;

import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform/profile")
public class ProfileController {
    private final RbacService rbacService;
    public ProfileController(RbacService rbacService) { this.rbacService = rbacService; }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() { return ApiResponse.success(rbacService.personalSummary()); }

    @PutMapping("/self")
    @OperationLog(module = "个人中心", operation = "修改个人资料")
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> request) { return ApiResponse.success(rbacService.updateCurrentProfile(request)); }

    @PostMapping(value = "/self/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @OperationLog(module = "个人中心", operation = "上传头像")
    public ApiResponse<Map<String, Object>> uploadAvatar(@RequestPart("file") MultipartFile file) { return ApiResponse.success(rbacService.uploadCurrentAvatar(file)); }

    @DeleteMapping("/self/avatar")
    @OperationLog(module = "个人中心", operation = "移除头像")
    public ApiResponse<Map<String, Object>> clearAvatar() { return ApiResponse.success(rbacService.clearCurrentAvatar()); }

    @PostMapping("/self/password")
    @OperationLog(module = "个人中心", operation = "修改个人密码")
    public ApiResponse<Void> changePassword(@RequestBody Map<String, Object> request) { rbacService.changeCurrentPassword(request); return ApiResponse.success(null); }
}
