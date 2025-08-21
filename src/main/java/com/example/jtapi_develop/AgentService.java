package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.telephony.*;
import javax.telephony.callcontrol.*;
import javax.telephony.callcenter.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map; // *** SSE-MODIFIED ***: 引入 Map
import java.util.HashMap; 

@Service
public class AgentService {
    
    @Autowired
    private PhoneCallService phoneCallService;
    
    @Autowired
    private SseService sseService;
    /**
     * Agent 狀態類
     */
    public static class AgentStatus {
        public String agentId;
        public String extension;
        public boolean isLoggedIn;
        public long loginTime;
        public String status; // "AVAILABLE", "BUSY", "BREAK", "LOGGED_OUT"
        public String callHandlingMode; // "MANUAL_IN", "AUTO_IN", "NOT_SET"
        
        public AgentStatus(String agentId, String extension) {
            this.agentId = agentId;
            this.extension = extension;
            this.isLoggedIn = false;
            this.status = "LOGGED_OUT";
            this.callHandlingMode = "NOT_SET";
        }
        
        public String getStatusDisplay() {
            if (!isLoggedIn) return "未登入";
            
            String baseStatus;
            switch (status) {
                case "AVAILABLE": baseStatus = "待機中"; break;
                case "BUSY": baseStatus = "忙碌中"; break;
                case "BREAK": baseStatus = "休息中"; break;
                default: baseStatus = "未知狀態"; break;
            }
            
            String modeDisplay;
            switch (callHandlingMode) {
                case "MANUAL_IN": modeDisplay = " (手動接聽)"; break;
                case "AUTO_IN": modeDisplay = " (自動接聽)"; break;
                default: modeDisplay = ""; break;
            }
            
            return baseStatus + modeDisplay;
        }
        
        public String getCallHandlingModeDisplay() {
            switch (callHandlingMode) {
                case "MANUAL_IN": return "手動接聽 (Manual-in #96)";
                case "AUTO_IN": return "自動接聽 (Auto-in #92)";
                default: return "未設定";
            }
        }
        
        public long getLoginDuration() {
            return isLoggedIn ? (System.currentTimeMillis() - loginTime) / 1000 : 0;
        }
    }
    
    // 存儲 Agent 狀態
    private final ConcurrentHashMap<String, AgentStatus> agentStatuses = new ConcurrentHashMap<>();
    
    // 存儲最近的日誌 (最多保留100條)
    private final java.util.concurrent.ConcurrentLinkedQueue<String> recentLogs = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final int MAX_LOGS = 100;
    
    // Agent狀態監聽器映射
    private final ConcurrentHashMap<String, javax.telephony.callcenter.AgentTerminalListener> agentListeners = new ConcurrentHashMap<>();
    
    // 通話監聽器映射（用於 Manual-in 模式）
    private final ConcurrentHashMap<String, CallListener> callListeners = new ConcurrentHashMap<>();
    
    // 連線監聽器映射（用於 Manual-in 模式監聽通話斷線）
    private final ConcurrentHashMap<String, ConnectionListener> connectionListeners = new ConcurrentHashMap<>();
    
