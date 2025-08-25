package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    
    @Autowired
    private AgentService agentService;
    
    /**
     * Agent 登入 (POST版本 - 安全版本)
     * POST /api/agent/login
     * Body: {"extension": "1420", "agentId": "12345"}
     */
    @PostMapping("/login")
    public String agentLogin(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        String agentId = request.get("agentId");
        
        if (extension == null || agentId == null) {
            return "❌ 參數錯誤：需要提供 extension 和 agentId";
        }
        
        return agentService.agentLogin(extension, agentId);
    }
    
    /**
     * Agent 登出 (POST版本 - 安全版本)
     * POST /api/agent/logout
     * Body: {"extension": "1420"}
     */
    @PostMapping("/logout")
    public String agentLogout(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
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
     * 設定 Agent 狀態 (POST版本 - 安全版本)
     * POST /api/agent/set-status
     * Body: {"extension": "1420", "status": "AVAILABLE"}
     */
    @PostMapping("/set-status")
    public String setAgentStatus(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        String status = request.get("status");
        
        if (extension == null || status == null) {
            return "❌ 參數錯誤：需要提供 extension 和 status";
        }
        
        return agentService.setAgentStatus(extension, status.toUpperCase());
    }
    
    /**
     * Agent 設為待機 (POST版本 - 安全版本)
     * POST /api/agent/available
     * Body: {"extension": "1420"}
     */
    @PostMapping("/available")
    public String setAgentAvailable(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
        return agentService.setAgentStatus(extension, "AVAILABLE");
    }
    
    /**
     * Agent 設為忙碌 (POST版本 - 安全版本)
     * POST /api/agent/busy
     * Body: {"extension": "1420"}
     */
    @PostMapping("/busy")
    public String setAgentBusy(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
        return agentService.setAgentStatus(extension, "BUSY");
    }
    
    /**
     * Agent 設為休息 (POST版本 - 安全版本)
     * POST /api/agent/break
     * Body: {"extension": "1420"}
     */
    @PostMapping("/break")
    public String setAgentBreak(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
        return agentService.setAgentStatus(extension, "BREAK");
    }
    
    /**
     * 設定 Agent 為手動接聽模式 (POST版本 - 安全版本)
     * POST /api/agent/manual-in
     * Body: {"extension": "1420"}
     */
    @PostMapping("/manual-in")
    public String setAgentManualIn(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
        return agentService.setAgentManualInSimple(extension);
    }
    
    /**
     * 設定 Agent 為自動接聽模式 (POST版本 - 安全版本)
     * POST /api/agent/auto-in
     * Body: {"extension": "1420"}
     */
    @PostMapping("/auto-in")
    public String setAgentAutoIn(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
        return agentService.setAgentAutoInSimple(extension);
    }
    
    /**
     * 設定 Agent 為 AUX 狀態 (POST版本 - 安全版本)
     * POST /api/agent/aux
     * Body: {"extension": "1420"}
     */
    @PostMapping("/aux")
    public String setAgentAux(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
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
     * Manual-in 模式完成通話後調用 (POST版本 - 安全版本)
     * POST /api/agent/call-completed
     * Body: {"extension": "1420"}
     */
    @PostMapping("/call-completed")
    public String callCompleted(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
        return agentService.handleCallCompleted(extension);
    }
    
}