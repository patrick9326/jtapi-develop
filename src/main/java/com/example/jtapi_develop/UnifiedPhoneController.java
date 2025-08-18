package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;

/**
 * 統一話機控制器 - 模擬IP話機的按鍵操作
 */
@RestController
@RequestMapping("/api/unified-phone")
public class UnifiedPhoneController {
    
    @Autowired
    private UnifiedPhoneService phoneService;
    
    @Autowired
    private PhoneCallService phoneCallService; // 用於兼容舊版API
    
    @Autowired
    private AgentService agentService;
    
    // ========================================
    // 測試和診斷
    // ========================================
    
    /**
     * 測試API - 確認服務是否正常
     * GET /api/unified-phone/test?ext=1420
     */
    @GetMapping("/test")
    public String test(@RequestParam String ext) {
        return "統一話機服務正常運行，分機：" + ext + "，時間：" + new java.util.Date();
    }
    
    // ========================================
    // 基本通話控制（綠色/紅色按鍵）
    // ========================================
    
    /**
     * 接聽鍵 (綠色按鍵)
     * GET /api/unified-phone/answer?ext=1420
     */
    @GetMapping("/answer")
    public String answer(@RequestParam String ext) {
        return phoneService.answerCall(ext);
    }
    
    /**
     * 撥號 (輸入號碼後按撥號鍵)
     * GET /api/unified-phone/dial?ext=1420&number=1424
     */
    @GetMapping("/dial")
    public String dial(@RequestParam String ext, @RequestParam String number) {
        return phoneService.makeCall(ext, number);
    }
    
    /**
     * 掛斷鍵 (紅色按鍵)
     * GET /api/unified-phone/hangup?ext=1420
     */
    @GetMapping("/hangup")
    public String hangup(@RequestParam String ext) {
        return phoneService.hangupCurrentLine(ext);
    }
    
    /**
     * 掛斷指定線路
     * GET /api/unified-phone/hangup-line?ext=1420&lineId=1420_L2
     */
    @GetMapping("/hangup-line")
    public String hangupLine(@RequestParam String ext, @RequestParam String lineId) {
        return phoneService.hangupSpecificLine(ext, lineId);
    }
    
    // ========================================
    // 多線控制（線路管理）
    // ========================================
    
    /**
     * Hold鍵 (保持當前線路)
     * GET /api/unified-phone/hold?ext=1420
     */
    @GetMapping("/hold")
    public String hold(@RequestParam String ext) {
        return phoneService.holdCurrentLine(ext);
    }
    
    /**
     * 線路選擇鍵 (切換到指定線路)
     * GET /api/unified-phone/line?ext=1420&line=1420_L2
     */
    @GetMapping("/line")
    public String selectLine(@RequestParam String ext, @RequestParam String line) {
        return phoneService.switchToLine(ext, line);
    }
    
    /**
     * Flash鍵 (快速切換線路)
     * GET /api/unified-phone/flash?ext=1420
     */
    @GetMapping("/flash")
    public String flash(@RequestParam String ext) {
        return phoneService.flashSwitch(ext);
    }
    
    // ========================================
    // 高級功能（轉接和會議）
    // ========================================
    
    /**
     * 轉接鍵 - 開始轉接
     * GET /api/unified-phone/transfer?ext=1420&target=1425
     */
    @GetMapping("/transfer")
    public String transfer(@RequestParam String ext, @RequestParam String target) {
        return phoneService.startTransfer(ext, target);
    }
    
    /**
     * 轉接鍵 - 完成轉接 (第二次按轉接鍵)
     * GET /api/unified-phone/transfer-complete?ext=1420
     */
    @GetMapping("/transfer-complete")
    public String completeTransfer(@RequestParam String ext) {
        return phoneService.completeTransfer(ext);
    }
    
    /**
     * 會議鍵 (建立三方通話)
     * GET /api/unified-phone/conference?ext=1420
     */
    @GetMapping("/conference")
    public String conference(@RequestParam String ext) {
        return phoneService.startConference(ext);
    }
    
    // ========================================
    // 話機狀態顯示
    // ========================================
    
    /**
     * 話機顯示屏 (查看所有線路狀態)
     * GET /api/unified-phone/display?ext=1420
     */
    @GetMapping("/display")
    public String display(@RequestParam String ext) {
        return phoneService.getPhoneDisplay(ext);
    }
    
    /**
     * 手動導入現有通話 (當自動檢測失敗時使用)
     * GET /api/unified-phone/import-calls?ext=1420
     */
    @GetMapping("/import-calls")
    public String importExistingCalls(@RequestParam String ext) {
        return phoneService.importExistingCalls(ext);
    }
    
    /**
     * 調試：查看原始通話連線信息
     * GET /api/unified-phone/debug-calls?ext=1420
     */
    @GetMapping("/debug-calls")
    public String debugCalls(@RequestParam String ext) {
        return phoneService.debugExistingCalls(ext);
    }
    
    /**
     * 查看所有話機通話狀態總覽
     * GET /api/unified-phone/status-all
     */
    @GetMapping("/status-all")
    public String getAllPhoneStatus() {
        return phoneService.getAllPhoneStatus();
    }
    
    /**
     * 清理重複和無效的線路
     * GET /api/unified-phone/cleanup?ext=1420
     */
    @GetMapping("/cleanup")
    public String cleanup(@RequestParam String ext) {
        return phoneService.cleanupAndRefresh(ext);
    }
    
    // ========================================
    // 便利操作（組合動作）
    // ========================================
    
