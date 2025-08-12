package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.telephony.*;
import javax.telephony.callcontrol.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentService {
    
    @Autowired
    private PhoneCallService phoneCallService;
    
    /**
     * Agent 狀態類
     */
    public static class AgentStatus {
        public String agentId;
        public String extension;
        public boolean isLoggedIn;
        public long loginTime;
        public String status; // "AVAILABLE", "BUSY", "BREAK", "LOGGED_OUT"
        
        public AgentStatus(String agentId, String extension) {
            this.agentId = agentId;
            this.extension = extension;
            this.isLoggedIn = false;
            this.status = "LOGGED_OUT";
        }
        
        public String getStatusDisplay() {
            if (!isLoggedIn) return "未登入";
            
            switch (status) {
                case "AVAILABLE": return "待機中";
                case "BUSY": return "忙碌中";
                case "BREAK": return "休息中";
                default: return "未知狀態";
            }
        }
        
        public long getLoginDuration() {
            return isLoggedIn ? (System.currentTimeMillis() - loginTime) / 1000 : 0;
        }
    }
    
    // 存儲 Agent 狀態
    private final ConcurrentHashMap<String, AgentStatus> agentStatuses = new ConcurrentHashMap<>();
    
    /**
     * Agent 登入功能 (#94 + AgentID)
     */
    public String agentLogin(String extension, String agentId) {
        try {
            System.out.println("[AGENT] 嘗試登入 Agent: " + agentId + " 在分機: " + extension);
            
            // 檢查分機是否已登入
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：分機 " + extension + " 未登入系統，請先登入 CTI 用戶";
            }
            
            // 檢查 Agent 是否已經在其他分機登入
            AgentStatus existingAgent = findAgentById(agentId);
            if (existingAgent != null && existingAgent.isLoggedIn) {
                return "錯誤：Agent " + agentId + " 已在分機 " + existingAgent.extension + " 登入";
            }
            
            // 檢查分機是否已有其他 Agent 登入
            AgentStatus existingOnExtension = agentStatuses.get(extension);
            if (existingOnExtension != null && existingOnExtension.isLoggedIn) {
                return "錯誤：分機 " + extension + " 已有 Agent " + existingOnExtension.agentId + " 登入";
            }
            
            // 執行 Avaya Agent 登入指令 (#94 + AgentID)
            String loginCommand = "#94" + agentId;
            String result = executeFeatureCode(extension, loginCommand);
            
            if (result.contains("成功") || result.contains("完成")) {
                // 建立或更新 Agent 狀態
                AgentStatus agentStatus = new AgentStatus(agentId, extension);
                agentStatus.isLoggedIn = true;
                agentStatus.loginTime = System.currentTimeMillis();
                agentStatus.status = "AVAILABLE";
                
                agentStatuses.put(extension, agentStatus);
                
                System.out.println("[AGENT] Agent " + agentId + " 登入成功");
                return "Agent " + agentId + " 登入成功\n" +
                       "分機: " + extension + "\n" +
                       "狀態: 待機中\n" +
                       "登入時間: " + new java.util.Date();
            } else {
                return "Agent 登入失敗: " + result;
            }
            
        } catch (Exception e) {
            System.err.println("[AGENT] Agent 登入失敗: " + e.getMessage());
            e.printStackTrace();
            return "Agent 登入失敗: " + e.getMessage();
        }
    }
    
    /**
     * Agent 登出功能 (#95)
     */
    public String agentLogout(String extension) {
        try {
            System.out.println("[AGENT] 嘗試登出分機: " + extension + " 的 Agent");
            
            // 檢查分機是否有 Agent 登入
            AgentStatus agentStatus = agentStatuses.get(extension);
            if (agentStatus == null || !agentStatus.isLoggedIn) {
                return "錯誤：分機 " + extension + " 沒有 Agent 登入";
            }
            
            // 執行 Avaya Agent 登出指令 (#95)
            String logoutCommand = "#95";
            String result = executeFeatureCode(extension, logoutCommand);
            
            if (result.contains("成功") || result.contains("完成")) {
                // 更新 Agent 狀態
                String agentId = agentStatus.agentId;
                long loginDuration = agentStatus.getLoginDuration();
                
                agentStatus.isLoggedIn = false;
                agentStatus.status = "LOGGED_OUT";
                
                System.out.println("[AGENT] Agent " + agentId + " 登出成功");
                return "Agent " + agentId + " 登出成功\n" +
                       "分機: " + extension + "\n" +
                       "登入時長: " + loginDuration + " 秒\n" +
                       "登出時間: " + new java.util.Date();
            } else {
                return "Agent 登出失敗: " + result;
            }
            
        } catch (Exception e) {
            System.err.println("[AGENT] Agent 登出失敗: " + e.getMessage());
            e.printStackTrace();
            return "Agent 登出失敗: " + e.getMessage();
        }
    }
    
    /**
     * 查看 Agent 狀態
     */
    public String getAgentStatus(String extension) {
        AgentStatus agentStatus = agentStatuses.get(extension);
        
        if (agentStatus == null || !agentStatus.isLoggedIn) {
            return "分機 " + extension + " 沒有 Agent 登入";
        }
        
        StringBuilder status = new StringBuilder();
        status.append("=== Agent 狀態 ===\n");
        status.append("Agent ID: ").append(agentStatus.agentId).append("\n");
        status.append("分機: ").append(agentStatus.extension).append("\n");
        status.append("狀態: ").append(agentStatus.getStatusDisplay()).append("\n");
        status.append("登入時間: ").append(new java.util.Date(agentStatus.loginTime)).append("\n");
        status.append("登入時長: ").append(agentStatus.getLoginDuration()).append(" 秒\n");
        
        return status.toString();
    }
    
    /**
     * 查看所有 Agent 狀態
     */
    public String getAllAgentStatus() {
        if (agentStatuses.isEmpty()) {
            return "目前沒有 Agent 登入";
        }
        
        StringBuilder status = new StringBuilder("=== 所有 Agent 狀態 ===\n");
        for (AgentStatus agent : agentStatuses.values()) {
            if (agent.isLoggedIn) {
                status.append("Agent ").append(agent.agentId)
                      .append(" (").append(agent.extension).append(") - ")
                      .append(agent.getStatusDisplay()).append("\n");
            }
        }
        
        return status.toString();
    }
    
    /**
     * 設定 Agent 狀態 (待機/忙碌/休息)
     */
    public String setAgentStatus(String extension, String newStatus) {
        try {
            AgentStatus agentStatus = agentStatuses.get(extension);
            if (agentStatus == null || !agentStatus.isLoggedIn) {
                return "錯誤：分機 " + extension + " 沒有 Agent 登入";
            }
            
            // 驗證狀態
            if (!isValidAgentStatus(newStatus)) {
                return "錯誤：無效的 Agent 狀態: " + newStatus + 
                       "\n有效狀態：AVAILABLE, BUSY, BREAK";
            }
            
            String oldStatus = agentStatus.status;
            agentStatus.status = newStatus;
            
            System.out.println("[AGENT] Agent " + agentStatus.agentId + 
                             " 狀態變更: " + oldStatus + " -> " + newStatus);
            
            return "Agent " + agentStatus.agentId + " 狀態已更新\n" +
                   "從 " + getStatusDisplayName(oldStatus) + 
                   " 變更為 " + getStatusDisplayName(newStatus);
            
        } catch (Exception e) {
            return "設定 Agent 狀態失敗: " + e.getMessage();
        }
    }
    
    // ========================================
    // 輔助方法
    // ========================================
    
    /**
     * 執行功能代碼 (Feature Code)
     */
    private String executeFeatureCode(String extension, String featureCode) {
        try {
            // 取得分機連線
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                throw new Exception("無法取得分機連線");
            }
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            
            // 建立通話來執行功能代碼
            Call featureCall = extensionConn.provider.createCall();
            featureCall.connect(extensionConn.terminal, extensionConn.address, featureCode);
            
            // 等待指令執行
            Thread.sleep(2000);
            
            // 檢查執行結果
            Connection[] connections = featureCall.getConnections();
            boolean success = false;
            
            for (Connection connection : connections) {
                if (connection.getState() == Connection.CONNECTED ||
                    connection.getState() == Connection.DISCONNECTED) {
                    success = true;
                    break;
                }
            }
            
            // 清理通話
            for (Connection connection : connections) {
                if (connection.getState() != Connection.DISCONNECTED) {
                    try {
                        connection.disconnect();
                    } catch (Exception e) {
                        // 忽略清理錯誤
                    }
                }
            }
            
            return success ? "功能代碼執行成功" : "功能代碼執行失敗";
            
        } catch (Exception e) {
            System.err.println("[AGENT] 執行功能代碼失敗: " + e.getMessage());
            return "執行功能代碼失敗: " + e.getMessage();
        }
    }
    
    /**
     * 根據 Agent ID 找到 Agent
     */
    private AgentStatus findAgentById(String agentId) {
        return agentStatuses.values().stream()
            .filter(agent -> agent.agentId.equals(agentId))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * 檢查是否為有效的 Agent 狀態
     */
    private boolean isValidAgentStatus(String status) {
        return status.equals("AVAILABLE") || 
               status.equals("BUSY") || 
               status.equals("BREAK");
    }
    
    /**
     * 取得狀態顯示名稱
     */
    private String getStatusDisplayName(String status) {
        switch (status) {
            case "AVAILABLE": return "待機中";
            case "BUSY": return "忙碌中";
            case "BREAK": return "休息中";
            case "LOGGED_OUT": return "已登出";
            default: return "未知狀態";
        }
    }
}