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
     * 查看所有 Agent 狀態
     * GET /api/agent/status-all
     */
    @GetMapping("/status-all")
    public String getAllAgentStatus() {
        return agentService.getAllAgentStatus();
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
}