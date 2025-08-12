package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/multiline")
public class MultiLineController {
    
    @Autowired
    private MultiLineService multiLineService;
    
    /**
     * 接聽來電
     * GET /api/multiline/answer?extension=1420
     */
    @GetMapping("/answer")
    public String answerCall(@RequestParam String extension) {
        return multiLineService.answerIncomingCall(extension);
    }
    
    /**
     * 撥打新電話（在有其他通話的情況下）
     * GET /api/multiline/make-call?extension=1420&target=1424
     */
    @GetMapping("/make-call")
    public String makeNewCall(@RequestParam String extension,
                              @RequestParam String target) {
        return multiLineService.makeNewCall(extension, target);
    }
    
    /**
     * Hold 指定的通話
     * GET /api/multiline/hold?extension=1420&lineId=1
     */
    @GetMapping("/hold")
    public String holdCall(@RequestParam String extension,
                          @RequestParam(required = false) String lineId) {
        return multiLineService.holdCall(extension, lineId);
    }
    
    /**
     * 恢復被 Hold 的通話
     * GET /api/multiline/unhold?extension=1420&lineId=1
     */
    @GetMapping("/unhold")
    public String unholdCall(@RequestParam String extension,
                            @RequestParam String lineId) {
        return multiLineService.unholdCall(extension, lineId);
    }
    
    /**
     * 切換到指定的通話線路（會自動 hold 當前活躍的線路）
     * GET /api/multiline/switch?extension=1420&lineId=2
     */
    @GetMapping("/switch")
    public String switchToLine(@RequestParam String extension,
                              @RequestParam String lineId) {
        return multiLineService.switchToLine(extension, lineId);
    }
    
    /**
     * 掛斷指定的通話
     * GET /api/multiline/hangup?extension=1420&lineId=1
     */
    @GetMapping("/hangup")
    public String hangupCall(@RequestParam String extension,
                            @RequestParam(required = false) String lineId) {
        return multiLineService.hangupCall(extension, lineId);
    }
    
    /**
     * 掛斷所有通話
     * GET /api/multiline/hangup-all?extension=1420
     */
    @GetMapping("/hangup-all")
    public String hangupAllCalls(@RequestParam String extension) {
        return multiLineService.hangupAllCalls(extension);
    }
    
    /**
     * 查看分機的所有線路狀態
     * GET /api/multiline/status?extension=1420
     */
    @GetMapping("/status")
    public String getLineStatus(@RequestParam String extension) {
        return multiLineService.getExtensionLineStatus(extension);
    }
    
    /**
     * 查看所有分機的多線狀態
     * GET /api/multiline/status-all
     */
    @GetMapping("/status-all")
    public String getAllStatus() {
        return multiLineService.getAllMultiLineStatus();
    }
    
    /**
     * 自動接聽模式切換
     * GET /api/multiline/auto-answer?extension=1420&enabled=true
     */
    @GetMapping("/auto-answer")
    public String setAutoAnswer(@RequestParam String extension,
                               @RequestParam boolean enabled) {
        return multiLineService.setAutoAnswerMode(extension, enabled);
    }
    
    /**
     * 快速操作：Hold 當前通話並撥打新電話
     * GET /api/multiline/hold-and-call?extension=1420&target=1424
     */
    @GetMapping("/hold-and-call")
    public String holdCurrentAndMakeNewCall(@RequestParam String extension,
                                           @RequestParam String target) {
        return multiLineService.holdCurrentAndMakeNewCall(extension, target);
    }
    
    /**
     * 快速操作：在兩條線路間切換
     * GET /api/multiline/toggle?extension=1420
     */
    @GetMapping("/toggle")
    public String toggleBetweenLines(@RequestParam String extension) {
        return multiLineService.toggleBetweenTwoLines(extension);
    }
    
    /**
     * 線路詳細信息
     * GET /api/multiline/line-details?extension=1420&lineId=1
     */
    @GetMapping("/line-details")
    public String getLineDetails(@RequestParam String extension,
                                @RequestParam String lineId) {
        return multiLineService.getLineDetails(extension, lineId);
    }
    
    /**
     * 清理斷開的線路
     * GET /api/multiline/cleanup?extension=1420
     */
    @GetMapping("/cleanup")
    public String cleanupDisconnectedLines(@RequestParam String extension) {
        return multiLineService.cleanupDisconnectedLines(extension);
    }
    
    /**
     * 測試多線能力
     * GET /api/multiline/test?extension=1420
     */
    @GetMapping("/test")
    public String testMultiLineCapability(@RequestParam String extension) {
        return multiLineService.testMultiLineCapability(extension);
    }
}