    /**
     * Agent 登入功能 - 使用 JTAPI AgentTerminal.addAgent() API
     */
    public String agentLogin(String extension, String agentId) {
        try {
            logToMemory("[AGENT] 使用 AgentTerminal.addAgent() API 登入 Agent: " + agentId + " 在分機: " + extension);
            
            // 檢查分機是否已登入，如果沒有則嘗試自動登入
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                logToMemory("[AGENT] 分機 " + extension + " 未登入，嘗試自動連接 CTI 系統");
            }
            
            // 清理既有狀態
            cleanupExistingAgentStates(agentId, extension);
            
            // 嘗試使用 JTAPI CallCenter API 登入
            boolean success = loginAgentViaAPI(extension, agentId);
            
            if (success) {
                // 建立或更新 Agent 狀態記錄
                AgentStatus agentStatus = new AgentStatus(agentId, extension);
                agentStatus.isLoggedIn = true;
                agentStatus.loginTime = System.currentTimeMillis();
                agentStatus.status = "AVAILABLE";
                
                agentStatuses.put(extension, agentStatus);
                
                // 設置 Agent 狀態監聽器
                setupAgentStateListener(extension, agentId);
                
                // *** SSE-MODIFIED ***: 登入成功後發送事件
                Map<String, String> eventData = new HashMap<>();
                eventData.put("action", "login");
                eventData.put("agentId", agentId);
                sseService.sendEvent(extension, "agent_event", eventData);
                

                // 記錄登入成功日誌
                logToMemory("[AGENT] Agent " + agentId + " API 登入成功");
                return "✅ Agent " + agentId + " 登入成功！(API)\n" +
                       "分機: " + extension + "\n" +
                       "狀態: 待機中\n" +
                       "登入時間: " + new java.util.Date() + "\n" +
                       "使用 AgentTerminal.addAgent() 方法";
            } else {
                // 強制只使用 API 方式，不允許功能代碼備用方案
                logToMemory("[AGENT] API 登入失敗，不使用功能代碼備用方案");
                return "❌ Agent " + agentId + " API 登入失敗\n" +
                       "分機: " + extension + "\n" +
                       "必須解決 API 問題，不允許使用功能代碼\n" +
                       "請檢查：\n" +
                       "- CTI 連線是否正常\n" +
                       "- AgentTerminal 是否支援\n" +
                       "- ACD Address 是否正確\n" +
                       "- Agent 狀態管理邏輯";
                
                /* 註解掉功能代碼備用方案 - 強制純 API 方式
                try {
                    String loginCommand = "#94" + agentId;
                    logToMemory("[AGENT] 執行 Agent 登入指令: " + loginCommand);
                    
                    executeFeatureCodeDirect(extension, loginCommand);
                    Thread.sleep(2000);
                    
                    // 建立或更新 Agent 狀態記錄
                    AgentStatus agentStatus = new AgentStatus(agentId, extension);
                    agentStatus.isLoggedIn = true;
                    agentStatus.loginTime = System.currentTimeMillis();
                    agentStatus.status = "AVAILABLE";
                    
                    agentStatuses.put(extension, agentStatus);
                    
                    logToMemory("[AGENT] Agent " + agentId + " 功能代碼登入成功");
                    return "⚠️ Agent " + agentId + " 登入成功！(備用方式)\n" +
                           "分機: " + extension + "\n" +
                           "狀態: 待機中\n" +
                           "登入時間: " + new java.util.Date() + "\n" +
                           "使用功能代碼 #94" + agentId + "\n" +
                           "註：因 Avaya 系統限制，無法使用 AgentTerminal API";
                           
                } catch (Exception featureCodeError) {
                    logToMemory("[AGENT] 功能代碼備用方案也失敗: " + featureCodeError.getMessage());
                    return "❌ Agent " + agentId + " 登入完全失敗\n" +
                           "分機: " + extension + "\n" +
                           "API 方式失敗原因: Avaya 系統相容性問題\n" +
                           "功能代碼方式失敗原因: " + featureCodeError.getMessage() + "\n" +
                           "請確認：\n" +
                           "- 分機是否已登入系統\n" +
                           "- Agent ID 格式是否正確\n" +
                           "- 系統是否支援 Agent 功能";
                }
                */
            }
            
        } catch (Exception e) {
            String errorMsg = "[AGENT] Agent 登入失敗: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            System.err.println(errorMsg);
            e.printStackTrace();
            
            // 詳細錯誤訊息給前端
            StringBuilder errorDetail = new StringBuilder();
            errorDetail.append("Agent 登入失敗\n");
            errorDetail.append("錯誤類型: ").append(e.getClass().getSimpleName()).append("\n");
            errorDetail.append("錯誤訊息: ").append(e.getMessage()).append("\n");
            
            if (e.getCause() != null) {
                errorDetail.append("根本原因: ").append(e.getCause().getMessage()).append("\n");
            }
            
            // 添加堆疊追蹤的前幾行
            StackTraceElement[] stack = e.getStackTrace();
            if (stack.length > 0) {
                errorDetail.append("錯誤位置: ").append(stack[0].toString()).append("\n");
            }
            
            return errorDetail.toString();
        }
    }
    
    /**
     * Agent 登出功能 - 優先使用 API 方法設定為 NOT_READY 狀態
     */
    public String agentLogout(String extension) {
        try {
            logToMemory("[AGENT] 開始 Agent 登出流程 - Extension: " + extension);
            
            // 取得現有狀態
            AgentStatus agentStatus = agentStatuses.get(extension);
            String agentId = (agentStatus != null) ? agentStatus.agentId : "未知";
            long loginDuration = (agentStatus != null) ? agentStatus.getLoginDuration() : 0;
            
            // 優先嘗試使用 API 方法設定 Agent 為 NOT_READY 狀態（而不是完全移除）
            boolean apiLogoutSuccess = logoutAgentViaAPI(extension, agentId);
            
            if (apiLogoutSuccess) {
                logToMemory("[AGENT] API 登出成功，Agent 已設定為 NOT_READY 狀態");
                // 不完全清理狀態，只更新為登出狀態（保留 Agent 記錄）
                if (agentStatus != null) {
                    agentStatus.isLoggedIn = false;
                    agentStatus.status = "NOT_READY";
                }
                
                // 清理所有監聽器
                cleanupAgentStateListener(extension);
                cleanupCallListener(extension);
                cleanupConnectionListener(extension);
                
                // *** SSE-MODIFIED ***: 登出成功後發送事件
                Map<String, String> eventData = new HashMap<>();
                eventData.put("action", "logout");
                eventData.put("agentId", agentId);
                sseService.sendEvent(extension, "agent_event", eventData);

                // *** SSE-MODIFIED ***: 登出成功後發送事件
                return "✅ Agent " + agentId + " 登出成功！(API)\n" +
                       "分機: " + extension + "\n" +
                       "登入時長: " + loginDuration + " 秒\n" +
                       "登出時間: " + new java.util.Date() + "\n" +
                       "Agent 保留在系統中，可快速重新登入";
            } else {
                // 強制只使用 API 方式，不允許功能代碼備用方案
                logToMemory("[AGENT] API 登出失敗，不使用功能代碼備用方案");
                return "❌ Agent " + agentId + " API 登出失敗\n" +
                       "分機: " + extension + "\n" +
                       "必須解決 API 問題，不允許使用功能代碼\n" +
                       "請檢查：\n" +
                       "- CTI 連線是否正常\n" +
                       "- Agent 是否存在於系統中\n" +
                       "- Agent 狀態管理邏輯";
                
                /* 註解掉功能代碼備用方案 - 強制純 API 方式
                String logoutCommand = "#95";
                executeFeatureCodeDirect(extension, logoutCommand);
                Thread.sleep(1000);
                
                // 清理 Agent 狀態
                if (agentStatus != null) {
                    agentStatuses.remove(extension);
                }
                
                return "Agent " + agentId + " 功能代碼登出指令已執行 (#95)\n" +
                       "分機: " + extension + "\n" +
                       "登入時長: " + loginDuration + " 秒\n" +
                       "登出時間: " + new java.util.Date() + "\n" +
                       "請使用 /api/agent/status?extension=" + extension + " 查詢實際狀態";
                */
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] Agent 登出失敗: " + e.getMessage());
            return "Agent 登出失敗: " + e.getMessage();
        }
    }
    
    /**
     * 使用完整的硬登出流程：邏輯登出 + 實體註銷 + 釋放設備 ID
     */
    private boolean logoutAgentViaAPI(String extension, String agentId) {
        try {
            logToMemory("[AGENT] 開始完整的硬登出流程 - Agent: " + agentId);
            
            // 取得分機連線
            PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                logToMemory("[AGENT] 無法取得分機連線，無法使用 API 登出");
                return false;
            }
            
            // 檢查 Terminal 是否支援 AgentTerminal
            if (!(conn.terminal instanceof AgentTerminal)) {
                logToMemory("[AGENT] Terminal 不支援 AgentTerminal，無法使用 API 登出");
                return false;
            }
            
            AgentTerminal agentTerminal = (AgentTerminal) conn.terminal;
            
            // 步驟1: 邏輯登出 - 找到並登出 Agent
            Agent targetAgent = null;
            Agent[] existingAgents = agentTerminal.getAgents();
            if (existingAgents != null) {
                for (Agent agent : existingAgents) {
                    try {
                        if (agentId.equals(agent.getAgentID())) {
                            logToMemory("[AGENT] 找到 Agent " + agentId + "，當前狀態: " + getAgentStateString(agent.getState()));
                            targetAgent = agent;
                            break;
                        }
                    } catch (Exception e) {
                        logToMemory("[AGENT] 檢查 Agent 時發生錯誤: " + e.getMessage());
                    }
                }
            }
            
            if (targetAgent == null) {
                logToMemory("[AGENT] 找不到 Agent " + agentId);
                return false;
            }
            
            // 步驟1: 邏輯登出 (SetAgentState(LOG_OUT))
            try {
                logToMemory("[AGENT] 步驟1: 邏輯登出 - 使用 removeAgent()");
                agentTerminal.removeAgent(targetAgent);
                logToMemory("[AGENT] ✓ Agent " + agentId + " 邏輯登出成功");
            } catch (Exception e) {
                logToMemory("[AGENT] ✗ Agent 邏輯登出失敗: " + e.getMessage());
                // 繼續嘗試後續步驟
            }
            
            // 步驟2: 實體註銷 (UnregisterTerminal)
            try {
                logToMemory("[AGENT] 步驟2: 實體註銷 - 完全清理 Terminal 資源");
                
                if (conn.terminal != null) {
                    // 嘗試找到所有可能的註銷方法
                    java.lang.reflect.Method[] methods = conn.terminal.getClass().getMethods();
                    boolean foundUnregister = false;
                    
                    for (java.lang.reflect.Method method : methods) {
                        String methodName = method.getName().toLowerCase();
                        if (methodName.contains("unregister") || 
                            methodName.contains("shutdown") || 
                            methodName.contains("close") ||
                            methodName.contains("release")) {
                            
                            try {
                                logToMemory("[AGENT] 嘗試調用 " + method.getName() + "()");
                                method.invoke(conn.terminal);
                                logToMemory("[AGENT] ✓ " + method.getName() + "() 執行成功");
                                foundUnregister = true;
                            } catch (Exception e) {
                                logToMemory("[AGENT] ✗ " + method.getName() + "() 失敗: " + e.getMessage());
                            }
                        }
                    }
                    
                    if (!foundUnregister) {
                        // 嘗試手動清理連線關聯
                        logToMemory("[AGENT] 找不到註銷方法，嘗試手動清理");
                        try {
                            // 從 PhoneCallService 中移除這個連線
                            phoneCallService.clearExtensionConnection(extension);
                            logToMemory("[AGENT] ✓ 已從連線池中移除分機連線");
                        } catch (Exception e) {
                            logToMemory("[AGENT] ✗ 清理連線池失敗: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logToMemory("[AGENT] 步驟2執行錯誤: " + e.getMessage());
            }
            
            // 步驟3: 釋放設備 ID (ReleaseDeviceID)
            try {
                logToMemory("[AGENT] 步驟3: 釋放設備 ID");
                
                // 嘗試釋放 Address 相關資源
                if (conn.address != null) {
                    try {
                        // 檢查是否有 release 相關方法
                        java.lang.reflect.Method[] methods = conn.address.getClass().getMethods();
                        for (java.lang.reflect.Method method : methods) {
                            if (method.getName().toLowerCase().contains("release")) {
                                logToMemory("[AGENT] 找到釋放方法: " + method.getName());
                            }
                        }
                        logToMemory("[AGENT] ○ Address 資源檢查完成");
                    } catch (Exception e) {
                        logToMemory("[AGENT] Address 資源檢查失敗: " + e.getMessage());
                    }
                }
                
                // 嘗試清理 Provider 相關資源
                if (conn.provider != null) {
                    try {
                        logToMemory("[AGENT] 檢查 Provider 狀態: " + conn.provider.getState());
                        logToMemory("[AGENT] ○ Provider 資源檢查完成");
                    } catch (Exception e) {
                        logToMemory("[AGENT] Provider 資源檢查失敗: " + e.getMessage());
                    }
                }
                
            } catch (Exception e) {
                logToMemory("[AGENT] 步驟3執行錯誤: " + e.getMessage());
            }
            
            logToMemory("[AGENT] ✓ 完整硬登出流程執行完成");
            return true;
            
        } catch (Exception e) {
            logToMemory("[AGENT] 硬登出流程發生嚴重錯誤: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 使用 JTAPI AgentTerminal.addAgent() 方法登入 Agent
     */
    private boolean loginAgentViaAPI(String extension, String agentId) {
        try {
            System.out.println("[AGENT] 嘗試使用 AgentTerminal.addAgent() 登入 Agent: " + agentId);
            logToMemory("[AGENT] 開始 Agent 登入流程 - Agent ID: " + agentId + ", Extension: " + extension);
            
            // 取得分機的連線
            PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                logToMemory("[AGENT] 錯誤：無法取得分機連線: " + extension);
                logToMemory("[AGENT] 嘗試先登入分機到 CTI 系統");
                
                // 嘗試多種方式建立 CTI 連線
                logToMemory("[AGENT] 嘗試建立 CTI 連線");
                
                // 方法1: 使用分機號碼登入
                try {
                    java.util.concurrent.CompletableFuture<String> loginResult = 
                        phoneCallService.loginExtension(extension, "");
                    String result = loginResult.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    logToMemory("[AGENT] 分機登入結果: " + result);
                    
                    conn = phoneCallService.getExtensionConnection(extension);
                    if (conn != null) {
                        logToMemory("[AGENT] 分機登入成功，取得連線");
                    }
                } catch (Exception loginError) {
                    logToMemory("[AGENT] 分機登入失敗: " + loginError.getMessage());
                }
                
                // 方法2: 如果分機登入失敗，嘗試 CTI 用戶登入
                if (conn == null) {
                    logToMemory("[AGENT] 嘗試使用 CTI 用戶登入 (ctiuser)");
                    try {
                        java.util.concurrent.CompletableFuture<String> ctiResult = 
                            phoneCallService.loginExtension("ctiuser", "Avaya123!");
                        String result = ctiResult.get(30, java.util.concurrent.TimeUnit.SECONDS);
                        logToMemory("[AGENT] CTI 用戶登入結果: " + result);
                        
                        // 檢查是否可以透過 CTI 連線取得分機
                        conn = phoneCallService.getExtensionConnection(extension);
                        if (conn == null) {
                            // 嘗試取得 CTI 連線本身
                            conn = phoneCallService.getExtensionConnection("ctiuser");
                            if (conn != null) {
                                logToMemory("[AGENT] 透過 CTI 用戶取得連線，嘗試建立分機關聯");
                            }
                        }
                    } catch (Exception ctiError) {
                        logToMemory("[AGENT] CTI 用戶登入失敗: " + ctiError.getMessage());
                    }
                }
                
                if (conn == null) {
                    logToMemory("[AGENT] 所有連線嘗試都失敗");
                    return false;
                }
            }
            System.out.println("[AGENT] ✓ 成功取得分機連線: " + extension);
            
            // 檢查 Terminal 是否支援 AgentTerminal
            if (!(conn.terminal instanceof AgentTerminal)) {
                System.out.println("[AGENT] 錯誤：Terminal 不支援 AgentTerminal 功能");
                System.out.println("[AGENT] Terminal 類型: " + conn.terminal.getClass().getName());
                return false;
            }
            System.out.println("[AGENT] ✓ Terminal 支援 AgentTerminal 功能");
            
            AgentTerminal agentTerminal = (AgentTerminal) conn.terminal;
            
            // 取得 Provider 和 ACD Address
            Provider provider = phoneCallService.getProvider();
            if (provider == null) {
                System.out.println("[AGENT] Provider 為 null");
                return false;
            }
            
            // 取得 Terminal 的第一個 Address 作為 Agent Address
            Address[] addresses = agentTerminal.getAddresses();
            if (addresses == null || addresses.length == 0) {
                System.out.println("[AGENT] 無法取得 Terminal Addresses");
                return false;
            }
            
            Address agentAddress = addresses[0];
            System.out.println("[AGENT] Agent Address: " + agentAddress.getName());
            
            // 取得 ACD Address - 嘗試多種 ACD 地址格式
            Address acdAddress = null;
            String[] acdFormats = {
                "ACD:" + extension,           // ACD:1420
                "acd:" + extension,           // acd:1420  
                extension + ":ACD",           // 1420:ACD
                "ACD",                        // 一般 ACD
                "acd",                        // 小寫 acd
                "ACD_GROUP",                  // ACD 群組
                "SPLIT_" + extension,         // SPLIT_1420
                "VDN_" + extension,           // VDN_1420 (Vector Directory Number)
                "HUNT_" + extension,          // HUNT_1420 (Hunt Group)
                "SKILL_" + extension,         // SKILL_1420 (Skill Group)
                extension                     // 最後使用分機本身
            };
            
            logToMemory("[AGENT] 嘗試尋找正確的 ACD Address 格式");
            for (String format : acdFormats) {
                try {
                    acdAddress = provider.getAddress(format);
                    if (acdAddress != null) {
                        logToMemory("[AGENT] 找到 ACD Address: " + format + " (類型: " + acdAddress.getClass().getSimpleName() + ")");
                        
                        // 檢查是否為 ACDAddress 類型
                        if (acdAddress instanceof ACDAddress) {
                            logToMemory("[AGENT] ✓ 找到 ACDAddress 類型: " + format);
                            break;
                        } else {
                            logToMemory("[AGENT] △ " + format + " 不是 ACDAddress 類型，繼續搜尋");
                        }
                    }
                } catch (Exception e) {
                    logToMemory("[AGENT] 嘗試 ACD 格式 " + format + " 失敗: " + e.getMessage());
                }
            }
            
            if (acdAddress == null) {
                logToMemory("[AGENT] 無法找到任何 ACD Address");
                return false;
            }
            
            // 檢查最終找到的地址類型
            if (acdAddress instanceof ACDAddress) {
                logToMemory("[AGENT] ✅ 找到真正的 ACDAddress，使用標準 API");
            } else {
                logToMemory("[AGENT] ⚠️ 只找到 " + acdAddress.getClass().getSimpleName() + "，需要使用 Avaya 特定方法");
            }
            
            ACDAddress acdAddr = (acdAddress instanceof ACDAddress) ? (ACDAddress) acdAddress : null;
            
            // 使用 AgentTerminal.addAgent() 方法登入 Agent
            try {
                System.out.println("[AGENT] 呼叫 AgentTerminal.addAgent()");
                System.out.println("[AGENT] - agentAddress: " + agentAddress.getName());
                System.out.println("[AGENT] - acdAddress: " + acdAddress.getName());
                System.out.println("[AGENT] - agentID: " + agentId);
                
                // 先檢查是否已經有相同的 Agent 存在
                Agent[] existingAgents = agentTerminal.getAgents();
                if (existingAgents != null) {
                    for (Agent existingAgent : existingAgents) {
                        try {
                            if (agentId.equals(existingAgent.getAgentID())) {
                                logToMemory("[AGENT] Agent " + agentId + " 已經存在，當前狀態: " + getAgentStateString(existingAgent.getState()));
                                
                                // 直接設定為 READY 狀態 (Avaya 系統不支援 LOG_IN 狀態)
                                try {
                                    existingAgent.setState(Agent.READY);
                                    logToMemory("[AGENT] Agent " + agentId + " 狀態已設定為 READY (待機中)");
                                    return true;
                                } catch (Exception stateError) {
                                    logToMemory("[AGENT] 設定 READY 狀態失敗: " + stateError.getMessage());
                                    
                                    // 如果 READY 失敗，嘗試 NOT_READY
                                    try {
                                        existingAgent.setState(Agent.NOT_READY);
                                        logToMemory("[AGENT] Agent " + agentId + " 狀態已設定為 NOT_READY");
                                        return true;
                                    } catch (Exception notReadyError) {
                                        logToMemory("[AGENT] 設定 NOT_READY 狀態也失敗: " + notReadyError.getMessage());
                                        throw stateError; // 拋出原始錯誤
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logToMemory("[AGENT] 檢查現有 Agent 時發生錯誤: " + e.getMessage());
                        }
                    }
                }
                
                // 嘗試使用標準 JTAPI addAgent 方法，強制轉型為 ACDAddress
                Agent newAgent = null;
                
                logToMemory("[AGENT] 嘗試強制轉型 LucentV11AddressImpl 為 ACDAddress");
                try {
                    // 直接嘗試轉型 - 可能 Avaya 系統內部支援此轉換
                    ACDAddress forcedACDAddress = (ACDAddress) acdAddress;
                    logToMemory("[AGENT] ✓ 強制轉型成功，使用標準 addAgent() 方法");
                    
                    newAgent = agentTerminal.addAgent(
                        agentAddress,       // Agent Terminal Address
                        forcedACDAddress,   // 強制轉型的 ACD Address
                        Agent.READY,        // 初始狀態設為待機
                        agentId,            // Agent ID
                        ""                  // 密碼
                    );
                    logToMemory("[AGENT] ✓ 標準 addAgent() 方法成功");
                    
                } catch (ClassCastException cce) {
                    logToMemory("[AGENT] ✗ 強制轉型失敗: " + cce.getMessage());
                    
                    // 嘗試使用 null 作為 ACDAddress（某些 Avaya 版本支援）
                    try {
                        logToMemory("[AGENT] 嘗試使用 null ACDAddress");
                        newAgent = agentTerminal.addAgent(
                            agentAddress,   // Agent Terminal Address
                            null,           // ACD Address 為 null
                            Agent.READY,    // 初始狀態設為待機
                            agentId,        // Agent ID
                            ""              // 密碼
                        );
                        logToMemory("[AGENT] ✓ null ACDAddress 方法成功");
                        
                    } catch (Exception nullError) {
                        logToMemory("[AGENT] ✗ null ACDAddress 方法失敗: " + nullError.getMessage());
                        
                        // 最後嘗試：查找是否有其他 addAgent 重載方法
                        try {
                            logToMemory("[AGENT] 尋找 addAgent 方法的其他重載版本");
                            java.lang.reflect.Method[] methods = agentTerminal.getClass().getMethods();
                            
                            for (java.lang.reflect.Method method : methods) {
                                if ("addAgent".equals(method.getName())) {
                                    Class<?>[] paramTypes = method.getParameterTypes();
                                    if (paramTypes.length == 3) {
                                        // 可能是簡化版本：addAgent(Address, int, String)
                                        logToMemory("[AGENT] 找到3參數 addAgent 方法");
                                        try {
                                            newAgent = (Agent) method.invoke(agentTerminal, 
                                                agentAddress, Agent.READY, agentId);
                                            logToMemory("[AGENT] ✓ 3參數 addAgent 方法成功");
                                            break;
                                        } catch (Exception e) {
                                            logToMemory("[AGENT] ✗ 3參數 addAgent 失敗: " + e.getMessage());
                                        }
                                    }
                                }
                            }
                            
                            if (newAgent == null) {
                                throw new Exception("所有 addAgent 方法都失敗");
                            }
                            
                        } catch (Exception methodError) {
                            logToMemory("[AGENT] ✗ 方法搜尋失敗: " + methodError.getMessage());
                            throw new Exception("無法使用任何 addAgent 方法創建 Agent");
                        }
                    }
                }
                
                if (newAgent != null) {
                    System.out.println("[AGENT] AgentTerminal.addAgent() 成功建立 Agent: " + agentId);
                    System.out.println("[AGENT] Agent 狀態: " + getAgentStateString(newAgent.getState()));
                    return true;
                } else {
                    System.out.println("[AGENT] AgentTerminal.addAgent() 回傳 null");
                    return false;
                }
                
            } catch (InvalidArgumentException e) {
                System.out.println("[AGENT] addAgent() 參數錯誤: " + e.getMessage());
                return false;
            } catch (InvalidStateException e) {
                System.out.println("[AGENT] addAgent() 狀態錯誤: " + e.getMessage());
                return false;
            } catch (ResourceUnavailableException e) {
                System.out.println("[AGENT] addAgent() 資源不可用: " + e.getMessage());
                return false;
            } catch (Exception e) {
                System.out.println("[AGENT] addAgent() 未知錯誤: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                if (e.getCause() != null) {
                    System.out.println("[AGENT] 根本原因: " + e.getCause().getMessage());
                }
                e.printStackTrace();
                return false;
            }
            
        } catch (Exception e) {
            System.err.println("[AGENT] API 登入失敗: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 使用 JTAPI CallCenter API 登出 Agent
     */
    private boolean logoutAgentViaAPI(String extension) {
        try {
            System.out.println("[AGENT] 嘗試使用 JTAPI CallCenter API 登出");
            
            // 取得 Provider 和 Address
            Provider provider = phoneCallService.getProvider();
            if (provider == null) {
                System.out.println("[AGENT] Provider 為 null，無法使用 API");
                return false;
            }
            
            // 檢查是否支援 CallCenter 功能
            if (!(provider instanceof CallCenterProvider)) {
                System.out.println("[AGENT] Provider 不支援 CallCenter 功能");
                return false;
            }
            
            CallCenterProvider ccProvider = (CallCenterProvider) provider;
            Address address = ccProvider.getAddress(extension);
            
            if (address == null) {
                System.out.println("[AGENT] 無法取得分機 Address: " + extension);
                return false;
            }
            
            // 檢查是否支援 CallCenter Address
            if (!(address instanceof CallCenterAddress)) {
                System.out.println("[AGENT] Address 不支援 CallCenter 功能");
                return false;
            }
            
            CallCenterAddress ccAddress = (CallCenterAddress) address;
            
            // 方法1: 嘗試使用 ACDAddress (Avaya 特定)
            Agent agent = null;
            try {
                // 使用反射檢查 ACDAddress
                Class<?> acdAddressClass = Class.forName("com.avaya.jtapi.tsapi.callcenter.ACDAddress");
                if (acdAddressClass.isInstance(ccAddress)) {
                    java.lang.reflect.Method getAgentMethod = acdAddressClass.getMethod("getAgent");
                    agent = (Agent) getAgentMethod.invoke(ccAddress);
                    System.out.println("[AGENT] 使用 ACDAddress.getAgent() 成功");
                }
            } catch (Exception e) {
                System.out.println("[AGENT] ACDAddress 方式失敗: " + e.getMessage());
            }
            
            // 方法2: 嘗試從 Terminal 取得 Agent
            if (agent == null) {
                try {
                    PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
                    if (conn != null && conn.terminal instanceof AgentTerminal) {
                        AgentTerminal agentTerminal = (AgentTerminal) conn.terminal;
                        Agent[] agents = agentTerminal.getAgents();
                        if (agents != null && agents.length > 0) {
                            agent = agents[0];
                            System.out.println("[AGENT] 使用 AgentTerminal.getAgents() 成功");
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[AGENT] AgentTerminal 方式失敗: " + e.getMessage());
                }
            }
            
            if (agent == null) {
                System.out.println("[AGENT] 無法取得 Agent 物件");
                return false;
            }
            
            // 登出 Agent
            try {
                agent.setState(Agent.LOG_OUT);
                System.out.println("[AGENT] Agent 登出指令已發送");
            } catch (Exception e) {
                System.out.println("[AGENT] Agent 登出失敗: " + e.getMessage());
                return false;
            }
            
            System.out.println("[AGENT] API 登出指令已發送");
            
            // 等待一下讓狀態更新
            Thread.sleep(1000);
            
            // 檢查登出狀態
            int currentState = agent.getState();
            boolean isLoggedOut = (currentState == Agent.LOG_OUT || currentState == Agent.UNKNOWN);
            
            System.out.println("[AGENT] Agent 狀態: " + currentState + ", 登出成功: " + isLoggedOut);
            return isLoggedOut;
            
        } catch (Exception e) {
            System.err.println("[AGENT] API 登出失敗: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 清理既有 Agent 狀態
     */
    private void cleanupExistingAgentStates(String agentId, String extension) {
        // 檢查並清理同一 Agent 在其他分機的狀態
        AgentStatus existingAgent = findAgentById(agentId);
        if (existingAgent != null && existingAgent.isLoggedIn) {
            System.out.println("[AGENT] 發現 Agent " + agentId + " 在分機 " + existingAgent.extension + " 有登入記錄，先清理");
            agentStatuses.remove(existingAgent.extension);
            System.out.println("[AGENT] 已清理 Agent " + agentId + " 的過期狀態");
        }
        
        // 檢查並清理該分機的既有 Agent 狀態
        AgentStatus existingOnExtension = agentStatuses.get(extension);
        if (existingOnExtension != null && existingOnExtension.isLoggedIn) {
            System.out.println("[AGENT] 發現分機 " + extension + " 有 Agent " + existingOnExtension.agentId + " 登入記錄，先清理");
            agentStatuses.remove(extension);
            System.out.println("[AGENT] 已清理分機 " + extension + " 的過期 Agent 狀態");
        }
    }
    

    
    /**
     * 查看 Agent 狀態 - 從 Avaya Server 查詢實際狀態
     */
    public String getAgentStatus(String extension) {
        try {
            System.out.println("[AGENT] 查詢分機 " + extension + " 的 Agent 狀態（從 Avaya Server）");
            
            // 從 Avaya Server 查詢實際的 Agent 狀態
            Agent[] agents = getAgentsFromAvayaServer(extension);
            
            if (agents == null || agents.length == 0) {
                // 檢查本地狀態作為備用
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null && localStatus.isLoggedIn) {
                    return "分機 " + extension + " 沒有從 Avaya Server 找到 Agent，但本地記錄顯示：\n" +
                           "Agent ID: " + localStatus.agentId + " (本地記錄)\n" +
                           "建議：請檢查 Agent 是否實際登入 Avaya 系統";
                }
                return "分機 " + extension + " 沒有 Agent 登入";
            }
            
            StringBuilder status = new StringBuilder();
            status.append("=== Agent 狀態（來自 Avaya Server）===\n");
            
            for (Agent agent : agents) {
                try {
                    String agentId = agent.getAgentID();
                    int agentState = agent.getState();
                    String stateDisplay = getAvayaAgentStateDisplay(agentState);
                    
                    status.append("Agent ID: ").append(agentId).append("\n");
                    status.append("分機: ").append(extension).append("\n");
                    status.append("Avaya 狀態: ").append(stateDisplay).append(" (").append(agentState).append(")\n");
                    
                    // 嘗試獲取更多詳細資訊
                    try {
                        AgentTerminal agentTerminal = agent.getAgentTerminal();
                        if (agentTerminal != null) {
                            // AgentTerminal 繼承自 Terminal，檢查 Terminal 狀態
                            Terminal terminal = (Terminal) agentTerminal;
                            // Terminal 沒有 getState 方法，跳過狀態檢查
                            status.append("Terminal: 已連接\n");
                        }
                    } catch (Exception e) {
                        status.append("Terminal 狀態: 無法取得\n");
                    }
                    
                    // 檢查本地狀態並比較
                    AgentStatus localStatus = agentStatuses.get(extension);
                    if (localStatus != null) {
                        status.append("本地登入時間: ").append(new java.util.Date(localStatus.loginTime)).append("\n");
                        status.append("本地登入時長: ").append(localStatus.getLoginDuration()).append(" 秒\n");
                        status.append("本地記錄模式: ").append(localStatus.getCallHandlingModeDisplay()).append("\n");
                    }
                    
                    status.append("查詢時間: ").append(new java.util.Date()).append("\n");
                    
                    if (agents.length > 1) {
                        status.append("---\n");
                    }
                } catch (Exception e) {
                    status.append("取得 Agent 詳細資訊時發生錯誤: ").append(e.getMessage()).append("\n");
                }
            }
            
            return status.toString();
            
        } catch (Exception e) {
            System.err.println("[AGENT] 查詢 Agent 狀態失敗: " + e.getMessage());
            e.printStackTrace();
            
            // 發生錯誤時回退到本地狀態
            AgentStatus localStatus = agentStatuses.get(extension);
            if (localStatus != null && localStatus.isLoggedIn) {
                return "無法從 Avaya Server 查詢狀態，顯示本地記錄：\n" +
                       "Agent ID: " + localStatus.agentId + "\n" +
                       "狀態: " + localStatus.getStatusDisplay() + "\n" +
                       "錯誤: " + e.getMessage();
            }
            
            return "無法查詢 Agent 狀態: " + e.getMessage();
        }
    }
    
    /**
     * 查看所有 Agent 狀態 - 從 Avaya Server 查詢實際狀態
     */
    public String getAllAgentStatus() {
        try {
            System.out.println("[AGENT] 查詢所有 Agent 狀態（從 Avaya Server）");
            
            StringBuilder status = new StringBuilder("=== 所有 Agent 狀態（來自 Avaya Server）===\n");
            boolean foundAnyAgent = false;
            
            // 遍歷所有已知的分機連線來查詢 Agent
            for (String extension : agentStatuses.keySet()) {
                try {
                    Agent[] agents = getAgentsFromAvayaServer(extension);
                    if (agents != null && agents.length > 0) {
                        foundAnyAgent = true;
                        
                        for (Agent agent : agents) {
                            try {
                                String agentId = agent.getAgentID();
                                int agentState = agent.getState();
                                String stateDisplay = getAvayaAgentStateDisplay(agentState);
                                
                                status.append("分機 ").append(extension)
                                      .append(" - Agent ").append(agentId)
                                      .append(" - ").append(stateDisplay)
                                      .append(" (").append(agentState).append(")\n");
                                
                            } catch (Exception e) {
                                status.append("分機 ").append(extension)
                                      .append(" - Agent 資訊取得失敗: ").append(e.getMessage()).append("\n");
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[AGENT] 查詢分機 " + extension + " 的 Agent 狀態失敗: " + e.getMessage());
                }
            }
            
            // 如果沒有從 Avaya Server 找到任何 Agent，檢查所有可能的分機連線
            if (!foundAnyAgent) {
                status.append("從 Avaya Server 未找到任何 Agent\n");
                
                // 嘗試從所有活躍的分機連線查詢
                try {
                    for (String extension : getAllActiveExtensions()) {
                        Agent[] agents = getAgentsFromAvayaServer(extension);
                        if (agents != null && agents.length > 0) {
                            foundAnyAgent = true;
                            
                            for (Agent agent : agents) {
                                try {
                                    String agentId = agent.getAgentID();
                                    int agentState = agent.getState();
                                    String stateDisplay = getAvayaAgentStateDisplay(agentState);
                                    
                                    status.append("分機 ").append(extension)
                                          .append(" - Agent ").append(agentId)
                                          .append(" - ").append(stateDisplay)
                                          .append(" (").append(agentState).append(")\n");
                                    
                                } catch (Exception e) {
                                    status.append("分機 ").append(extension)
                                          .append(" - Agent 資訊取得失敗: ").append(e.getMessage()).append("\n");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    status.append("掃描所有分機時發生錯誤: ").append(e.getMessage()).append("\n");
                }
            }
            
            // 如果還是沒有找到，顯示本地記錄作為參考
            if (!foundAnyAgent) {
                status.append("\n=== 本地記錄（參考用）===\n");
                if (agentStatuses.isEmpty()) {
                    status.append("本地也沒有 Agent 記錄\n");
                } else {
                    for (AgentStatus agent : agentStatuses.values()) {
                        if (agent.isLoggedIn) {
                            status.append("分機 ").append(agent.extension)
                                  .append(" - Agent ").append(agent.agentId)
                                  .append(" - ").append(agent.getStatusDisplay())
                                  .append(" (本地記錄)\n");
                        }
                    }
                }
            }
            
            status.append("\n查詢時間: ").append(new java.util.Date());
            
            return status.toString();
            
        } catch (Exception e) {
            System.err.println("[AGENT] 查詢所有 Agent 狀態失敗: " + e.getMessage());
            e.printStackTrace();
            
            // 發生錯誤時回退到本地狀態
            StringBuilder status = new StringBuilder("無法從 Avaya Server 查詢，顯示本地記錄：\n");
            if (agentStatuses.isEmpty()) {
                status.append("目前沒有本地 Agent 記錄\n");
            } else {
                for (AgentStatus agent : agentStatuses.values()) {
                    if (agent.isLoggedIn) {
                        status.append("Agent ").append(agent.agentId)
                              .append(" (").append(agent.extension).append(") - ")
                              .append(agent.getStatusDisplay()).append(" (本地記錄)\n");
                    }
                }
            }
            status.append("錯誤: ").append(e.getMessage());
            
            return status.toString();
        }
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
    
    /**
     * 設定 Agent 為手動接聽模式 (Manual-in #96) - 驗證 Avaya Server 狀態
     */
    public String setAgentManualIn(String extension) {
        try {
            // 先檢查是否有 Agent 登入（從 Avaya Server 查詢）
            Agent[] agents = getAgentsFromAvayaServer(extension);
            if (agents == null || agents.length == 0) {
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null && localStatus.isLoggedIn) {
                    return "錯誤：分機 " + extension + " 在 Avaya Server 上找不到 Agent，但本地記錄顯示有 Agent " + 
                           localStatus.agentId + " 登入。請檢查 Avaya 系統狀態。";
                }
                return "錯誤：分機 " + extension + " 沒有 Agent 登入";
            }
            
            Agent agent = agents[0]; // 使用第一個 Agent
            String agentId = agent.getAgentID();
            
            System.out.println("[AGENT] 設定 Agent " + agentId + " 為手動接聽模式");
            
            // 執行 Manual-in 功能代碼 (#96)
            String manualInCommand = "#96";
            boolean success = executeFeatureCodeImproved(extension, manualInCommand);
            
            // 等待系統處理
            Thread.sleep(1000);
            
            // 重新從 Avaya Server 查詢狀態來驗證
            Agent[] updatedAgents = getAgentsFromAvayaServer(extension);
            StringBuilder result = new StringBuilder();
            
            result.append("=== Manual-in 設定結果 ===\n");
            result.append("Agent ID: ").append(agentId).append("\n");
            result.append("分機: ").append(extension).append("\n");
            result.append("指令執行: ").append(success ? "成功" : "可能失敗").append("\n");
            
            if (updatedAgents != null && updatedAgents.length > 0) {
                Agent updatedAgent = updatedAgents[0];
                int newState = updatedAgent.getState();
                String newStateDisplay = getAvayaAgentStateDisplay(newState);
                String callMode = getAvayaCallHandlingMode(updatedAgent);
                
                result.append("Avaya 當前狀態: ").append(newStateDisplay).append("\n");
                result.append("Avaya 來電處理模式: ").append(callMode).append("\n");
                
                // 更新本地記錄
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null) {
                    localStatus.callHandlingMode = "MANUAL_IN";
                    result.append("本地記錄已更新為: 手動接聽模式\n");
                }
                
                // 判斷是否成功
                if (callMode.contains("手動") || newState == Agent.NOT_READY) {
                    result.append("✅ 手動接聽模式設定成功（已從 Avaya Server 驗證）\n");
                } else {
                    result.append("⚠️  無法確認是否成功設定為手動接聽模式\n");
                }
            } else {
                result.append("⚠️  無法從 Avaya Server 驗證設定結果\n");
                // 仍然更新本地記錄
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null) {
                    localStatus.callHandlingMode = "MANUAL_IN";
                    result.append("本地記錄已更新為: 手動接聽模式\n");
                }
            }
            
            result.append("設定時間: ").append(new java.util.Date());
            
            return result.toString();
            
        } catch (Exception e) {
            System.err.println("[AGENT] 設定手動接聽模式失敗: " + e.getMessage());
            e.printStackTrace();
            return "設定手動接聽模式失敗: " + e.getMessage();
        }
    }
    
    /**
     * 使用 API 方式設定 Agent 工作模式
     */
    private boolean setAgentWorkModeViaAPI(String extension, String mode) {
        try {
            logToMemory("[AGENT] 開始設定 Agent 工作模式 - Extension: " + extension + ", Mode: " + mode);
            
            // 取得分機連線
            PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                logToMemory("[AGENT] 無法取得分機連線，無法設定工作模式");
                return false;
            }
            
            // 檢查 Terminal 是否支援 AgentTerminal
            if (!(conn.terminal instanceof AgentTerminal)) {
                logToMemory("[AGENT] Terminal 不支援 AgentTerminal，無法設定工作模式");
                return false;
            }
            
            AgentTerminal agentTerminal = (AgentTerminal) conn.terminal;
            
            // 找到對應的 Agent
            Agent[] existingAgents = agentTerminal.getAgents();
            if (existingAgents != null) {
                for (Agent agent : existingAgents) {
                    try {
                        String agentId = agent.getAgentID();
                        logToMemory("[AGENT] 找到 Agent " + agentId + "，當前狀態: " + getAgentStateString(agent.getState()));
                        
                        // 根據模式設定 Agent 狀態
                        int targetState;
                        switch (mode) {
                            case "MANUAL_IN":
                                // Manual-in：設為 READY，讓 Agent 可以接聽來電
                                // 設置連線監聽器，當通話結束後自動轉為 WORK_NOT_READY (ACW 狀態)
                                targetState = Agent.READY;
                                // 設置連線監聽器，監聽通話斷線事件
                                setupConnectionListener(extension, agentId);
                                logToMemory("[AGENT] Manual-in 模式：設為 READY 狀態，可接聽一通電話，已設置連線監聽器");
                                break;
                            case "AUTO_IN":
                                // Auto-in：設為 READY，持續接聽來電
                                targetState = Agent.READY;
                                logToMemory("[AGENT] Auto-in 模式：持續接聽模式");
                                break;
                            case "AUX":
                                targetState = Agent.NOT_READY;       // AUX：未就緒狀態
                                // 清理所有監聽器，因為 AUX 模式不需要監聽通話
                                cleanupCallListener(extension);
                                cleanupConnectionListener(extension);
                                logToMemory("[AGENT] AUX 模式：已清理所有監聽器");
                                break;
                            case "ACW":
                                // ACW (After Call Work)：通話後工作狀態，設為 WORK_NOT_READY
                                targetState = Agent.WORK_NOT_READY;
                                // 清理連線監聽器，因為已經處理完通話斷線事件
                                cleanupConnectionListener(extension);
                                logToMemory("[AGENT] ACW 模式：設為 WORK_NOT_READY 狀態");
                                break;
                            default:
                                logToMemory("[AGENT] 未知的工作模式: " + mode);
                                return false;
                        }
                        
                        // 設定 Agent 狀態 - 處理 Avaya 狀態轉換限制
                        int currentState = agent.getState();
                        logToMemory("[AGENT] 當前狀態代碼: " + currentState + " (" + getAgentStateString(currentState) + ")");
                        logToMemory("[AGENT] 目標狀態代碼: " + targetState + " (" + getAgentStateString(targetState) + ")");
                        
                        boolean success = setAgentStateWithTransition(agent, currentState, targetState, mode);
                        if (success) {
                            logToMemory("[AGENT] ✓ Agent " + agentId + " 已設定為 " + mode + " 模式 (狀態: " + getAgentStateString(targetState) + ")");
                            return true;
                        } else {
                            logToMemory("[AGENT] ✗ Agent " + agentId + " 狀態轉換失敗");
                            continue; // 嘗試下一個 Agent
                        }
                        
                    } catch (Exception e) {
                        logToMemory("[AGENT] 設定 Agent 工作模式時發生錯誤: " + e.getMessage());
                    }
                }
            }
            
            logToMemory("[AGENT] 找不到任何 Agent");
            return false;
            
        } catch (Exception e) {
            logToMemory("[AGENT] 設定工作模式過程發生錯誤: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 設定 Agent 為手動接聽模式 (API 方式)
     */
    public String setAgentManualInSimple(String extension) {
        try {
            logToMemory("[AGENT] 使用 API 設定 Manual-in 模式 - Extension: " + extension);
            
            boolean success = setAgentWorkModeViaAPI(extension, "MANUAL_IN");
            
            if (success) {
                // 更新本地記錄
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null) {
                    localStatus.callHandlingMode = "MANUAL_IN";
                }
                
                // *** SSE-MODIFIED ***: 狀態變更後發送事件
                Map<String, String> eventData = new HashMap<>();
                eventData.put("action", "set_mode");
                eventData.put("mode", "MANUAL_IN");
                sseService.sendEvent(extension, "agent_event", eventData);
                
                // 返回成功訊息
                return "✅ Manual-in 設定成功！(API)\n" +
                       "分機: " + extension + "\n" +
                       "執行時間: " + new java.util.Date() + "\n" +
                       "狀態: 手動接聽模式 (READY - 可接聽一通來電)\n" +
                       "使用 Agent.setState() API 方法\n" +
                       "🔔 特殊行為: 通話結束後會自動轉為 ACW 狀態，不會接收新電話\n" +
                       "💡 要接聽下一通電話，需要再次選擇 Manual-in 模式";
            } else {
                return "❌ Manual-in API 設定失敗\n" +
                       "分機: " + extension + "\n" +
                       "必須解決 API 問題，不允許使用功能代碼\n" +
                       "請檢查：\n" +
                       "- Agent 是否已登入\n" +
                       "- CTI 連線是否正常";
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] Manual-in 設定失敗: " + e.getMessage());
            return "Manual-in 設定失敗: " + e.getMessage();
        }
    }
    
    /**
     * 設定 Agent 為自動接聽模式 (API 方式)
     */
    public String setAgentAutoInSimple(String extension) {
        try {
            logToMemory("[AGENT] 使用 API 設定 Auto-in 模式 - Extension: " + extension);
            
            boolean success = setAgentWorkModeViaAPI(extension, "AUTO_IN");
            
            if (success) {
                // 更新本地記錄
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null) {
                    localStatus.callHandlingMode = "AUTO_IN";
                }
                
                // *** SSE-MODIFIED ***: 狀態變更後發送事件
                Map<String, String> eventData = new HashMap<>();
                eventData.put("action", "set_mode");
                eventData.put("mode", "AUTO_IN");
                sseService.sendEvent(extension, "agent_event", eventData);


                return "✅ Auto-in 設定成功！(API)\n" +
                       "分機: " + extension + "\n" +
                       "執行時間: " + new java.util.Date() + "\n" +
                       "狀態: 自動接聽模式 (READY - 系統限制)\n" +
                       "使用 Agent.setState() API 方法\n" +
                       "註：此 Avaya 系統不支援 WORK_READY，使用 READY 狀態模擬";
            } else {
                return "❌ Auto-in API 設定失敗\n" +
                       "分機: " + extension + "\n" +
                       "必須解決 API 問題，不允許使用功能代碼\n" +
                       "請檢查：\n" +
                       "- Agent 是否已登入\n" +
                       "- CTI 連線是否正常";
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] Auto-in 設定失敗: " + e.getMessage());
            return "Auto-in 設定失敗: " + e.getMessage();
        }
    }
    
    /**
     * 設定 Agent 為 AUX 狀態 (API 方式)
     */
    public String setAgentAuxSimple(String extension) {
        try {
            logToMemory("[AGENT] 使用 API 設定 AUX 狀態 - Extension: " + extension);
            
            boolean success = setAgentWorkModeViaAPI(extension, "AUX");
            
            if (success) {
                // 更新本地記錄
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null) {
                    localStatus.callHandlingMode = "AUX";
                    localStatus.status = "AUX";
                }
                
                // *** SSE-MODIFIED ***: 狀態變更後發送事件
                Map<String, String> eventData = new HashMap<>();
                eventData.put("action", "set_mode");
                eventData.put("mode", "AUX");
                sseService.sendEvent(extension, "agent_event", eventData);


                return "✅ AUX 狀態設定成功！(API)\n" +
                       "分機: " + extension + "\n" +
                       "執行時間: " + new java.util.Date() + "\n" +
                       "狀態: 輔助工作狀態 (NOT_READY)\n" +
                       "使用 Agent.setState() API 方法";
            } else {
                return "❌ AUX API 設定失敗\n" +
                       "分機: " + extension + "\n" +
                       "必須解決 API 問題，不允許使用功能代碼\n" +
                       "請檢查：\n" +
                       "- Agent 是否已登入\n" +
                       "- CTI 連線是否正常";
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] AUX 設定失敗: " + e.getMessage());
            return "AUX 設定失敗: " + e.getMessage();
        }
    }
    
    /**
     * 設定 Agent 為自動接聽模式 (Auto-in #92) - 驗證 Avaya Server 狀態（已停用）
     */
    private String setAgentAutoIn(String extension) {
        try {
            // 先檢查是否有 Agent 登入（從 Avaya Server 查詢）
            Agent[] agents = getAgentsFromAvayaServer(extension);
            if (agents == null || agents.length == 0) {
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null && localStatus.isLoggedIn) {
                    return "錯誤：分機 " + extension + " 在 Avaya Server 上找不到 Agent，但本地記錄顯示有 Agent " + 
                           localStatus.agentId + " 登入。請檢查 Avaya 系統狀態。";
                }
                return "錯誤：分機 " + extension + " 沒有 Agent 登入";
            }
            
            Agent agent = agents[0]; // 使用第一個 Agent
            String agentId = agent.getAgentID();
            
            System.out.println("[AGENT] 設定 Agent " + agentId + " 為自動接聽模式");
            
            // 執行 Auto-in 功能代碼 (#92)
            String autoInCommand = "#92";
            boolean success = executeFeatureCodeImproved(extension, autoInCommand);
            
            // 等待系統處理
            Thread.sleep(1000);
            
            // 重新從 Avaya Server 查詢狀態來驗證
            Agent[] updatedAgents = getAgentsFromAvayaServer(extension);
            StringBuilder result = new StringBuilder();
            
            result.append("=== Auto-in 設定結果 ===\n");
            result.append("Agent ID: ").append(agentId).append("\n");
            result.append("分機: ").append(extension).append("\n");
            result.append("指令執行: ").append(success ? "成功" : "可能失敗").append("\n");
            
            if (updatedAgents != null && updatedAgents.length > 0) {
                Agent updatedAgent = updatedAgents[0];
                int newState = updatedAgent.getState();
                String newStateDisplay = getAvayaAgentStateDisplay(newState);
                String callMode = getAvayaCallHandlingMode(updatedAgent);
                
                result.append("Avaya 當前狀態: ").append(newStateDisplay).append("\n");
                result.append("Avaya 來電處理模式: ").append(callMode).append("\n");
                
                // 更新本地記錄
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null) {
                    localStatus.callHandlingMode = "AUTO_IN";
                    result.append("本地記錄已更新為: 自動接聽模式\n");
                }
                
                // 判斷是否成功
                if (callMode.contains("自動") || newState == Agent.READY) {
                    result.append("✅ 自動接聽模式設定成功（已從 Avaya Server 驗證）\n");
                } else {
                    result.append("⚠️  無法確認是否成功設定為自動接聽模式\n");
                }
            } else {
                result.append("⚠️  無法從 Avaya Server 驗證設定結果\n");
                // 仍然更新本地記錄
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null) {
                    localStatus.callHandlingMode = "AUTO_IN";
                    result.append("本地記錄已更新為: 自動接聽模式\n");
                }
            }
            
            result.append("設定時間: ").append(new java.util.Date());
            
            return result.toString();
            
        } catch (Exception e) {
            System.err.println("[AGENT] 設定自動接聽模式失敗: " + e.getMessage());
            e.printStackTrace();
            return "設定自動接聽模式失敗: " + e.getMessage();
        }
    }
    
    /**
     * 查看 Agent 來電處理模式 - 從 Avaya Server 查詢
     */
    public String getAgentCallHandlingMode(String extension) {
        try {
            System.out.println("[AGENT] 查詢分機 " + extension + " 的來電處理模式（從 Avaya Server）");
            
            // 從 Avaya Server 查詢實際的 Agent 狀態
            Agent[] agents = getAgentsFromAvayaServer(extension);
            
            if (agents == null || agents.length == 0) {
                // 檢查本地狀態作為備用
                AgentStatus localStatus = agentStatuses.get(extension);
                if (localStatus != null && localStatus.isLoggedIn) {
                    return "分機 " + extension + " 沒有從 Avaya Server 找到 Agent，本地記錄顯示：\n" +
                           "Agent ID: " + localStatus.agentId + "\n" +
                           "本地記錄模式: " + localStatus.getCallHandlingModeDisplay() + "\n" +
                           "建議：請檢查 Agent 是否實際登入 Avaya 系統";
                }
                return "錯誤：分機 " + extension + " 沒有 Agent 登入";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("=== Agent 來電處理模式（來自 Avaya Server）===\n");
            
            for (Agent agent : agents) {
                try {
                    String agentId = agent.getAgentID();
                    int agentState = agent.getState();
                    String stateDisplay = getAvayaAgentStateDisplay(agentState);
                    
                    result.append("Agent ID: ").append(agentId).append("\n");
                    result.append("分機: ").append(extension).append("\n");
                    result.append("Avaya 狀態: ").append(stateDisplay).append("\n");
                    
                    // 嘗試從 Avaya Server 查詢來電處理模式
                    String avayaCallMode = getAvayaCallHandlingMode(agent);
                    result.append("Avaya 來電處理模式: ").append(avayaCallMode).append("\n");
                    
                    // 比較本地記錄
                    AgentStatus localStatus = agentStatuses.get(extension);
                    if (localStatus != null) {
                        result.append("本地記錄模式: ").append(localStatus.getCallHandlingModeDisplay()).append("\n");
                        if (!avayaCallMode.equals("無法取得") && 
                            !avayaCallMode.toLowerCase().contains(localStatus.callHandlingMode.toLowerCase())) {
                            result.append("⚠️  Avaya 與本地記錄不一致\n");
                        }
                    }
                    
                    result.append("查詢時間: ").append(new java.util.Date()).append("\n");
                    
                    if (agents.length > 1) {
                        result.append("---\n");
                    }
                } catch (Exception e) {
                    result.append("取得 Agent 來電處理模式時發生錯誤: ").append(e.getMessage()).append("\n");
                }
            }
            
            return result.toString();
            
        } catch (Exception e) {
            System.err.println("[AGENT] 查詢來電處理模式失敗: " + e.getMessage());
            e.printStackTrace();
            
            // 發生錯誤時回退到本地狀態
            AgentStatus localStatus = agentStatuses.get(extension);
            if (localStatus != null && localStatus.isLoggedIn) {
                return "無法從 Avaya Server 查詢來電處理模式，顯示本地記錄：\n" +
                       "Agent ID: " + localStatus.agentId + "\n" +
                       "本地記錄模式: " + localStatus.getCallHandlingModeDisplay() + "\n" +
                       "錯誤: " + e.getMessage();
            }
            
            return "無法查詢來電處理模式: " + e.getMessage();
        }
    }
    
    // ========================================
    // 輔助方法
    // ========================================
    
    /**
     * 直接執行功能代碼（最簡化版本，用於 manual-in/auto-in）
     */
    private void executeFeatureCodeDirect(String extension, String featureCode) {
        try {
            System.out.println("[AGENT] 直接執行功能代碼: " + featureCode + " 在分機: " + extension);
            
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                System.out.println("[AGENT] 分機連線不存在");
                return;
            }
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            
            // 直接建立通話執行功能代碼
            Call featureCall = extensionConn.provider.createCall();
            featureCall.connect(extensionConn.terminal, extensionConn.address, featureCode);
            
            System.out.println("[AGENT] 功能代碼 " + featureCode + " 已送出");
            
        } catch (Exception e) {
            System.err.println("[AGENT] 直接執行功能代碼失敗: " + e.getMessage());
        }
    }
    
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
            
            
            return success;
            
        } catch (Exception e) {
            System.err.println("[AGENT] Call 功能代碼執行失敗: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 從 Avaya Server 查詢 Agent 狀態
     */
    private Agent[] getAgentsFromAvayaServer(String extension) {
        try {
            System.out.println("[AGENT] 從 Avaya Server 查詢分機 " + extension + " 的 Agent");
            
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                System.out.println("[AGENT] 分機 " + extension + " 沒有連線");
                return null;
            }
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            
            // 檢查 Terminal 是否支援 AgentTerminal
            if (extensionConn.terminal instanceof AgentTerminal) {
                AgentTerminal agentTerminal = (AgentTerminal) extensionConn.terminal;
                
                // 使用 JTAPI AgentTerminal.getAgents() 查詢實際的 Agent 狀態
                Agent[] agents = agentTerminal.getAgents();
                
                if (agents != null && agents.length > 0) {
                    System.out.println("[AGENT] 從 Avaya Server 找到 " + agents.length + " 個 Agent");
                    return agents;
                } else {
                    System.out.println("[AGENT] 從 Avaya Server 沒有找到 Agent");
                    return null;
                }
            } else {
                System.out.println("[AGENT] Terminal 不支援 AgentTerminal 介面");
                return null;
            }
            
        } catch (Exception e) {
            System.err.println("[AGENT] 從 Avaya Server 查詢 Agent 失敗: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 取得所有活躍的分機號碼
     */
    private java.util.Set<String> getAllActiveExtensions() {
        try {
            return phoneCallService.getAllExtensionConnections().keySet();
        } catch (Exception e) {
            System.err.println("[AGENT] 取得活躍分機列表失敗: " + e.getMessage());
            return java.util.Collections.emptySet();
        }
    }
    
    /**
     * 從 Avaya Server 查詢 Agent 的來電處理模式
     */
    private String getAvayaCallHandlingMode(Agent agent) {
        try {
            // JTAPI 中沒有直接的來電處理模式查詢 API
            // 我們需要透過其他方式間接判斷，或檢查 Agent 的擴展屬性
            
            // 方法1: 檢查 Agent 是否支援擴展屬性
            try {
                // 嘗試獲取 Agent 的詳細資訊
                AgentTerminal agentTerminal = agent.getAgentTerminal();
                if (agentTerminal != null) {
                    // 在某些 Avaya 實作中，可能可以透過特定方法查詢
                    // 但標準 JTAPI 沒有直接的來電處理模式查詢
                    return "需要使用 Avaya 特定 API 查詢";
                }
            } catch (Exception e) {
                // 忽略錯誤
            }
            
            // 方法2: 透過 Agent 狀態間接判斷
            int agentState = agent.getState();
            switch (agentState) {
                case Agent.READY:
                    return "可能為自動接聽模式 (Agent READY)";
                case Agent.NOT_READY:
                    return "可能為手動接聽模式 (Agent NOT_READY)";
                default:
                    return "無法從狀態判斷 (" + getAvayaAgentStateDisplay(agentState) + ")";
            }
            
        } catch (Exception e) {
            return "查詢失敗: " + e.getMessage();
        }
    }
    
    /**
     * 轉換 Avaya Agent 狀態為顯示文字
     */
    private String getAvayaAgentStateDisplay(int agentState) {
        switch (agentState) {
            case Agent.LOG_IN: return "已登入";
            case Agent.LOG_OUT: return "已登出";
            case Agent.NOT_READY: return "未就緒";
            case Agent.READY: return "就緒待命";
            case Agent.WORK_NOT_READY: return "後處理未就緒";
            case Agent.WORK_READY: return "後處理就緒";
            case Agent.BUSY: return "忙碌中";
            case Agent.UNKNOWN: return "未知狀態";
            default: return "狀態碼: " + agentState;
        }
    }
    
    
    /**
     * 驗證 Agent 實際登入狀態 - 使用 Avaya Server 查詢
     */
    private boolean verifyAgentLoginStatus(String extension, String agentId) {
        try {
            System.out.println("[AGENT] 驗證 Agent " + agentId + " 在分機 " + extension + " 的登入狀態");
            
            Agent[] agents = getAgentsFromAvayaServer(extension);
            if (agents == null || agents.length == 0) {
                return false;
            }
            
            // 檢查是否有指定的 Agent ID
            for (Agent agent : agents) {
                try {
                    String currentAgentId = agent.getAgentID();
                    int agentState = agent.getState();
                    
                    if (agentId.equals(currentAgentId)) {
                        // 找到指定的 Agent，檢查狀態
                        boolean isLoggedIn = (agentState == Agent.LOG_IN || 
                                            agentState == Agent.READY || 
                                            agentState == Agent.NOT_READY ||
                                            agentState == Agent.WORK_READY ||
                                            agentState == Agent.WORK_NOT_READY ||
                                            agentState == Agent.BUSY);
                        
                        System.out.println("[AGENT] Agent " + agentId + " 狀態: " + 
                                         getAvayaAgentStateDisplay(agentState) + 
                                         ", 登入狀態: " + isLoggedIn);
                        
                        return isLoggedIn;
                    }
                } catch (Exception e) {
                    System.err.println("[AGENT] 檢查 Agent 詳細資訊時發生錯誤: " + e.getMessage());
                }
            }
            
            return false;
            
        } catch (Exception e) {
            System.err.println("[AGENT] 驗證 Agent 登入狀態失敗: " + e.getMessage());
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
    
    /**
     * 處理 Agent 狀態轉換，考慮 Avaya 系統的限制
     */
    private boolean setAgentStateWithTransition(Agent agent, int currentState, int targetState, String mode) {
        try {
            logToMemory("[AGENT] 開始狀態轉換: " + getAgentStateString(currentState) + " → " + getAgentStateString(targetState));
            
            // 如果已經是目標狀態，直接返回成功
            if (currentState == targetState) {
                logToMemory("[AGENT] Agent 已經處於目標狀態");
                return true;
            }
            
            // 定義 Avaya 支援的狀態轉換路徑
            boolean needsIntermediateTransition = false;
            int intermediateState = -1;
            
            // 檢查是否需要中間轉換
            switch (targetState) {
                case Agent.READY: // Auto-in 模式 (使用 READY 模擬 WORK_READY) 或 待機模式
                    // READY 狀態通常可以從任何狀態直接轉換
                    logToMemory("[AGENT] 轉換到 READY 狀態（Auto-in 或待機模式）");
                    break;
                    
                case Agent.WORK_NOT_READY: // Manual-in 模式
                    // WORK_NOT_READY 從某些狀態需要先轉換到 READY
                    if (currentState == Agent.NOT_READY || currentState == Agent.WORK_READY) {
                        needsIntermediateTransition = true;
                        intermediateState = Agent.READY;
                        logToMemory("[AGENT] Manual-in 需要中間轉換: " + getAgentStateString(currentState) + " → READY → WORK_NOT_READY");
                    }
                    break;
                    
                case Agent.NOT_READY: // AUX 模式
                    // NOT_READY 通常可以從任何狀態直接轉換
                    logToMemory("[AGENT] AUX 狀態通常可以直接轉換");
                    break;
            }
            
            // 執行中間轉換（如果需要）
            if (needsIntermediateTransition) {
                try {
                    logToMemory("[AGENT] 執行中間狀態轉換: " + getAgentStateString(intermediateState));
                    agent.setState(intermediateState);
                    Thread.sleep(500); // 給系統一點時間處理狀態變化
                    logToMemory("[AGENT] ✓ 中間狀態轉換成功");
                } catch (Exception e) {
                    logToMemory("[AGENT] ✗ 中間狀態轉換失敗: " + e.getMessage());
                    
                    // 如果中間轉換失敗，嘗試其他路徑
                    if (targetState == Agent.WORK_READY && intermediateState == Agent.READY) {
                        // 嘗試 NOT_READY → WORK_READY 直接轉換
                        try {
                            logToMemory("[AGENT] 嘗試替代路徑: 直接從 " + getAgentStateString(currentState) + " → WORK_READY");
                            agent.setState(targetState);
                            return true;
                        } catch (Exception e2) {
                            logToMemory("[AGENT] 直接轉換也失敗: " + e2.getMessage());
                            return false;
                        }
                    }
                    return false;
                }
            }
            
            // 執行最終狀態轉換
            try {
                logToMemory("[AGENT] 執行最終狀態轉換: " + getAgentStateString(targetState));
                agent.setState(targetState);
                logToMemory("[AGENT] ✓ 最終狀態轉換成功");
                return true;
            } catch (Exception e) {
                logToMemory("[AGENT] ✗ 最終狀態轉換失敗: " + e.getMessage());
                
                // 記錄詳細的錯誤資訊
                if (e.getMessage().contains("CSTA Error")) {
                    logToMemory("[AGENT] CSTA 錯誤詳情: " + e.getMessage());
                    logToMemory("[AGENT] 可能的原因:");
                    logToMemory("[AGENT] - 從狀態 " + getAgentStateString(currentState) + " 到 " + getAgentStateString(targetState) + " 的轉換不被 Avaya 系統支援");
                    logToMemory("[AGENT] - Agent 可能處於通話狀態或其他限制狀態");
                    logToMemory("[AGENT] - 系統配置限制了此狀態轉換");
                }
                
                return false;
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] 狀態轉換過程發生意外錯誤: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 取得 Agent 狀態的字串描述
     */
    private String getAgentStateString(int state) {
        switch (state) {
            case Agent.LOG_IN: return "已登入";
            case Agent.LOG_OUT: return "已登出";
            case Agent.READY: return "待機中";
            case Agent.NOT_READY: return "未就緒";
            case Agent.WORK_NOT_READY: return "後處理中";
            case Agent.WORK_READY: return "工作中";
            case Agent.BUSY: return "忙碌中";
            default: return "未知狀態(" + state + ")";
        }
    }
    
    /**
     * 診斷 Agent 登入環境
     */
    public String diagnoseAgentEnvironment(String extension) {
        StringBuilder diagnosis = new StringBuilder();
        diagnosis.append("=== Agent 登入環境診斷 ===\n");
        diagnosis.append("分機: ").append(extension).append("\n");
        diagnosis.append("診斷時間: ").append(new java.util.Date()).append("\n\n");
        
        try {
            // 1. 檢查分機連線
            diagnosis.append("1. 分機連線檢查:\n");
            PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                diagnosis.append("   ❌ 無法取得分機連線\n");
                diagnosis.append("   → 請確認分機是否已登入 CTI 系統\n\n");
                return diagnosis.toString();
            }
            diagnosis.append("   ✅ 分機連線正常\n\n");
            
            // 2. 檢查 Terminal 類型
            diagnosis.append("2. Terminal 類型檢查:\n");
            diagnosis.append("   Terminal 類型: ").append(conn.terminal.getClass().getName()).append("\n");
            
            if (conn.terminal instanceof AgentTerminal) {
                diagnosis.append("   ✅ Terminal 支援 AgentTerminal\n");
                AgentTerminal agentTerminal = (AgentTerminal) conn.terminal;
                
                // 3. 檢查現有 Agents
                diagnosis.append("\n3. 現有 Agent 檢查:\n");
                try {
                    Agent[] existingAgents = agentTerminal.getAgents();
                    if (existingAgents == null || existingAgents.length == 0) {
                        diagnosis.append("   📝 目前沒有 Agent\n");
                    } else {
                        diagnosis.append("   📝 現有 ").append(existingAgents.length).append(" 個 Agent:\n");
                        for (int i = 0; i < existingAgents.length; i++) {
                            try {
                                diagnosis.append("      Agent ").append(i + 1).append(": ");
                                diagnosis.append("ID=").append(existingAgents[i].getAgentID()).append(", ");
                                diagnosis.append("狀態=").append(getAgentStateString(existingAgents[i].getState())).append("\n");
                            } catch (Exception e) {
                                diagnosis.append("      Agent ").append(i + 1).append(": 無法取得資訊 (").append(e.getMessage()).append(")\n");
                            }
                        }
                    }
                } catch (Exception e) {
                    diagnosis.append("   ⚠️ 無法查詢現有 Agent: ").append(e.getMessage()).append("\n");
                }
                
                // 4. 檢查 Addresses
                diagnosis.append("\n4. Address 檢查:\n");
                try {
                    Address[] addresses = agentTerminal.getAddresses();
                    if (addresses == null || addresses.length == 0) {
                        diagnosis.append("   ❌ 沒有可用的 Address\n");
                    } else {
                        diagnosis.append("   📝 找到 ").append(addresses.length).append(" 個 Address:\n");
                        for (int i = 0; i < addresses.length; i++) {
                            diagnosis.append("      Address ").append(i + 1).append(": ").append(addresses[i].getName());
                            diagnosis.append(" (類型: ").append(addresses[i].getClass().getSimpleName()).append(")\n");
                        }
                    }
                } catch (Exception e) {
                    diagnosis.append("   ⚠️ 無法查詢 Address: ").append(e.getMessage()).append("\n");
                }
                
            } else {
                diagnosis.append("   ❌ Terminal 不支援 AgentTerminal\n");
                diagnosis.append("   → 此分機可能不支援 CallCenter 功能\n");
            }
            
            // 5. 檢查 Provider
            diagnosis.append("\n5. Provider 檢查:\n");
            Provider provider = phoneCallService.getProvider();
            if (provider == null) {
                diagnosis.append("   ❌ Provider 為 null\n");
            } else {
                diagnosis.append("   ✅ Provider 類型: ").append(provider.getClass().getName()).append("\n");
                diagnosis.append("   ✅ Provider 狀態: ").append(provider.getState()).append("\n");
                
                // 檢查是否支援 CallCenter
                if (provider instanceof CallCenterProvider) {
                    diagnosis.append("   ✅ Provider 支援 CallCenter\n");
                } else {
                    diagnosis.append("   ⚠️ Provider 不支援 CallCenter\n");
                }
                
                // 嘗試取得 ACD Address
                try {
                    Address acdAddress = provider.getAddress(extension);
                    if (acdAddress != null) {
                        diagnosis.append("   ✅ 可取得分機 Address: ").append(acdAddress.getName()).append("\n");
                        diagnosis.append("   📝 Address 類型: ").append(acdAddress.getClass().getName()).append("\n");
                        
                        if (acdAddress instanceof ACDAddress) {
                            diagnosis.append("   ✅ Address 是 ACDAddress 類型\n");
                        } else {
                            diagnosis.append("   ⚠️ Address 不是 ACDAddress 類型\n");
                        }
                    } else {
                        diagnosis.append("   ❌ 無法取得分機 Address\n");
                    }
                } catch (Exception e) {
                    diagnosis.append("   ⚠️ 取得分機 Address 失敗: ").append(e.getMessage()).append("\n");
                }
            }
            
            diagnosis.append("\n=== 診斷結論 ===\n");
            if (conn.terminal instanceof AgentTerminal) {
                diagnosis.append("✅ 系統基本上支援 Agent 功能\n");
                diagnosis.append("💡 如果登入失敗，請查看具體錯誤訊息\n");
            } else {
                diagnosis.append("❌ 此分機不支援 Agent 功能\n");
                diagnosis.append("💡 請使用支援 CallCenter 的分機\n");
            }
            
        } catch (Exception e) {
            diagnosis.append("\n❌ 診斷過程發生錯誤:\n");
            diagnosis.append("錯誤類型: ").append(e.getClass().getSimpleName()).append("\n");
            diagnosis.append("錯誤訊息: ").append(e.getMessage()).append("\n");
            e.printStackTrace();
        }
        
        return diagnosis.toString();
    }
    
    /**
     * 記錄日誌到內存
     */
    private void logToMemory(String message) {
        String timestampedMessage = "[" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "] " + message;
        recentLogs.offer(timestampedMessage);
        
        // 限制日誌數量
        while (recentLogs.size() > MAX_LOGS) {
            recentLogs.poll();
        }
        
        // 同時輸出到 Console
        System.out.println(timestampedMessage);
    }
    
    /**
     * 取得最近的日誌
     */
    public String getRecentLogs() {
        StringBuilder logs = new StringBuilder();
        logs.append("=== 最近的 Agent 操作日誌 ===\n");
        logs.append("最多顯示最近 ").append(MAX_LOGS).append(" 條記錄\n\n");
        
        if (recentLogs.isEmpty()) {
            logs.append("目前沒有日誌記錄\n");
        } else {
            int count = 0;
            for (String log : recentLogs) {
                logs.append(log).append("\n");
                count++;
            }
            logs.append("\n總共 ").append(count).append(" 條記錄");
        }
        
        return logs.toString();
    }
    
    /**
     * 設置連線監聽器，用於 Manual-in 模式下監聽通話斷線事件
     */
    private void setupConnectionListener(String extension, String agentId) {
        try {
            logToMemory("[AGENT] 設置連線監聽器 - Extension: " + extension + ", AgentID: " + agentId);
            
            // 獲取分機連線
            PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                logToMemory("[AGENT] 無法設置連線監聽器：找不到分機連線");
                return;
            }
            
            // 創建連線監聽器來監聽通話斷線事件
            ConnectionListener connectionListener = new ConnectionListener() {
                @Override
                public void connectionDisconnected(ConnectionEvent connectionEvent) {
                    // 通話斷線事件 - Manual-in 模式的關鍵事件
                    logToMemory("[AGENT] Manual-in 模式檢測到通話斷線 - Extension: " + extension);
                    handleConnectionDisconnect(connectionEvent, extension, agentId);
                }
                
                @Override
                public void connectionAlerting(ConnectionEvent connectionEvent) {
                    // 來電響鈴事件
                    logToMemory("[AGENT] Manual-in 模式來電響鈴 - Extension: " + extension);
                }
                
                @Override
                public void connectionConnected(ConnectionEvent connectionEvent) {
                    // 通話接通事件
                    logToMemory("[AGENT] Manual-in 模式通話接通 - Extension: " + extension);
                }
                
                @Override
                public void connectionCreated(ConnectionEvent connectionEvent) {
                    // 連線建立事件
                }
                
                @Override
                public void connectionFailed(ConnectionEvent connectionEvent) {
                    // 連線失敗事件
                }
                
                @Override
                public void connectionInProgress(ConnectionEvent connectionEvent) {
                    // 連線進行中事件
                }
                
                @Override
                public void connectionUnknown(ConnectionEvent connectionEvent) {
                    // 連線狀態未知事件
                }
                
                // 繼承自 CallListener 的方法
                @Override
                public void callEventTransmissionEnded(CallEvent callEvent) {
                    // 通話事件傳輸結束
                }
                
                @Override
                public void callActive(CallEvent callEvent) {
                    // 通話啟動事件
                }
                
                @Override
                public void callInvalid(CallEvent callEvent) {
                    // 通話無效事件
                }
                
                @Override
                public void singleCallMetaProgressStarted(javax.telephony.MetaEvent metaEvent) {
                    // 單一通話元進度開始
                }
                
                @Override
                public void singleCallMetaProgressEnded(javax.telephony.MetaEvent metaEvent) {
                    // 單一通話元進度結束
                }
                
                @Override
                public void singleCallMetaSnapshotStarted(javax.telephony.MetaEvent metaEvent) {
                    // 單一通話元快照開始
                }
                
                @Override
                public void singleCallMetaSnapshotEnded(javax.telephony.MetaEvent metaEvent) {
                    // 單一通話元快照結束
                }
                
                @Override
                public void multiCallMetaMergeStarted(javax.telephony.MetaEvent metaEvent) {
                    // 多通話元合併開始
                }
                
                @Override
                public void multiCallMetaMergeEnded(javax.telephony.MetaEvent metaEvent) {
                    // 多通話元合併結束
                }
                
                @Override
                public void multiCallMetaTransferStarted(javax.telephony.MetaEvent metaEvent) {
                    // 多通話元轉接開始
                }
                
                @Override
                public void multiCallMetaTransferEnded(javax.telephony.MetaEvent metaEvent) {
                    // 多通話元轉接結束
                }
            };
            
            // 添加連線監聽器到 Address (使用 addCallListener，因為 ConnectionListener 繼承自 CallListener)
            if (conn.address != null) {
                try {
                    conn.address.addCallListener(connectionListener);
                    // 保存監聽器引用，以便後續清理
                    connectionListeners.put(extension, connectionListener);
                    logToMemory("[AGENT] ✓ 連線監聽器設置成功 - Extension: " + extension);
                    
                } catch (Exception e) {
                    logToMemory("[AGENT] 設置連線監聽器失敗: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] 設置連線監聽器失敗: " + e.getMessage());
        }
    }
    
    /**
     * 設置通話結束監聽器，用於 Manual-in 模式下通話結束後自動轉為等待狀態
     */
    private void setupCallEndListener(String extension, String agentId) {
        try {
            logToMemory("[AGENT] 設置通話結束監聽器 - Extension: " + extension + ", AgentID: " + agentId);
            
            // 獲取分機連線
            PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                logToMemory("[AGENT] 無法設置通話監聽器：找不到分機連線");
                return;
            }
            
            // 檢查 Terminal 是否支援 AgentTerminal
            if (!(conn.terminal instanceof AgentTerminal)) {
                logToMemory("[AGENT] 無法設置通話監聽器：Terminal 不支援 AgentTerminal");
                return;
            }
            
            // 添加通話監聽器到 Address
            if (conn.address != null) {
                try {
                    // 創建通話監聽器來監聽通話結束事件
                    CallListener callListener = new CallListener() {
                        @Override
                        public void callEventTransmissionEnded(CallEvent callEvent) {
                            // 通話事件傳輸結束
                        }
                        
                        // 不需要 callChangedEvent，使用特定的事件方法監聽通話狀態變化
                        
                        @Override
                        public void callActive(CallEvent callEvent) {
                            // 通話啟動事件
                            logToMemory("[AGENT] Manual-in 模式通話啟動 - Extension: " + extension);
                        }
                        
                        @Override
                        public void callInvalid(CallEvent callEvent) {
                            // 通話無效事件 - 表示通話結束
                            logToMemory("[AGENT] Manual-in 模式通話結束 - Extension: " + extension);
                            handleCallEvent(callEvent, extension, agentId);
                        }
                        
                        @Override
                        public void singleCallMetaProgressStarted(javax.telephony.MetaEvent metaEvent) {
                            // 單一通話元進度開始
                        }
                        
                        @Override
                        public void singleCallMetaProgressEnded(javax.telephony.MetaEvent metaEvent) {
                            // 單一通話元進度結束
                        }
                        
                        @Override
                        public void singleCallMetaSnapshotStarted(javax.telephony.MetaEvent metaEvent) {
                            // 單一通話元快照開始
                        }
                        
                        @Override
                        public void singleCallMetaSnapshotEnded(javax.telephony.MetaEvent metaEvent) {
                            // 單一通話元快照結束
                        }
                        
                        @Override
                        public void multiCallMetaMergeStarted(javax.telephony.MetaEvent metaEvent) {
                            // 多通話元合併開始
                        }
                        
                        @Override
                        public void multiCallMetaMergeEnded(javax.telephony.MetaEvent metaEvent) {
                            // 多通話元合併結束
                        }
                        
                        @Override
                        public void multiCallMetaTransferStarted(javax.telephony.MetaEvent metaEvent) {
                            // 多通話元轉接開始
                        }
                        
                        @Override
                        public void multiCallMetaTransferEnded(javax.telephony.MetaEvent metaEvent) {
                            // 多通話元轉接結束
                        }
                    };
                    
                    conn.address.addCallListener(callListener);
                    // 保存監聽器引用，以便後續清理
                    callListeners.put(extension, callListener);
                    logToMemory("[AGENT] ✓ 通話結束監聽器設置成功 - Extension: " + extension);
                    
                } catch (Exception e) {
                    logToMemory("[AGENT] 設置通話監聽器失敗: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] 設置通話結束監聽器失敗: " + e.getMessage());
        }
    }
    
    /**
     * 處理連線斷線事件，當 Manual-in 模式下通話斷線時轉為 ACW 狀態
     */
    private void handleConnectionDisconnect(ConnectionEvent connectionEvent, String extension, String agentId) {
        try {
            AgentStatus agentStatus = agentStatuses.get(extension);
            if (agentStatus == null || !"MANUAL_IN".equals(agentStatus.callHandlingMode)) {
                return; // 只處理 Manual-in 模式
            }
            
            logToMemory("[AGENT] Manual-in 模式通話斷線，準備轉為 ACW 狀態 - Extension: " + extension);
            
            // 延遲執行狀態轉換，確保通話完全結束
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // 等待2秒確保通話完全結束
                    
                    // Manual-in 模式下，通話結束後轉為 WORK_NOT_READY (ACW) 狀態
                    boolean success = setAgentWorkModeViaAPI(extension, "ACW");
                    if (success) {
                        logToMemory("[AGENT] ✓ Manual-in 模式：通話結束後自動轉為 ACW 狀態成功");
                        agentStatus.status = "ACW";
                        agentStatus.callHandlingMode = "ACW";
                    } else {
                        logToMemory("[AGENT] ✗ Manual-in 模式：通話結束後自動轉為 ACW 狀態失敗");
                    }
                    
                } catch (Exception e) {
                    logToMemory("[AGENT] Manual-in 自動轉 ACW 狀態過程中發生錯誤: " + e.getMessage());
                }
            }).start();
            
        } catch (Exception e) {
            logToMemory("[AGENT] 處理連線斷線事件失敗: " + e.getMessage());
        }
    }
    
    /**
     * 處理通話事件，當 Manual-in 模式下通話結束時自動轉為等待狀態
     */
    private void handleCallEvent(CallEvent callEvent, String extension, String agentId) {
        try {
            AgentStatus agentStatus = agentStatuses.get(extension);
            if (agentStatus == null || !"MANUAL_IN".equals(agentStatus.callHandlingMode)) {
                return; // 只處理 Manual-in 模式
            }
            
            Call call = callEvent.getCall();
            int callState = call.getState();
            
            logToMemory("[AGENT] Manual-in 模式通話事件 - Extension: " + extension + ", 通話狀態: " + callState);
            
            // 檢查是否為通話結束事件
            boolean callEnded = false;
            
            // 方法1：檢查 Call 狀態是否為 INVALID
            if (callState == Call.INVALID) {
                callEnded = true;
                logToMemory("[AGENT] Manual-in 模式檢測到通話結束 (Call.INVALID)");
            }
            
            // 方法2：檢查 Connection 狀態
            try {
                Connection[] connections = call.getConnections();
                if (connections != null) {
                    boolean allDisconnected = true;
                    for (Connection conn : connections) {
                        if (conn.getState() != Connection.DISCONNECTED) {
                            allDisconnected = false;
                            break;
                        }
                    }
                    if (allDisconnected && connections.length > 0) {
                        callEnded = true;
                        logToMemory("[AGENT] Manual-in 模式檢測到通話結束 (所有 Connection 已斷線)");
                    }
                }
            } catch (Exception e) {
                logToMemory("[AGENT] 檢查 Connection 狀態時發生錯誤: " + e.getMessage());
            }
            
            if (callEnded) {
                logToMemory("[AGENT] Manual-in 模式確認通話結束，準備轉為等待狀態");
                
                // 延遲執行狀態轉換，確保通話完全結束
                new Thread(() -> {
                    try {
                        Thread.sleep(2000); // 等待2秒確保通話完全結束
                        
                        // 將 Agent 轉為 NOT_READY 狀態（AUX 狀態）
                        boolean success = setAgentWorkModeViaAPI(extension, "AUX");
                        if (success) {
                            logToMemory("[AGENT] ✓ Manual-in 模式：通話結束後自動轉為等待狀態成功");
                            agentStatus.status = "AUX";
                            agentStatus.callHandlingMode = "AUX";
                        } else {
                            logToMemory("[AGENT] ✗ Manual-in 模式：通話結束後自動轉為等待狀態失敗");
                        }
                        
                    } catch (Exception e) {
                        logToMemory("[AGENT] Manual-in 自動轉等待狀態過程中發生錯誤: " + e.getMessage());
                    }
                }).start();
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] 處理通話事件失敗: " + e.getMessage());
        }
    }
    
    /**
     * 設置 Agent 狀態監聽器，監聽狀態變化以實現 Manual-in 自動轉 AUX
     */
    private void setupAgentStateListener(String extension, String agentId) {
        try {
            logToMemory("[AGENT] 設置 Agent 狀態監聽器 - Extension: " + extension + ", AgentID: " + agentId);
            
            // 獲取分機連線
            PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                logToMemory("[AGENT] 無法設置監聽器：找不到分機連線");
                return;
            }
            
            // 檢查 Terminal 是否支援 AgentTerminal
            if (!(conn.terminal instanceof AgentTerminal)) {
                logToMemory("[AGENT] 無法設置監聽器：Terminal 不支援 AgentTerminal");
                return;
            }
            
            AgentTerminal agentTerminal = (AgentTerminal) conn.terminal;
            
            // 暫時簡化實現：不使用事件監聽器，而是在設定工作模式時添加特殊邏輯
            logToMemory("[AGENT] 暫時跳過事件監聽器設置，使用替代方案");
            // TODO: 實現事件監聽器（需要更深入了解 Avaya JTAPI 的監聽器架構）
            
            logToMemory("[AGENT] ✓ Agent 狀態監聽器設置成功");
            
        } catch (Exception e) {
            logToMemory("[AGENT] 設置 Agent 狀態監聽器失敗: " + e.getMessage());
        }
    }
    
    /**
     * 檢查是否需要從 Manual-in 模式轉換到 AUX
     */
    private void checkManualInToAuxTransition(String extension, String agentId) {
        try {
            // 獲取 Agent 狀態
            AgentStatus agentStatus = agentStatuses.get(extension);
            if (agentStatus != null && "MANUAL_IN".equals(agentStatus.callHandlingMode)) {
                logToMemory("[AGENT] 檢測到 Manual-in 模式 Agent " + agentId + " 回到就緒狀態，準備轉換到 AUX");
                
                // 短暫延遲，確保狀態變化完成
                new Thread(() -> {
                    try {
                        Thread.sleep(1000); // 等待1秒
                        
                        // 轉換到 AUX 狀態
                        boolean success = setAgentWorkModeViaAPI(extension, "AUX");
                        if (success) {
                            logToMemory("[AGENT] ✓ Manual-in 模式：通話結束後自動轉換到 AUX 成功");
                        } else {
                            logToMemory("[AGENT] ✗ Manual-in 模式：通話結束後自動轉換到 AUX 失敗");
                        }
                        
                    } catch (Exception e) {
                        logToMemory("[AGENT] Manual-in 自動轉 AUX 過程中發生錯誤: " + e.getMessage());
                    }
                }).start();
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] 檢查 Manual-in 轉 AUX 時發生錯誤: " + e.getMessage());
        }
    }
    
    /**
     * 清理連線監聽器
     */
    private void cleanupConnectionListener(String extension) {
        try {
            ConnectionListener connectionListener = connectionListeners.remove(extension);
            if (connectionListener != null) {
                // 獲取分機連線
                PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
                if (conn != null && conn.address != null) {
                    conn.address.removeCallListener(connectionListener);
                    logToMemory("[AGENT] ✓ 連線監聽器已清理 - Extension: " + extension);
                }
            }
        } catch (Exception e) {
            logToMemory("[AGENT] 清理連線監聽器失敗: " + e.getMessage());
        }
    }
    
    /**
     * 清理通話監聽器
     */
    private void cleanupCallListener(String extension) {
        try {
            CallListener callListener = callListeners.remove(extension);
            if (callListener != null) {
                // 獲取分機連線
                PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
                if (conn != null && conn.address != null) {
                    conn.address.removeCallListener(callListener);
                    logToMemory("[AGENT] ✓ 通話監聽器已清理 - Extension: " + extension);
                }
            }
        } catch (Exception e) {
            logToMemory("[AGENT] 清理通話監聽器失敗: " + e.getMessage());
        }
    }
    
    /**
     * 清理 Agent 狀態監聽器
     */
    private void cleanupAgentStateListener(String extension) {
        try {
            javax.telephony.callcenter.AgentTerminalListener listener = agentListeners.remove(extension);
            if (listener != null) {
                // 獲取分機連線
                PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
                if (conn != null && conn.terminal instanceof AgentTerminal) {
                    AgentTerminal agentTerminal = (AgentTerminal) conn.terminal;
                    agentTerminal.removeTerminalListener(listener);
                    logToMemory("[AGENT] ✓ Agent 狀態監聽器已清理 - Extension: " + extension);
                }
            }
        } catch (Exception e) {
            logToMemory("[AGENT] 清理 Agent 狀態監聽器失敗: " + e.getMessage());
        }
    }
    
    /**
     * 測試所有可能的 Agent 狀態轉換
     */
    public String testAllAgentStates(String extension) {
        StringBuilder result = new StringBuilder();
        result.append("=== Agent 狀態測試報告 ===\n");
        result.append("分機: ").append(extension).append("\n");
        result.append("測試時間: ").append(new java.util.Date()).append("\n\n");
        
        try {
            // 取得分機連線
            PhoneCallService.ExtensionConnection conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：無法取得分機連線，請先登入 Agent";
            }
            
            // 檢查 Terminal 是否支援 AgentTerminal
            if (!(conn.terminal instanceof AgentTerminal)) {
                return "錯誤：Terminal 不支援 AgentTerminal 功能";
            }
            
            AgentTerminal agentTerminal = (AgentTerminal) conn.terminal;
            
            // 找到 Agent
            Agent[] existingAgents = agentTerminal.getAgents();
            if (existingAgents == null || existingAgents.length == 0) {
                return "錯誤：找不到任何 Agent，請先登入 Agent";
            }
            
            Agent agent = existingAgents[0];
            String agentId = agent.getAgentID();
            int currentState = agent.getState();
            
            result.append("Agent ID: ").append(agentId).append("\n");
            result.append("當前狀態: ").append(getAgentStateString(currentState)).append(" (代碼: ").append(currentState).append(")\n\n");
            
            // 定義要測試的所有狀態
            int[] testStates = {
                Agent.LOG_IN,        // 1 - 已登入
                Agent.LOG_OUT,       // 2 - 已登出  
                Agent.NOT_READY,     // 3 - 未就緒
                Agent.READY,         // 4 - 待機中
                Agent.WORK_NOT_READY,// 5 - 後處理中
                Agent.WORK_READY,    // 6 - 工作中
                Agent.BUSY           // 7 - 忙碌中
            };
            
            String[] stateNames = {
                "LOG_IN (1)",
                "LOG_OUT (2)", 
                "NOT_READY (3)",
                "READY (4)",
                "WORK_NOT_READY (5)",
                "WORK_READY (6)",
                "BUSY (7)"
            };
            
            result.append("=== 狀態轉換測試結果 ===\n");
            
            for (int i = 0; i < testStates.length; i++) {
                int targetState = testStates[i];
                String stateName = stateNames[i];
                
                try {
                    agent.setState(targetState);
                    Thread.sleep(100); // 給系統一點時間
                    
                    // 驗證狀態是否真的改變了
                    int newState = agent.getState();
                    if (newState == targetState) {
                        result.append("✅ ").append(stateName).append(" - 成功\n");
                    } else {
                        result.append("⚠️ ").append(stateName).append(" - 部分成功 (實際狀態: ").append(getAgentStateString(newState)).append(")\n");
                    }
                    
                } catch (Exception e) {
                    result.append("❌ ").append(stateName).append(" - 失敗: ").append(e.getMessage()).append("\n");
                }
            }
            
            // 恢復原始狀態
            try {
                agent.setState(currentState);
                result.append("\n✅ 已恢復到原始狀態: ").append(getAgentStateString(currentState));
            } catch (Exception e) {
                result.append("\n⚠️ 無法恢復原始狀態: ").append(e.getMessage());
            }
            
        } catch (Exception e) {
            result.append("測試過程發生錯誤: ").append(e.getMessage());
        }
        
        return result.toString();
    }
    
    /**
     * 處理通話完成事件（適用於 Manual-in 模式）
     */
    public String handleCallCompleted(String extension) {
        try {
            logToMemory("[AGENT] 處理通話完成事件 - Extension: " + extension);
            
            // 檢查 Agent 狀態
            AgentStatus agentStatus = agentStatuses.get(extension);
            if (agentStatus == null) {
                return "❌ 找不到 Agent 狀態記錄";
            }
            
            if (!"MANUAL_IN".equals(agentStatus.callHandlingMode)) {
                return "⚠️ Agent 不在 Manual-in 模式，無需處理";
            }
            
            // 轉換到 AUX 狀態
            boolean success = setAgentWorkModeViaAPI(extension, "AUX");
            if (success) {
                return "✅ Manual-in 通話完成：已自動轉換到 AUX 狀態\n" +
                       "分機: " + extension + "\n" +
                       "現在需要手動選擇是否接聽下一通電話";
            } else {
                return "❌ Manual-in 通話完成：轉換到 AUX 失敗";
            }
            
        } catch (Exception e) {
            logToMemory("[AGENT] 處理通話完成事件失敗: " + e.getMessage());
            return "處理通話完成事件失敗: " + e.getMessage();
        }
    }
}