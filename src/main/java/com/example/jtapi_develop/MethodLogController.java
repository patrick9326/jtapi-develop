package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/method-log")
public class MethodLogController {
    
    @Autowired
    private MethodLogService methodLogService;
    
    /**
     * 獲取所有記錄（成功+失敗）
     * GET /api/method-log/all
     */
    @GetMapping("/all")
    public String getAllLogs() {
        return methodLogService.getAllLogs();
    }
    
    /**
     * 獲取所有成功記錄
     * GET /api/method-log/success
     */
    @GetMapping("/success")
    public String getSuccessLogs() {
        return methodLogService.getAllSuccessLogs();
    }
    
    /**
     * 獲取所有失敗記錄
     * GET /api/method-log/failure
     */
    @GetMapping("/failure")
    public String getFailureLogs() {
        return methodLogService.getAllFailureLogs();
    }
    
    /**
     * 獲取特定功能的記錄
     * GET /api/method-log/function?name=轉接
     */
    @GetMapping("/function")
    public String getLogsByFunction(@RequestParam String name) {
        return methodLogService.getSuccessLogsByFunction(name);
    }
    
    /**
     * 獲取方法統計
     * GET /api/method-log/statistics
     */
    @GetMapping("/statistics")
    public String getStatistics() {
        return methodLogService.getMethodStatistics();
    }
    
    /**
     * 清除所有記錄
     * GET /api/method-log/clear
     */
    @GetMapping("/clear")
    public String clearLogs() {
        return methodLogService.clearAllLogs();
    }
}