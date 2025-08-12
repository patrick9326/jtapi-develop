package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfer")
public class TransferController {
    
    @Autowired
    private TransferService transferService;
    
    /**
     * 一段轉接 (Blind Transfer)
     * GET /api/transfer/blind?extension=1420&target=1424
     */
    @GetMapping("/blind")
    public String blindTransfer(@RequestParam String extension,
                               @RequestParam String target) {
        return transferService.blindTransfer(extension, target);
    }
    
    /**
     * 二段轉接 - 開始諮詢
     * GET /api/transfer/attended/start?extension=1420&target=1424
     */
    @GetMapping("/attended/start")
    public String startAttendedTransfer(@RequestParam String extension,
                                       @RequestParam String target) {
        return transferService.startAttendedTransfer(extension, target);
    }
    /**
 * Lucent V15 系統專用轉接能力檢查
 */
@GetMapping("/lucent-check")
public String checkLucentCapabilities(@RequestParam String extension) {
    return transferService.checkLucentTransferCapabilities(extension);
}
    /**
     * 二段轉接 - 完成轉接
     * GET /api/transfer/attended/complete?sessionId=1420_1638123456789
     */
    @GetMapping("/attended/complete")
    public String completeAttendedTransfer(@RequestParam String sessionId) {
        return transferService.completeAttendedTransfer(sessionId);
    }
    
    /**
     * 二段轉接 - 取消轉接
     * GET /api/transfer/attended/cancel?sessionId=1420_1638123456789
     */
    @GetMapping("/attended/cancel")
    public String cancelAttendedTransfer(@RequestParam String sessionId) {
        return transferService.cancelAttendedTransfer(sessionId);
    }
    
    /**
     * 查看轉接狀態
     * GET /api/transfer/status
     */
    @GetMapping("/status")
    public String getTransferStatus() {
        return transferService.getTransferStatus();
    }
    
    /**
     * 測試轉接能力
     * GET /api/transfer/test?extension=1420
     */
    @GetMapping("/test")
    public String testTransferCapabilities(@RequestParam String extension) {
        return transferService.testTransferCapabilities(extension);
    }
    
    /**
     * 清理過期轉接會話
     * GET /api/transfer/cleanup
     */
    @GetMapping("/cleanup")
    public String cleanupExpiredTransfers() {
        transferService.cleanupExpiredTransfers();
        return "過期轉接會話清理完成";
    }
    
    // ========================================
    // 新增的便利 API（根據分機號操作）
    // ========================================
    
    /**
     * 二段轉接 - 根據分機號完成轉接（新增！）
     * GET /api/transfer/attended/complete-by-extension?extension=1420
     */
    @GetMapping("/attended/complete-by-extension")
    public String completeAttendedTransferByExtension(@RequestParam String extension) {
        return transferService.completeAttendedTransferByExtension(extension);
    }
    
    /**
     * 二段轉接 - 根據分機號取消轉接（新增！）
     * GET /api/transfer/attended/cancel-by-extension?extension=1420
     */
    @GetMapping("/attended/cancel-by-extension")
    public String cancelAttendedTransferByExtension(@RequestParam String extension) {
        return transferService.cancelAttendedTransferByExtension(extension);
    }
    
    /**
     * 查看特定分機的轉接狀態（新增！）
     * GET /api/transfer/status-by-extension?extension=1420
     */
    @GetMapping("/status-by-extension")
    public String getTransferStatusByExtension(@RequestParam String extension) {
        return transferService.getTransferStatusByExtension(extension);
    }
}