    /**
     * 接聽並Hold前一通電話 (智能接聽)
     * GET /api/unified-phone/smart-answer?ext=1420
     */
    @GetMapping("/smart-answer")
    public String smartAnswer(@RequestParam String ext) {
        // 先Hold當前通話，再接聽新來電
        String holdResult = phoneService.holdCurrentLine(ext);
        if (holdResult.contains("失敗") || holdResult.contains("沒有活躍線路")) {
            // 如果Hold失敗（可能沒有當前線路），直接接聽
            return phoneService.answerCall(ext);
        }
        String answerResult = phoneService.answerCall(ext);
        return holdResult + " | " + answerResult;
    }
    
    /**
     * 快速撥號並Hold當前通話
     * GET /api/unified-phone/hold-and-dial?ext=1420&number=1425
     */
    @GetMapping("/hold-and-dial")
    public String holdAndDial(@RequestParam String ext, @RequestParam String number) {
        String holdResult = phoneService.holdCurrentLine(ext);
        String dialResult = phoneService.makeCall(ext, number);
        if (holdResult.contains("沒有活躍線路")) {
            return dialResult; // 沒有現有通話，直接顯示撥號結果
        }
        return holdResult + " | " + dialResult;
    }
    
    /**
     * 一鍵會議 (自動使用前兩條線路建立會議)
     * GET /api/unified-phone/quick-conference?ext=1420
     */
    @GetMapping("/quick-conference")
    public String quickConference(@RequestParam String ext) {
        return phoneService.startConference(ext);
    }
    
    /**
     * 快速狀態 (簡化版顯示)
     * GET /api/unified-phone/status?ext=1420
     */
    @GetMapping("/status")
    public String status(@RequestParam String ext) {
        String fullDisplay = phoneService.getPhoneDisplay(ext);
        // 簡化顯示，只顯示重要信息
        String[] lines = fullDisplay.split("\n");
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (line.contains(">>>") || line.contains("===") || line.contains("空閒")) {
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }
    
    // ========================================
    // 舊版API兼容（漸進遷移）
    // ========================================
    
    /**
     * 兼容舊版登入API
     * GET /api/unified-phone/login?extension=ctiuser&password=Avaya123!
     */
    @GetMapping("/login")
    public CompletableFuture<String> login(@RequestParam String extension, @RequestParam String password) {
        // 重定向到原有的登入服務
        return phoneCallService.loginExtension(extension, password);
    }
    
    // ========================================
    // 新增/修改的 API 端點
    // ========================================

    /**
     * 一般 Unhold 功能 (恢復被 Hold 的通話)
     * GET /api/unified-phone/unhold?ext=1420
     */
    @GetMapping("/unhold")
    public String unhold(@RequestParam String ext) {
        return phoneService.unholdCall(ext);
    }

    /**
     * Hold 活躍通話
     * GET /api/unified-phone/hold-active?ext=1420
     */
    @GetMapping("/hold-active")
    public String holdActive(@RequestParam String ext) {
        return phoneService.holdActiveCall(ext);
    }

    /**
     * 智能 Hold/Unhold 切換
     * GET /api/unified-phone/toggle-hold?ext=1420
     */
    @GetMapping("/toggle-hold")
    public String toggleHold(@RequestParam String ext) {
        return phoneService.toggleHoldCall(ext);
    }

    /**
     * 指定線路撥號
     * GET /api/unified-phone/dial-on-line?ext=1420&number=1424&lineId=1420_L3
     */
    @GetMapping("/dial-on-line")
    public String dialOnSpecificLine(@RequestParam String ext, 
                                    @RequestParam String number,
                                    @RequestParam(required = false) String lineId) {
        return phoneService.makeCallOnSpecificLine(ext, number, lineId);
    }

    /**
     * 查看可用線路 (本地狀態)
     * GET /api/unified-phone/available-lines?ext=1420
     */
    @GetMapping("/available-lines")
    public String getAvailableLines(@RequestParam String ext) {
        return phoneService.getAvailableLines(ext);
    }
    
    /**
     * 查看Server端實際可用線路
     * GET /api/unified-phone/server-lines?ext=1420
     */
    @GetMapping("/server-lines")
    public String getServerAvailableLines(@RequestParam String ext) {
        return phoneService.getServerAvailableLines(ext);
    }

    /**
     * 線路選擇撥號（網頁專用）
     * GET /api/unified-phone/select-line-and-dial?ext=1420&number=1424&preferredLine=L3
     */
    @GetMapping("/select-line-and-dial")
    public String selectLineAndDial(@RequestParam String ext,
                                   @RequestParam String number,
                                   @RequestParam(required = false) String preferredLine) {
        // 將前端的線路選擇（L1, L2...）轉換為實際的線路ID
        String lineId = null;
        if (preferredLine != null && !preferredLine.isEmpty()) {
            // L1 -> 1420_L1, L2 -> 1420_L2 等等
            lineId = ext + "_" + preferredLine;
        }
        
        return phoneService.makeCallOnSpecificLine(ext, number, lineId);
    }
    
    // ========================================
    // Agent狀態檢查API
    // ========================================
    
    /**
     * 檢查分機Agent狀態
     * GET /api/unified-phone/check-agent?ext=1420
     */
    @GetMapping("/check-agent")
    public String checkAgent(@RequestParam String ext) {
        return phoneService.checkAgentStatus(ext);
    }
    
    /**
     * 檢查分機是否可接受來電
     * GET /api/unified-phone/check-availability?ext=1420
     */
    @GetMapping("/check-availability")
    public String checkAvailability(@RequestParam String ext) {
        return phoneCallService.checkExtensionAvailability(ext);
    }
    
 
    /**
     * 設定Agent狀態
     * GET /api/unified-phone/set-agent-status?ext=1420&status=BUSY
     */
    @GetMapping("/set-agent-status")
    public String setAgentStatus(@RequestParam String ext, @RequestParam String status) {
        return agentService.setAgentStatus(ext, status);
    }
}