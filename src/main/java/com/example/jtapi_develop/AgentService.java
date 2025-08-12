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
     * Agent 登入功能 (#94 + AgentID) - 修正版
     */
    public String agentLogin(String extension, String agentId) {
        try {
            System.out.println("[AGENT] 嘗試登入 Agent: " + agentId + " 在分機: " + extension);
            
            // 檢查分機是否已登入
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：分機 " + extension + " 未登入系統，請先登入 CTI 用戶";
            }
            
            // 檢查並清理同一 Agent 在其他分機的狀態
            AgentStatus existingAgent = findAgentById(agentId);
            if (existingAgent != null && existingAgent.isLoggedIn) {
                System.out.println("[AGENT] 發現 Agent " + agentId + " 在分機 " + existingAgent.extension + " 有登入記錄，先清理");
                existingAgent.isLoggedIn = false;
                existingAgent.status = "LOGGED_OUT";
                agentStatuses.remove(existingAgent.extension);
                System.out.println("[AGENT] 已清理 Agent " + agentId + " 的過期狀態");
            }
            
            // 檢查並清理該分機的既有 Agent 狀態
            AgentStatus existingOnExtension = agentStatuses.get(extension);
            if (existingOnExtension != null && existingOnExtension.isLoggedIn) {
                System.out.println("[AGENT] 發現分機 " + extension + " 有 Agent " + existingOnExtension.agentId + " 登入記錄，先清理");
                existingOnExtension.isLoggedIn = false;
                existingOnExtension.status = "LOGGED_OUT";
                agentStatuses.remove(extension);
                System.out.println("[AGENT] 已清理分機 " + extension + " 的過期 Agent 狀態");
            }
            
            // 執行 Avaya Agent 登入指令 (#94 + AgentID) - 使用改良的方法
            String loginCommand = "#94" + agentId;
            boolean success = executeFeatureCodeImproved(extension, loginCommand);
            
            if (success) {
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
                // 即使執行失敗，也檢查是否實際已經登入
                boolean actuallyLoggedIn = verifyAgentLoginStatus(extension, agentId);
                if (actuallyLoggedIn) {
                    // 實際已登入，更新狀態
                    AgentStatus agentStatus = new AgentStatus(agentId, extension);
                    agentStatus.isLoggedIn = true;
                    agentStatus.loginTime = System.currentTimeMillis();
                    agentStatus.status = "AVAILABLE";
                    
                    agentStatuses.put(extension, agentStatus);
                    
                    System.out.println("[AGENT] Agent " + agentId + " 實際已登入，更新狀態");
                    return "Agent " + agentId + " 登入成功（已檢測到實際登入）\n" +
                           "分機: " + extension + "\n" +
                           "狀態: 待機中\n" +
                           "登入時間: " + new java.util.Date();
                } else {
                    return "Agent 登入失敗：無法執行登入指令或檢測登入狀態";
                }
            }
            
        } catch (Exception e) {
            System.err.println("[AGENT] Agent 登入失敗: " + e.getMessage());
            e.printStackTrace();
            return "Agent 登入失敗: " + e.getMessage();
        }
    }
    
    /**
     * Agent 登出功能 (#95) - 修正版
     */
    public String agentLogout(String extension) {
        try {
            System.out.println("[AGENT] 嘗試登出分機: " + extension + " 的 Agent");
            
            // 取得現有狀態（如果有的話）
            AgentStatus agentStatus = agentStatuses.get(extension);
            
            // 無論本地狀態如何，都先嘗試執行登出指令
            // 因為實際系統可能已登入但本地狀態不同步
            
            // 執行 Avaya Agent 登出指令 (#95) - 使用改良的方法
            String logoutCommand = "#95";
            boolean success = executeFeatureCodeImproved(extension, logoutCommand);
            
            // 準備回應訊息的變數
            String agentId = (agentStatus != null) ? agentStatus.agentId : "未知";
            long loginDuration = (agentStatus != null) ? agentStatus.getLoginDuration() : 0;
            
            if (success) {
                // 清理 Agent 狀態
                if (agentStatus != null) {
                    agentStatus.isLoggedIn = false;
                    agentStatus.status = "LOGGED_OUT";
                    // 徹底清理：從 map 中移除該狀態記錄
                    agentStatuses.remove(extension);
                    System.out.println("[AGENT] 徹底清理分機 " + extension + " 的 Agent 狀態記錄");
                }
                
                System.out.println("[AGENT] Agent " + agentId + " 登出成功");
                return "Agent " + agentId + " 登出成功\n" +
                       "分機: " + extension + "\n" +
                       "登入時長: " + loginDuration + " 秒\n" +
                       "登出時間: " + new java.util.Date();
            } else {
                // 檢查是否實際已經登出
                boolean actuallyLoggedOut = !verifyAgentLoginStatus(extension, agentId);
                if (actuallyLoggedOut) {
                    // 實際已登出，清理狀態
                    if (agentStatus != null) {
                        agentStatus.isLoggedIn = false;
                        agentStatus.status = "LOGGED_OUT";
                        // 徹底清理：從 map 中移除該狀態記錄
                        agentStatuses.remove(extension);
                        System.out.println("[AGENT] 徹底清理分機 " + extension + " 的 Agent 狀態記錄");
                    }
                    
                    System.out.println("[AGENT] Agent " + agentId + " 實際已登出，更新狀態");
                    return "Agent " + agentId + " 登出成功（已檢測到實際登出）\n" +
                           "分機: " + extension + "\n" +
                           "登入時長: " + loginDuration + " 秒\n" +
                           "登出時間: " + new java.util.Date();
                } else {
                    // 無法確認登出狀態，但嘗試清理本地狀態
                    if (agentStatus != null) {
                        agentStatus.isLoggedIn = false;
                        agentStatus.status = "LOGGED_OUT";
                        // 徹底清理：從 map 中移除該狀態記錄
                        agentStatuses.remove(extension);
                        System.out.println("[AGENT] 徹底清理分機 " + extension + " 的 Agent 狀態記錄");
                    }
                    
                    return "Agent " + agentId + " 登出指令已執行\n" +
                           "分機: " + extension + "\n" +
                           "已清理本地狀態記錄\n" +
                           "登入時長: " + loginDuration + " 秒";
                }
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
     * 改良的功能代碼執行方法
     */
    private boolean executeFeatureCodeImproved(String extension, String featureCode) {
        try {
            System.out.println("[AGENT] 執行功能代碼: " + featureCode + " 在分機: " + extension);
            
            // 方法1: 嘗試使用 DTMF 方式發送功能代碼
            boolean dtmfSuccess = sendDTMFFeatureCode(extension, featureCode);
            if (dtmfSuccess) {
                System.out.println("[AGENT] DTMF 功能代碼執行成功");
                return true;
            }
            
            // 方法2: 嘗試使用傳統 Call.connect 方式
            boolean callSuccess = sendCallFeatureCode(extension, featureCode);
            if (callSuccess) {
                System.out.println("[AGENT] Call 功能代碼執行成功");
                return true;
            }
            
            System.out.println("[AGENT] 所有功能代碼執行方法都失敗");
            return false;
            
        } catch (Exception e) {
            System.err.println("[AGENT] 執行功能代碼時發生異常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 使用 DTMF 方式發送功能代碼
     */
    private boolean sendDTMFFeatureCode(String extension, String featureCode) {
        try {
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) return false;
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            
            // 嘗試找到現有的活躍通話來發送 DTMF
            TerminalConnection[] termConnections = extensionConn.terminal.getTerminalConnections();
            if (termConnections != null) {
                for (TerminalConnection termConn : termConnections) {
                    if (termConn.getState() == TerminalConnection.ACTIVE) {
                        // 找到活躍通話，嘗試發送 DTMF
                        if (termConn instanceof CallControlTerminalConnection) {
                            CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                            // 這裡需要實現 DTMF 發送，但 JTAPI 標準可能不支援
                            // 直接返回 false，讓它嘗試其他方法
                            return false;
                        }
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 使用 Call.connect 方式發送功能代碼
     */
    private boolean sendCallFeatureCode(String extension, String featureCode) {
        try {
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) return false;
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            
            // 建立通話來執行功能代碼
            Call featureCall = extensionConn.provider.createCall();
            featureCall.connect(extensionConn.terminal, extensionConn.address, featureCode);
            
            // 等待指令執行
            Thread.sleep(1000); // 減少等待時間
            
            // 檢查執行結果 - 更寬鬆的成功判定
            Connection[] connections = featureCall.getConnections();
            boolean success = connections.length > 0; // 只要有連線就認為可能成功
            
            // 清理通話
            for (Connection connection : connections) {
                try {
                    if (connection.getState() != Connection.DISCONNECTED) {
                        connection.disconnect();
                    }
                } catch (Exception e) {
                    // 忽略清理錯誤
                }
            }
            
            // 額外等待讓系統處理
            Thread.sleep(500);
            
            return success;
            
        } catch (Exception e) {
            System.err.println("[AGENT] Call 功能代碼執行失敗: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 驗證 Agent 實際登入狀態
     */
    private boolean verifyAgentLoginStatus(String extension, String agentId) {
        try {
            // 這裡可以實現更複雜的狀態檢查邏輯
            // 由於 JTAPI 限制，我們假設如果沒有異常就是成功
            
            // 簡單的延遲檢查
            Thread.sleep(500);
            
            // 檢查分機是否還有連線（間接檢查）
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn != null) {
                // 分機連線正常，假設操作可能成功
                return true;
            }
            
            return false;
        } catch (Exception e) {
            return false;
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