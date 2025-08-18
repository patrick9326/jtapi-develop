package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    
    @Autowired
    private AgentService agentService;
    
    /**
     * Agent 登入 (#94 + AgentID)
     * GET /api/agent/login?extension=1420&agentId=12345
     */
    @GetMapping("/login")
    public String agentLogin(@RequestParam String extension,
                           @RequestParam String agentId) {
        return agentService.agentLogin(extension, agentId);
    }
    
    /**
     * Agent 登出 (#95)
     * GET /api/agent/logout?extension=1420
     */
    @GetMapping("/logout")
    public String agentLogout(@RequestParam String extension) {
        return agentService.agentLogout(extension);
    }
    
    /**
     * 查看 Agent 狀態
     * GET /api/agent/status?extension=1420
     */
    @GetMapping("/status")
    public String getAgentStatus(@RequestParam String extension) {
        return agentService.getAgentStatus(extension);
    }
    
    
    /**
     * 設定 Agent 狀態
     * GET /api/agent/set-status?extension=1420&status=AVAILABLE
     */
    @GetMapping("/set-status")
    public String setAgentStatus(@RequestParam String extension,
                               @RequestParam String status) {
        return agentService.setAgentStatus(extension, status.toUpperCase());
    }
    
    /**
     * Agent 設為待機
     * GET /api/agent/available?extension=1420
     */
    @GetMapping("/available")
    public String setAgentAvailable(@RequestParam String extension) {
        return agentService.setAgentStatus(extension, "AVAILABLE");
    }
    
    /**
     * Agent 設為忙碌
     * GET /api/agent/busy?extension=1420
     */
    @GetMapping("/busy")
    public String setAgentBusy(@RequestParam String extension) {
        return agentService.setAgentStatus(extension, "BUSY");
    }
    
    /**
     * Agent 設為休息
     * GET /api/agent/break?extension=1420
     */
    @GetMapping("/break")
    public String setAgentBreak(@RequestParam String extension) {
        return agentService.setAgentStatus(extension, "BREAK");
    }
    
    /**
     * 設定 Agent 為手動接聽模式 (Manual-in #96)
     * GET /api/agent/manual-in?extension=1420
     */
    @GetMapping("/manual-in")
    public String setAgentManualIn(@RequestParam String extension) {
        return agentService.setAgentManualInSimple(extension);
    }
    
    /**
     * 設定 Agent 為自動接聽模式 (Auto-in #92)
     * GET /api/agent/auto-in?extension=1420
     */
    @GetMapping("/auto-in")
    public String setAgentAutoIn(@RequestParam String extension) {
        return agentService.setAgentAutoInSimple(extension);
    }
    
    /**
     * 設定 Agent 為 AUX 狀態 (輔助工作狀態)
     * GET /api/agent/aux?extension=1420
     */
    @GetMapping("/aux")
    public String setAgentAux(@RequestParam String extension) {
        return agentService.setAgentAuxSimple(extension);
    }
    
    /**
     * 診斷 Agent 登入環境
     * GET /api/agent/diagnose?extension=1420
     */
    @GetMapping("/diagnose")
    public String diagnoseAgentEnvironment(@RequestParam String extension) {
        return agentService.diagnoseAgentEnvironment(extension);
    }
    
    /**
     * 查看最近的 Agent 日誌
     * GET /api/agent/logs
     */
    @GetMapping("/logs")
    public String getAgentLogs() {
        return agentService.getRecentLogs();
    }
    
    /**
     * 測試所有可能的 Agent 狀態
     * GET /api/agent/test-states?extension=1420
     */
    @GetMapping("/test-states")
    public String testAgentStates(@RequestParam String extension) {
        return agentService.testAllAgentStates(extension);
    }
    
    /**
     * Manual-in 模式完成通話後調用
     * GET /api/agent/call-completed?extension=1420
     */
    @GetMapping("/call-completed")
    public String callCompleted(@RequestParam String extension) {
        return agentService.handleCallCompleted(extension);
    }
    
}