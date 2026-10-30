package com.parkenergyplatform.controller.operations;

import java.util.Map;

import com.parkenergyplatform.aop.OperationLog;
import com.parkenergyplatform.common.ApiResponse;
import com.parkenergyplatform.service.operations.AiSceneDiagnosisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 工程场景数字化：场景融合异常识别、多源证据辅助诊断与运行经济双维复核接口
 */
@RestController
@RequestMapping("/api/platform/ai-diagnosis")
public class AiSceneDiagnosisController {
    private final AiSceneDiagnosisService aiSceneDiagnosisService;

    public AiSceneDiagnosisController(AiSceneDiagnosisService aiSceneDiagnosisService) {
        this.aiSceneDiagnosisService = aiSceneDiagnosisService;
    }

    @GetMapping("/workbench")
    public ApiResponse<Map<String, Object>> workbench() {
        return ApiResponse.success(aiSceneDiagnosisService.workbenchOverview());
    }

    @PostMapping("/evaluate")
    public ApiResponse<Map<String, Object>> evaluate(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(aiSceneDiagnosisService.evaluateCustom(body));
    }

    @PostMapping("/create-work-order")
    @OperationLog(module = "AI场景诊断", operation = "生成证据驱动运维工单")
    public ApiResponse<Map<String, Object>> createWorkOrder(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(aiSceneDiagnosisService.createDiagnosisWorkOrder(body));
    }

    @PostMapping("/llm-proxy")
    public ApiResponse<Map<String, Object>> llmProxy(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(aiSceneDiagnosisService.callExternalLlm(body));
    }

    @PostMapping("/reset-demo-flow")
    @OperationLog(module = "AI场景诊断", operation = "触发场景异常上报并重置演示链路")
    public ApiResponse<Map<String, Object>> resetDemoFlow() {
        return ApiResponse.success(aiSceneDiagnosisService.resetDemoFlow());
    }

    @PostMapping("/parse-protocol-doc")
    @OperationLog(module = "协议中心", operation = "AI解析电表协议文档并对齐标准测点")
    public ApiResponse<Map<String, Object>> parseProtocolDoc(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(aiSceneDiagnosisService.parseProtocolDocument(body));
    }

    @PostMapping("/upload-protocol-pdf")
    @OperationLog(module = "协议中心", operation = "上传电表协议PDF并自动解析")
    public ApiResponse<Map<String, Object>> uploadProtocolPdf(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(aiSceneDiagnosisService.parseUploadedProtocolPdf(file));
    }
}
