package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.telephony.*;
import javax.telephony.callcontrol.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Service
public class TransferService {
    
    @Autowired
    private PhoneCallService phoneCallService;
    
    
     @Autowired
    private SseService sseService;
    

    // 用於追蹤轉接狀態的內部類
    private static class TransferSession {
        String transferringExtension;  // 發起轉接的分機
        String targetExtension;        // 轉接目標
        Call heldCall;                // 保持中的原始通話
        Call consultCall;             // 諮詢通話（二段轉接用）
        long startTime;               // 開始時間
        String sessionId;             // 會話ID
        
        TransferSession(String transferringExt, String target) {
            this.transferringExtension = transferringExt;
            this.targetExtension = target;
            this.startTime = System.currentTimeMillis();
            this.sessionId = transferringExt + "_" + System.currentTimeMillis();
        }
    }
    
    // 存儲活躍的轉接會話
    private final ConcurrentHashMap<String, TransferSession> activeTransfers = new ConcurrentHashMap<>();
    
    // 新增：分機號碼到會話ID的映射
    private final ConcurrentHashMap<String, String> extensionToSessionMap = new ConcurrentHashMap<>();
    
    /**
     * 一段轉接 (Blind Transfer)
     */
    public String blindTransfer(String extension, String targetExtension) {
        try {
            System.out.println("[BLIND_TRANSFER] 開始一段轉接: " + extension + " 將退出，通話轉到 " + targetExtension);
            
            // 1. 取得分機的連線
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：分機 " + extension + " 未登入或連線不可用";
            }
            
            // 2. 找到分機的活躍通話
            Call activeCall = findActiveCall(extension, conn);
            if (activeCall == null) {
                return "錯誤：分機 " + extension + " 沒有活躍的通話可以轉接";
            }
            
            // 3. 額外驗證：檢查通話是否真的處於連接狀態
            boolean hasActiveConnection = false;
            Connection[] connections = activeCall.getConnections();
            for (Connection connection : connections) {
                if (connection.getState() == Connection.CONNECTED) {
                    hasActiveConnection = true;
                    break;
                }
            }
            
            if (!hasActiveConnection) {
                return "錯誤：沒有找到處於連接狀態的通話連線";
            }
            
            // 4. 找到通話的另一方（原始來電者）- 優先選擇真正的分機而非系統號碼
            String originalCaller = null;
            String systemNumber = null;
            
            for (Connection connection : connections) {
                String addressName = connection.getAddress().getName();
                if (!addressName.equals(extension) && connection.getState() == Connection.CONNECTED) {
                    // 直接使用連接狀態為CONNECTED的對方號碼
                    originalCaller = addressName;
                    System.out.println("[BLIND_TRANSFER] 發現通話對方: " + addressName);
                    break; // 找到對方就立即跳出
                }
            }
            
            if (originalCaller == null) {
                return "錯誤：無法找到通話的另一方或對方未處於連接狀態";
            }
            
            System.out.println("[BLIND_TRANSFER] 確認原來電者: " + originalCaller);
            
            System.out.println("[BLIND_TRANSFER] 轉接場景: " + originalCaller + " ↔ " + extension + " → " + originalCaller + " ↔ " + targetExtension);
            
            // 4. 依序嘗試不同的轉接方法
            try {
                return blindTransferUsingRedirectMethod(extension, targetExtension, originalCaller, activeCall, conn);
            } catch (Exception e1) {
                System.out.println("[BLIND_TRANSFER] Redirect 方法失敗: " + e1.getMessage());
                
                try {
                    return blindTransferUsingReconnectMethod(extension, targetExtension, originalCaller, activeCall, conn);
                } catch (Exception e2) {
                    System.out.println("[BLIND_TRANSFER] Reconnect 方法失敗: " + e2.getMessage());
                    
                    try {
                        return blindTransferUsingConferenceMethod(extension, targetExtension, originalCaller, activeCall, conn);
                    } catch (Exception e3) {
                        System.out.println("[BLIND_TRANSFER] Conference 方法失敗: " + e3.getMessage());
                        throw new Exception("所有轉接方法都失敗: redirect(" + e1.getMessage() + 
                                          "), reconnect(" + e2.getMessage() + 
                                          "), conference(" + e3.getMessage() + ")");
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("[BLIND_TRANSFER] 轉接失敗: " + e.getMessage());
            e.printStackTrace();
            return "一段轉接失敗: " + e.getMessage();
        }
    }
    
    /**
     * 二段轉接 - 步驟1: 開始諮詢通話 - 保持原邏輯不變
     */
    public String startAttendedTransfer(String extension, String targetExtension) {
        try {
            System.out.println("[ATTENDED_TRANSFER] 開始二段轉接: " + extension + " → " + targetExtension);
            
            // 檢查是否已有轉接會話
            if (extensionToSessionMap.containsKey(extension)) {
                return "錯誤：分機 " + extension + " 已有進行中的轉接會話";
            }
            
            // 1. 取得分機連線
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：分機 " + extension + " 未登入或連線不可用";
            }
            
            // 2. 找到原始通話
            Call originalCall = findActiveCall(extension, conn);
            if (originalCall == null) {
                return "錯誤：分機 " + extension + " 沒有活躍的通話可以轉接";
            }
            
            // 3. 保持原始通話
            if (originalCall instanceof CallControlCall) {
                // 找到分機的連接並保持
                Connection[] connections = originalCall.getConnections();
                for (Connection connection : connections) {
                    if (connection.getAddress().getName().equals(extension)) {
                        // 保持原通話 + 新撥這個部分需要修正
                        try {
                            // 取得分機的終端連接來執行 hold
                            TerminalConnection[] termConns = connection.getTerminalConnections();
                            for (TerminalConnection termConn : termConns) {
                                if (termConn instanceof CallControlTerminalConnection) {
                                    CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                                    ccTermConn.hold();
                                    System.out.println("[ATTENDED_TRANSFER] 原始通話已保持");
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("[ATTENDED_TRANSFER] 保持通話時發生錯誤: " + e.getMessage());
                            // 即使保持失敗，仍然繼續流程
                        }
                        break;
                    }
                }
            }
            
            // 4. 建立諮詢通話
            System.out.println("[ATTENDED_TRANSFER] 建立諮詢通話到 " + targetExtension);
            Call consultCall = conn.provider.createCall();
            consultCall.connect(conn.terminal, conn.address, targetExtension);
            
            // 5. 建立轉接會話記錄
            TransferSession session = new TransferSession(extension, targetExtension);
            session.heldCall = originalCall;
            session.consultCall = consultCall;
            activeTransfers.put(session.sessionId, session);
            extensionToSessionMap.put(extension, session.sessionId);  // 新增：分機→會話映射
            
            System.out.println("[ATTENDED_TRANSFER] 諮詢通話已建立，會話ID: " + session.sessionId);
            
            // *** SSE-MODIFIED ***: 二段轉接開始後推送事件
            Map<String, String> transferStartEventData = new HashMap<>();
            transferStartEventData.put("action", "attended_transfer_started");
            transferStartEventData.put("target", targetExtension);
            transferStartEventData.put("sessionId", session.sessionId);
            transferStartEventData.put("timestamp", String.valueOf(System.currentTimeMillis()));
            sseService.sendEvent(extension, "phone_event", transferStartEventData);
            
            return "二段轉接已開始：正在連接 " + targetExtension + "，會話ID: " + session.sessionId + 
                   "\n提示：請等待目標分機接聽，然後調用完成轉接 API";
            
        } catch (Exception e) {
            System.err.println("[ATTENDED_TRANSFER] 開始轉接失敗: " + e.getMessage());
            e.printStackTrace();
            extensionToSessionMap.remove(extension);  // 清理映射
            return "二段轉接開始失敗: " + e.getMessage();
        }
    }
    
    /**
     * 二段轉接 - 步驟2: 完成轉接 (修正版本，增加詳細調試信息)
     */
    /**
 * 根據 AVAYA JTAPI 官方文件修正的轉接完成方法
 * 替換 TransferService.java 中的 completeAttendedTransfer 方法
 */
public String completeAttendedTransfer(String sessionId) {
    StringBuilder debugInfo = new StringBuilder();
    
    try {
        debugInfo.append("=== AVAYA JTAPI 二段轉接完成 ===\n");
        debugInfo.append("會話ID: ").append(sessionId).append("\n");
        
        // 1. 找到轉接會話
        TransferSession session = activeTransfers.get(sessionId);
        if (session == null) {
            debugInfo.append("錯誤：找不到轉接會話\n");
            return debugInfo.toString();
        }
        
        debugInfo.append("轉接者: ").append(session.transferringExtension).append("\n");
        debugInfo.append("目標: ").append(session.targetExtension).append("\n");
        
        // 2. 檢查通話狀態
        if (session.heldCall == null || session.consultCall == null) {
            debugInfo.append("錯誤：轉接會話狀態異常\n");
            activeTransfers.remove(sessionId);
            extensionToSessionMap.remove(session.transferringExtension);
            return debugInfo.toString();
        }
        
        debugInfo.append("保持通話類型: ").append(session.heldCall.getClass().getSimpleName()).append("\n");
        debugInfo.append("諮詢通話類型: ").append(session.consultCall.getClass().getSimpleName()).append("\n");
        
        // 3. 轉換為 CallControlCall（AVAYA 必需）
        if (!(session.heldCall instanceof CallControlCall) || 
            !(session.consultCall instanceof CallControlCall)) {
            debugInfo.append("錯誤：通話不支援 CallControl 介面\n");
            activeTransfers.remove(sessionId);
            extensionToSessionMap.remove(session.transferringExtension);
            return debugInfo.toString();
        }
        
        CallControlCall heldControlCall = (CallControlCall) session.heldCall;
        CallControlCall consultControlCall = (CallControlCall) session.consultCall;
        
        // 4. 【關鍵修正】設定轉接控制器（AVAYA 官方要求）
        debugInfo.append("\n--- 設定轉接控制器 ---\n");
        
        // 找到轉接者的 TerminalConnection
        TerminalConnection transferControllerTermConn = null;
        Connection[] heldConnections = heldControlCall.getConnections();
        
        for (Connection connection : heldConnections) {
            if (connection.getAddress().getName().equals(session.transferringExtension)) {
                TerminalConnection[] termConns = connection.getTerminalConnections();
                if (termConns != null && termConns.length > 0) {
                    transferControllerTermConn = termConns[0];
                    debugInfo.append("找到轉接控制器: ").append(session.transferringExtension).append("\n");
                    break;
                }
            }
        }
        
        if (transferControllerTermConn == null) {
            debugInfo.append("錯誤：找不到轉接控制器的 TerminalConnection\n");
            activeTransfers.remove(sessionId);
            extensionToSessionMap.remove(session.transferringExtension);
            return debugInfo.toString();
        }
        
        // 【關鍵步驟】設定轉接控制器到原始通話
        try {
            debugInfo.append("設定轉接控制器到原始通話\n");
            heldControlCall.setTransferController(transferControllerTermConn);
            debugInfo.append("轉接控制器設定成功\n");
        } catch (Exception e) {
            debugInfo.append("設定轉接控制器失敗: ").append(e.getMessage()).append("\n");
            // 繼續嘗試，某些系統可能不需要明確設定
        }
        
        // 5. 【核心修正】使用正確的 AVAYA JTAPI 轉接方法
        debugInfo.append("\n--- 執行 AVAYA JTAPI 轉接 ---\n");
        
        try {
            // 【重要】根據 AVAYA 官方文件：使用諮詢通話的 transfer 方法
            // 正確語法：consultCall.transfer(originalCall)
            // 錯誤語法：originalCall.transfer(consultCall) ← 這是你之前的問題
            
            debugInfo.append("執行：consultCall.transfer(originalCall)\n");
            consultControlCall.transfer(heldControlCall);
            debugInfo.append("AVAYA 轉接方法執行成功\n");
            
            // 6. 清理會話
            activeTransfers.remove(sessionId);
            extensionToSessionMap.remove(session.transferringExtension);
            debugInfo.append("轉接完成，會話已清理\n");
            
            
            debugInfo.append("=== 轉接成功完成 ===\n");

            // *** SSE-MODIFIED ***: 轉接完成後，發送 phone_event 通知前端刷新線路
            Map<String, String> eventData = new HashMap<>();
            eventData.put("action", "transfer_complete");
            eventData.put("target", session.targetExtension);
            eventData.put("timestamp", String.valueOf(System.currentTimeMillis()));
            sseService.sendEvent(session.transferringExtension, "phone_event", eventData);
            
            // *** 新增：通知轉接目標 ***
            Map<String, String> targetEventData = new HashMap<>();
            targetEventData.put("action", "transfer_received");
            targetEventData.put("from", session.transferringExtension);
            targetEventData.put("timestamp", String.valueOf(System.currentTimeMillis()));
            sseService.sendEvent(session.targetExtension, "phone_event", targetEventData);
            

            return debugInfo.toString() + "\n結果：二段轉接成功完成！";
            
        } catch (Exception transferException) {
            debugInfo.append("AVAYA 標準轉接失敗: ").append(transferException.getMessage()).append("\n");
            
            // 備用方法1：嘗試相反方向的轉接
            try {
                debugInfo.append("\n--- 嘗試備用方法：originalCall.transfer(consultCall) ---\n");
                heldControlCall.transfer(consultControlCall);
                debugInfo.append("備用轉接方法成功\n");
                
                
                activeTransfers.remove(sessionId);
                extensionToSessionMap.remove(session.transferringExtension);
                return debugInfo.toString() + "\n結果：使用備用方法完成轉接";
                
            } catch (Exception backupException) {
                debugInfo.append("備用轉接方法也失敗: ").append(backupException.getMessage()).append("\n");
                
                // 備用方法2：使用單步轉接到目標地址
                try {
                    debugInfo.append("\n--- 嘗試單步轉接方法 ---\n");
                    
                    // 找到原來電者
                    String originalCaller = null;
                    for (Connection connection : heldConnections) {
                        String addressName = connection.getAddress().getName();
                        if (!addressName.equals(session.transferringExtension)) {
                            originalCaller = addressName;
                            break;
                        }
                    }
                    
                    if (originalCaller != null) {
                        debugInfo.append("原來電者: ").append(originalCaller).append("\n");
                        debugInfo.append("使用單步轉接到: ").append(session.targetExtension).append("\n");
                        
                        // 使用單步轉接方法
                        Connection transferResult = heldControlCall.transfer(session.targetExtension);
                        debugInfo.append("單步轉接執行成功\n");
                        
                        
                        activeTransfers.remove(sessionId);
                        extensionToSessionMap.remove(session.transferringExtension);
                        return debugInfo.toString() + "\n結果：使用單步轉接方法完成";
                    } else {
                        throw new Exception("找不到原來電者");
                    }
                    
                } catch (Exception singleStepException) {
                    debugInfo.append("單步轉接也失敗: ").append(singleStepException.getMessage()).append("\n");
                    
                    // 最後的錯誤處理
                    activeTransfers.remove(sessionId);
                    extensionToSessionMap.remove(session.transferringExtension);
                    
                    debugInfo.append("\n=== 所有轉接方法都失敗 ===\n");
                    debugInfo.append("建議手動完成轉接或聯繫系統管理員\n");
                    
                    return debugInfo.toString() + "\n結果：轉接失敗，請手動處理";
                }
            }
        }
        
    } catch (Exception e) {
        debugInfo.append("轉接過程發生嚴重錯誤: ").append(e.getMessage()).append("\n");
        activeTransfers.remove(sessionId);
        extensionToSessionMap.entrySet().removeIf(entry -> entry.getValue().equals(sessionId));
        return debugInfo.toString() + "\n結果：轉接過程異常中斷";
    }
}

/**
 * 新增：AVAYA JTAPI 轉接前的狀態驗證
 */
public String validateTransferReadiness(String extension) {
    try {
        String sessionId = extensionToSessionMap.get(extension);
        if (sessionId == null) {
            return "分機 " + extension + " 沒有進行中的轉接會話";
        }
        
        TransferSession session = activeTransfers.get(sessionId);
        if (session == null) {
            return "轉接會話已失效";
        }
        
        StringBuilder status = new StringBuilder();
        status.append("=== AVAYA 轉接準備狀態檢查 ===\n");
        status.append("轉接者: ").append(session.transferringExtension).append("\n");
        status.append("目標: ").append(session.targetExtension).append("\n");
        
        // 檢查保持通話狀態
        if (session.heldCall != null) {
            status.append("保持通話狀態: 存在\n");
            Connection[] heldConn = session.heldCall.getConnections();
            status.append("保持通話連線數: ").append(heldConn.length).append("\n");
            
            boolean hasHeldConnection = false;
            for (Connection conn : heldConn) {
                status.append("- ").append(conn.getAddress().getName())
                      .append(" (").append(getConnectionStateName(conn.getState())).append(")\n");
                
                if (conn.getAddress().getName().equals(session.transferringExtension)) {
                    TerminalConnection[] termConns = conn.getTerminalConnections();
                    for (TerminalConnection tc : termConns) {
                        if (tc instanceof CallControlTerminalConnection) {
                            CallControlTerminalConnection cctc = (CallControlTerminalConnection) tc;
                            int state = cctc.getCallControlState();
                            status.append("  終端狀態: ").append(getCallControlStateName(state)).append("\n");
                            if (state == CallControlTerminalConnection.HELD) {
                                hasHeldConnection = true;
                            }
                        }
                    }
                }
            }
            status.append("保持狀態正確: ").append(hasHeldConnection ? "是" : "否").append("\n");
        }
        
        // 檢查諮詢通話狀態
        if (session.consultCall != null) {
            status.append("諮詢通話狀態: 存在\n");
            Connection[] consultConn = session.consultCall.getConnections();
            status.append("諮詢通話連線數: ").append(consultConn.length).append("\n");
            
            boolean targetConnected = false;
            for (Connection conn : consultConn) {
                status.append("- ").append(conn.getAddress().getName())
                      .append(" (").append(getConnectionStateName(conn.getState())).append(")\n");
                      
                if (conn.getAddress().getName().equals(session.targetExtension) &&
                    conn.getState() == Connection.CONNECTED) {
                    targetConnected = true;
                }
            }
            status.append("目標已接聽: ").append(targetConnected ? "是" : "否").append("\n");
        }
        
        status.append("\n建議：");
        if (session.heldCall != null && session.consultCall != null) {
            status.append("準備完成，可以執行轉接");
        } else {
            status.append("狀態不完整，請檢查通話設定");
        }
        
        return status.toString();
        
    } catch (Exception e) {
        return "狀態檢查失敗: " + e.getMessage();
    }
}
    
    /**
     * 嘗試多種轉接方法
     */
    /**
 * 針對 Lucent V15 系統的二段轉接修正方法
 * 替換 TransferService.java 中的 attemptTransferMethods 方法
 */
    /**
     * 嘗試多種轉接方法 - 修正版本（解決 originalCaller 變數問題）
     */
    private String attemptTransferMethods(TransferSession session, StringBuilder debugInfo, boolean foundHeldConnection) {
        
        // 首先找到原來電者（移到方法開頭，讓所有地方都能使用）
        String originalCaller = null;
        try {
            Connection[] heldConnections = session.heldCall.getConnections();
            for (Connection connection : heldConnections) {
                String addressName = connection.getAddress().getName();
                if (!addressName.equals(session.transferringExtension)) {
                    originalCaller = addressName;
                    debugInfo.append("識別原來電者: ").append(originalCaller).append("\n");
                    break;
                }
            }
        } catch (Exception e) {
            debugInfo.append("查找原來電者時發生錯誤: ").append(e.getMessage()).append("\n");
        }
        
        if (originalCaller == null) {
            debugInfo.append("警告：無法識別原來電者，將影響某些轉接方法\n");
        }
        
        // 方法1: Lucent V15 專用的 SingleStepTransfer 方法
        try {
            debugInfo.append("\n--- 嘗試方法1: Lucent V15 SingleStepTransfer ---\n");
            
            if (!(session.heldCall instanceof CallControlCall) || 
                !(session.consultCall instanceof CallControlCall)) {
                throw new Exception("通話不支援 CallControl 介面");
            }
            
            CallControlCall heldControlCall = (CallControlCall) session.heldCall;
            CallControlCall consultControlCall = (CallControlCall) session.consultCall;
            
            // 檢查是否支援 SingleStepTransfer
            debugInfo.append("檢查 SingleStepTransfer 支援性\n");
            
            // 先嘗試使用 Lucent 特有的轉接方式
            try {
                // 使用反射調用可能存在的 Lucent 專用方法
                java.lang.reflect.Method singleStepMethod = heldControlCall.getClass().getMethod("singleStepTransfer", CallControlCall.class);
                debugInfo.append("找到 singleStepTransfer 方法\n");
                singleStepMethod.invoke(heldControlCall, consultControlCall);
                debugInfo.append("singleStepTransfer 執行成功\n");
                
                return "成功：使用 Lucent V15 SingleStepTransfer 方法完成轉接";
                
            } catch (NoSuchMethodException e) {
                debugInfo.append("不支援 singleStepTransfer 方法\n");
                throw new Exception("SingleStepTransfer 方法不存在");
            }
            
        } catch (Exception e1) {
            debugInfo.append("方法1失敗: ").append(e1.getMessage()).append("\n");
            
            // 方法2: 使用 ConsultationTransfer 方法
            try {
                debugInfo.append("\n--- 嘗試方法2: Lucent V15 ConsultationTransfer ---\n");
                
                CallControlCall heldControlCall = (CallControlCall) session.heldCall;
                CallControlCall consultControlCall = (CallControlCall) session.consultCall;
                
                // 檢查並恢復保持的通話（但不全部恢復，只是準備轉接）
                debugInfo.append("準備轉接通話\n");
                
                // 嘗試使用 Lucent 的 consultation transfer
                try {
                    java.lang.reflect.Method consultMethod = heldControlCall.getClass().getMethod("consultationTransfer", Call.class);
                    debugInfo.append("找到 consultationTransfer 方法\n");
                    consultMethod.invoke(heldControlCall, consultControlCall);
                    debugInfo.append("consultationTransfer 執行成功\n");
                    
                    return "成功：使用 Lucent V15 ConsultationTransfer 方法完成轉接";
                    
                } catch (NoSuchMethodException e) {
                    debugInfo.append("不支援 consultationTransfer 方法\n");
                    throw new Exception("ConsultationTransfer 方法不存在");
                }
                
            } catch (Exception e2) {
                debugInfo.append("方法2失敗: ").append(e2.getMessage()).append("\n");
                
                // 方法3: 模擬 Avaya 風格的轉接操作
                try {
                    debugInfo.append("\n--- 嘗試方法3: 模擬 Avaya 風格轉接 ---\n");
                    
                    // 取得轉接者的連線信息
                    var conn = phoneCallService.getExtensionConnection(session.transferringExtension);
                    if (conn == null) {
                        throw new Exception("無法取得轉接者連線");
                    }
                    var extensionConn = (PhoneCallService.ExtensionConnection) conn;
                    
                    if (originalCaller == null) {
                        throw new Exception("找不到原來電者，無法執行此方法");
                    }
                    
                    // 步驟1: 先讓目標分機接聽諮詢通話（如果還沒接聽）
                    debugInfo.append("確保目標分機已接聽諮詢通話\n");
                    boolean targetAnswered = false;
                    Connection[] consultConnections = session.consultCall.getConnections();
                    for (Connection connection : consultConnections) {
                        if (connection.getAddress().getName().equals(session.targetExtension)) {
                            if (connection.getState() == Connection.CONNECTED) {
                                targetAnswered = true;
                                debugInfo.append("目標分機已接聽\n");
                            }
                            break;
                        }
                    }
                    
                    if (!targetAnswered) {
                        debugInfo.append("目標分機尚未接聽，嘗試自動接聽\n");
                        // 這裡可以嘗試自動接聽，但通常需要手動
                    }
                    
                    // 步驟2: 使用更溫和的方式進行轉接
                    debugInfo.append("嘗試溫和轉接方式\n");
                    
                    // 不直接斷線，而是嘗試將兩通話合併
                    try {
                        // 嘗試使用 Lucent 系統可能支援的 join 操作
                        java.lang.reflect.Method joinMethod = session.heldCall.getClass().getMethod("join", Call.class);
                        debugInfo.append("找到 join 方法，嘗試合併通話\n");
                        joinMethod.invoke(session.heldCall, session.consultCall);
                        
                        // 等待合併完成
                        Thread.sleep(2000);
                        
                        // 然後讓轉接者退出
                        debugInfo.append("轉接者退出合併的通話\n");
                        Connection[] allConnections = session.heldCall.getConnections();
                        for (Connection connection : allConnections) {
                            if (connection.getAddress().getName().equals(session.transferringExtension)) {
                                connection.disconnect();
                                debugInfo.append("轉接者已退出\n");
                                break;
                            }
                        }
                        
                        return "成功：使用 join 方法完成轉接";
                        
                    } catch (NoSuchMethodException e) {
                        debugInfo.append("不支援 join 方法，嘗試手動序列操作\n");
                        
                        // 最後的手動方法：分步驟進行
                        debugInfo.append("執行分步驟轉接\n");
                        
                        // 1. 通知原來電者即將轉接（播放提示音或保持音樂）
                        debugInfo.append("步驟1: 保持原來電者\n");
                        
                        // 2. 確保諮詢通話建立完成
                        debugInfo.append("步驟2: 確認諮詢通話狀態\n");
                        
                        // 3. 嘗試讓原來電者撥打給目標（如果系統支援）
                        try {
                            debugInfo.append("步驟3: 嘗試讓原來電者重新撥打給目標\n");
                            
                            // 先斷開轉接者與原來電者的連線
                            Connection transferrerToOriginal = null;
                            for (Connection connection : session.heldCall.getConnections()) {
                                if (connection.getAddress().getName().equals(session.transferringExtension)) {
                                    transferrerToOriginal = connection;
                                    break;
                                }
                            }
                            
                            // 斷開轉接者與諮詢對象的連線
                            Connection transferrerToTarget = null;
                            for (Connection connection : session.consultCall.getConnections()) {
                                if (connection.getAddress().getName().equals(session.transferringExtension)) {
                                    transferrerToTarget = connection;
                                    break;
                                }
                            }
                            
                            if (transferrerToOriginal != null && transferrerToTarget != null) {
                                // 同時斷開轉接者的兩個連線
                                debugInfo.append("同時斷開轉接者的兩個連線\n");
                                transferrerToOriginal.disconnect();
                                transferrerToTarget.disconnect();
                                
                                // 等待斷線完成
                                Thread.sleep(2000);
                                
                                // 建立原來電者到目標的直接連線
                                debugInfo.append("建立 ").append(originalCaller).append(" 到 ").append(session.targetExtension).append(" 的直接連線\n");
                                
                                Address targetAddress = extensionConn.provider.getAddress(session.targetExtension);
                                Terminal targetTerminal = targetAddress.getTerminals()[0];
                                
                                Call newCall = extensionConn.provider.createCall();
                                newCall.connect(targetTerminal, targetAddress, originalCaller);
                                
                                debugInfo.append("新連線建立成功\n");
                                
                                return "成功：使用分步驟方法完成轉接";
                            } else {
                                throw new Exception("找不到轉接者的連線");
                            }
                            
                        } catch (Exception e4) {
                            debugInfo.append("分步驟轉接失敗: ").append(e4.getMessage()).append("\n");
                            throw e4;
                        }
                    }
                    
                } catch (Exception e3) {
                    debugInfo.append("方法3失敗: ").append(e3.getMessage()).append("\n");
                    
                    // 方法4: 最簡單的處理方式 - 給出手動指示
                    debugInfo.append("\n--- 方法4: 手動轉接指示 ---\n");
                    debugInfo.append("自動轉接失敗，建議手動操作：\n");
                    debugInfo.append("1. 在分機 ").append(session.transferringExtension).append(" 上按轉接鍵\n");
                    debugInfo.append("2. 或者手動掛斷，讓用戶重新撥打\n");
                    
                    if (originalCaller != null) {
                        debugInfo.append("3. 通話狀態：").append(originalCaller).append(" 等待中，").append(session.targetExtension).append(" 已連線\n");
                    } else {
                        debugInfo.append("3. 目標分機 ").append(session.targetExtension).append(" 已連線\n");
                    }
                    
                    return "部分成功：諮詢通話已建立，但自動轉接失敗。請手動完成轉接或取消轉接後重試。";
                }
            }
        }
    }
    
    /**
     * 新增：Lucent V15 專用的轉接狀態檢查
     */
    public String checkLucentTransferCapabilities(String extension) {
        try {
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：分機 " + extension + " 未登入";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("=== Lucent V15 系統轉接能力檢查 ===\n");
            result.append("分機: ").append(extension).append("\n");
            
            Call activeCall = findActiveCall(extension, conn);
            if (activeCall == null) {
                result.append("狀態: 沒有活躍通話\n");
                result.append("建議: 先建立一個通話再檢查轉接能力\n");
                return result.toString();
            }
            
            result.append("通話類型: ").append(activeCall.getClass().getSimpleName()).append("\n");
            
            // 檢查 Lucent 特有的方法
            try {
                java.lang.reflect.Method[] methods = activeCall.getClass().getMethods();
                result.append("支援的轉接相關方法:\n");
                
                boolean foundTransferMethods = false;
                for (java.lang.reflect.Method method : methods) {
                    String methodName = method.getName().toLowerCase();
                    if (methodName.contains("transfer") || 
                        methodName.contains("consult") || 
                        methodName.contains("redirect") ||
                        methodName.contains("join")) {
                        result.append("- ").append(method.getName()).append("(");
                        
                        // 顯示參數類型
                        Class<?>[] paramTypes = method.getParameterTypes();
                        for (int i = 0; i < paramTypes.length; i++) {
                            if (i > 0) result.append(", ");
                            result.append(paramTypes[i].getSimpleName());
                        }
                        result.append(")\n");
                        foundTransferMethods = true;
                    }
                }
                
                if (!foundTransferMethods) {
                    result.append("- 沒有找到轉接相關方法\n");
                }
                
                // 檢查連線狀態
                result.append("\n當前通話連線狀態:\n");
                Connection[] connections = activeCall.getConnections();
                for (int i = 0; i < connections.length; i++) {
                    Connection conn1 = connections[i];
                    result.append("連線 ").append(i).append(": ")
                          .append(conn1.getAddress().getName())
                          .append(" (").append(getConnectionStateName(conn1.getState())).append(")\n");
                }
                
            } catch (Exception e) {
                result.append("檢查轉接方法時發生錯誤: ").append(e.getMessage()).append("\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return "檢查失敗: " + e.getMessage();
        }
    }
/**
 * 新增：Lucent V15 專用的轉接狀態檢查
 */

    /**
     * 輔助方法：取得連線狀態名稱
     */
    private String getConnectionStateName(int state) {
        switch (state) {
            case Connection.IDLE: return "IDLE";
            case Connection.INPROGRESS: return "INPROGRESS";
            case Connection.ALERTING: return "ALERTING";
            case Connection.CONNECTED: return "CONNECTED";
            case Connection.DISCONNECTED: return "DISCONNECTED";
            case Connection.FAILED: return "FAILED";
            case Connection.UNKNOWN: return "UNKNOWN";
            default: return "UNKNOWN(" + state + ")";
        }
    }
    
    /**
     * 輔助方法：取得終端連線狀態名稱
     */
    private String getTerminalConnectionStateName(int state) {
        switch (state) {
            case TerminalConnection.IDLE: return "IDLE";
            case TerminalConnection.RINGING: return "RINGING";
            case TerminalConnection.ACTIVE: return "ACTIVE";
            case TerminalConnection.PASSIVE: return "PASSIVE";
            case TerminalConnection.DROPPED: return "DROPPED";
            case TerminalConnection.UNKNOWN: return "UNKNOWN";
            default: return "UNKNOWN(" + state + ")";
        }
    }
    
    /**
     * 輔助方法：取得 CallControl 狀態名稱
     */
    private String getCallControlStateName(int state) {
        switch (state) {
            case CallControlTerminalConnection.IDLE: return "IDLE";
            case CallControlTerminalConnection.RINGING: return "RINGING";
            case CallControlTerminalConnection.TALKING: return "TALKING";
            case CallControlTerminalConnection.HELD: return "HELD";
            case CallControlTerminalConnection.BRIDGED: return "BRIDGED";
            case CallControlTerminalConnection.DROPPED: return "DROPPED";
            case CallControlTerminalConnection.UNKNOWN: return "UNKNOWN";
            case CallControlTerminalConnection.INUSE: return "INUSE";
            default: return "UNKNOWN(" + state + ")";
        }
    }
    
    // ===== 以下保持原有方法不變 =====
    
    /**
     * 二段轉接 - 取消轉接 - 保持原邏輯不變
     */
    public String cancelAttendedTransfer(String sessionId) {
        try {
            System.out.println("[ATTENDED_TRANSFER] 取消轉接，會話ID: " + sessionId);
            
            // 1. 找到轉接會話
            TransferSession session = activeTransfers.remove(sessionId);
            if (session == null) {
                return "錯誤：找不到轉接會話 " + sessionId;
            }
            
            // 清理分機映射
            extensionToSessionMap.remove(session.transferringExtension);
            
            // 2. 掛斷諮詢通話
            if (session.consultCall != null) {
                try {
                    Connection[] consultConnections = session.consultCall.getConnections();
                    for (Connection conn : consultConnections) {
                        if (conn.getState() != Connection.DISCONNECTED) {
                            conn.disconnect();
                        }
                    }
                    System.out.println("[ATTENDED_TRANSFER] 諮詢通話已掛斷");
                } catch (Exception e) {
                    System.out.println("[ATTENDED_TRANSFER] 掛斷諮詢通話時發生錯誤: " + e.getMessage());
                }
            }
            
            // 3. 恢復原始通話
            if (session.heldCall != null) {
                try {
                    Connection[] heldConnections = session.heldCall.getConnections();
                    for (Connection connection : heldConnections) {
                        if (connection.getAddress().getName().equals(session.transferringExtension)) {
                            // 嘗試恢復通話
                            TerminalConnection[] termConns = connection.getTerminalConnections();
                            for (TerminalConnection termConn : termConns) {
                                if (termConn instanceof CallControlTerminalConnection) {
                                    CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                                    // 檢查是否為保持狀態
                                    if (ccTermConn.getCallControlState() == CallControlTerminalConnection.HELD) {
                                        ccTermConn.unhold();
                                        System.out.println("[ATTENDED_TRANSFER] 原始通話已恢復");
                                        break;
                                    }
                                }
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[ATTENDED_TRANSFER] 恢復原始通話時發生錯誤: " + e.getMessage());
                }
            }
            
            // *** SSE-MODIFIED ***: 轉接取消後推送事件
            Map<String, String> cancelEventData = new HashMap<>();
            cancelEventData.put("action", "transfer_cancelled");
            cancelEventData.put("timestamp", String.valueOf(System.currentTimeMillis()));
            sseService.sendEvent(session.transferringExtension, "phone_event", cancelEventData);
            
            return "二段轉接已取消，原始通話已恢復";
            
        } catch (Exception e) {
            System.err.println("[ATTENDED_TRANSFER] 取消轉接失敗: " + e.getMessage());
            e.printStackTrace();
            return "取消轉接失敗: " + e.getMessage();
        }
    }
    
    /**
     * 取得所有活躍的轉接會話狀態 - 保持原邏輯不變
     */
    public String getTransferStatus() {
        if (activeTransfers.isEmpty()) {
            return "目前沒有活躍的轉接會話";
        }
        
        StringBuilder status = new StringBuilder("活躍的轉接會話:\n");
        for (TransferSession session : activeTransfers.values()) {
            long duration = (System.currentTimeMillis() - session.startTime) / 1000;
            status.append("會話ID: ").append(session.sessionId)
                  .append(" | 轉接者: ").append(session.transferringExtension)
                  .append(" | 目標: ").append(session.targetExtension)
                  .append(" | 持續時間: ").append(duration).append("秒\n");
        }
        return status.toString();
    }
    
    /**
     * 根據分機號碼完成轉接
     */
    public String completeAttendedTransferByExtension(String extension) {
        try {
            String sessionId = extensionToSessionMap.get(extension);
            if (sessionId == null) {
                return "錯誤：分機 " + extension + " 沒有進行中的轉接會話";
            }
            
            System.out.println("[ATTENDED_TRANSFER] 根據分機 " + extension + " 完成轉接，會話ID: " + sessionId);
            return completeAttendedTransfer(sessionId);
            
        } catch (Exception e) {
            System.err.println("[ATTENDED_TRANSFER] 根據分機完成轉接失敗: " + e.getMessage());
            extensionToSessionMap.remove(extension);
            return "根據分機完成轉接失敗: " + e.getMessage();
        }
    }
    
    /**
     * 根據分機號碼取消轉接
     */
    public String cancelAttendedTransferByExtension(String extension) {
        try {
            String sessionId = extensionToSessionMap.get(extension);
            if (sessionId == null) {
                return "錯誤：分機 " + extension + " 沒有進行中的轉接會話";
            }
            
            System.out.println("[ATTENDED_TRANSFER] 根據分機 " + extension + " 取消轉接，會話ID: " + sessionId);
            return cancelAttendedTransfer(sessionId);
            
        } catch (Exception e) {
            System.err.println("[ATTENDED_TRANSFER] 根據分機取消轉接失敗: " + e.getMessage());
            extensionToSessionMap.remove(extension);
            return "根據分機取消轉接失敗: " + e.getMessage();
        }
    }
    
    /**
     * 根據分機號碼查看轉接狀態
     */
    public String getTransferStatusByExtension(String extension) {
        String sessionId = extensionToSessionMap.get(extension);
        if (sessionId == null) {
            return "分機 " + extension + " 沒有進行中的轉接會話";
        }
        
        TransferSession session = activeTransfers.get(sessionId);
        if (session == null) {
            extensionToSessionMap.remove(extension);
            return "分機 " + extension + " 沒有進行中的轉接會話";
        }
        
        long duration = (System.currentTimeMillis() - session.startTime) / 1000;
        return "分機 " + extension + " 的轉接會話:\n" +
               "會話ID: " + session.sessionId + "\n" +
               "轉接者: " + session.transferringExtension + "\n" +
               "目標: " + session.targetExtension + "\n" +
               "持續時間: " + duration + "秒";
    }
    
    /**
     * 新增：詳細調試當前通話狀態
     */
    public String debugCallStatus(String extension) {
        try {
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：分機 " + extension + " 未登入或連線不可用";
            }
            
            StringBuilder debug = new StringBuilder();
            debug.append("=== 分機 ").append(extension).append(" 通話狀態詳細調試 ===\n");
            
            Call activeCall = findActiveCall(extension, conn);
            if (activeCall == null) {
                debug.append("沒有找到活躍通話\n");
                return debug.toString();
            }
            
            debug.append("找到活躍通話，類型: ").append(activeCall.getClass().getSimpleName()).append("\n");
            
            Connection[] connections = activeCall.getConnections();
            debug.append("通話連線數: ").append(connections.length).append("\n");
            
            for (int i = 0; i < connections.length; i++) {
                Connection connection = connections[i];
                String addressName = connection.getAddress().getName();
                int state = connection.getState();
                
                debug.append("連線 ").append(i).append(": ").append(addressName)
                     .append(" (狀態: ").append(getConnectionStateName(state)).append(")\n");
                
                
                // 檢查終端連線
                TerminalConnection[] termConns = connection.getTerminalConnections();
                if (termConns != null) {
                    for (TerminalConnection tc : termConns) {
                        debug.append("  終端: ").append(tc.getTerminal().getName())
                             .append(" (狀態: ").append(getTerminalConnectionStateName(tc.getState())).append(")\n");
                    }
                }
            }
            
            // 分析問題
            debug.append("\n=== 問題分析 ===\n");
            boolean hasSystemNumber = false;
            boolean hasValidConnection = false;
            
            for (Connection connection : connections) {
                String addressName = connection.getAddress().getName();
                if (!addressName.equals(extension) && connection.getState() == Connection.CONNECTED) {
                    hasValidConnection = true;
                    debug.append("發現有效的對方: ").append(addressName).append("\n");
                }
            }
            
            if (!hasValidConnection) {
                debug.append("問題：沒有找到有效的通話對方\n");
                debug.append("建議：請確認分機之間是否建立了真正的通話連線\n");
            }
            
            return debug.toString();
            
        } catch (Exception e) {
            return "調試失敗: " + e.getMessage();
        }
    }
    
    /**
     * 測試轉接功能 - 顯示詳細調試信息 - 保持原邏輯不變
     */
    public String testTransferCapabilities(String extension) {
        try {
            System.out.println("[TEST_TRANSFER] 測試分機 " + extension + " 的轉接能力");
            
            // 1. 取得分機連線
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：分機 " + extension + " 未登入或連線不可用";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("分機 ").append(extension).append(" 轉接能力測試:\n");
            
            // 2. 檢查是否有活躍通話
            Call activeCall = findActiveCall(extension, conn);
            if (activeCall == null) {
                result.append("- 沒有活躍通話可供測試\n");
                result.append("- 建議：先建立一個通話再測試轉接能力\n");
                return result.toString();
            }
            
            result.append("- 找到活躍通話: ").append(activeCall.getClass().getSimpleName()).append("\n");
            
            // 3. 檢查通話介面支援
            if (activeCall instanceof CallControlCall) {
                result.append("- 支援 CallControlCall 介面 ✓\n");
                
                // 4. 檢查連線支援
                Connection[] connections = activeCall.getConnections();
                result.append("- 通話連線數: ").append(connections.length).append("\n");
                
                boolean hasValidConnection = false;
                for (int i = 0; i < connections.length; i++) {
                    Connection connection = connections[i];
                    result.append("  連線 ").append(i).append(": ").append(connection.getAddress().getName());
                    
                    if (connection instanceof CallControlConnection) {
                        result.append(" (支援 CallControlConnection ✓)");
                        hasValidConnection = true;
                    } else {
                        result.append(" (不支援 CallControlConnection ✗)");
                    }
                    result.append("\n");
                }
                
                if (hasValidConnection) {
                    result.append("- 基本轉接條件滿足 ✓\n");
                } else {
                    result.append("- 基本轉接條件不滿足 ✗\n");
                }
                
            } else {
                result.append("- 不支援 CallControlCall 介面 ✗\n");
            }
            
            // 5. 提供建議
            result.append("\n建議的轉接方法:\n");
            result.append("- 方法1: 使用 CallControlCall.transfer(Call) \n");
            result.append("- 方法2: 使用 CallControlConnection.redirect() \n");
            result.append("- 方法3: 掛斷原通話 + 重新撥打 (簡化轉接)\n");
            
            return result.toString();
            
        } catch (Exception e) {
            System.err.println("[TEST_TRANSFER] 測試失敗: " + e.getMessage());
            e.printStackTrace();
            return "轉接能力測試失敗: " + e.getMessage();
        }
    }
    
    /**
     * 清理過期的轉接會話 (超過5分鐘自動清理) - 保持原邏輯不變
     */
    public void cleanupExpiredTransfers() {
        long currentTime = System.currentTimeMillis();
        long fiveMinutes = 5 * 60 * 1000; // 5分鐘
        
        activeTransfers.entrySet().removeIf(entry -> {
            TransferSession session = entry.getValue();
            if (currentTime - session.startTime > fiveMinutes) {
                System.out.println("[TRANSFER_CLEANUP] 清理過期轉接: " + entry.getKey());
                try {
                    // *** SSE-MODIFIED ***: 轉接過期清理後推送事件
                    Map<String, String> expiredEventData = new HashMap<>();
                    expiredEventData.put("action", "transfer_expired");
                    expiredEventData.put("reason", "轉接超時自動清理");
                    expiredEventData.put("timestamp", String.valueOf(System.currentTimeMillis()));
                    sseService.sendEvent(session.transferringExtension, "phone_event", expiredEventData);
                    
                    cancelAttendedTransfer(entry.getKey());
                    extensionToSessionMap.remove(session.transferringExtension);
                } catch (Exception e) {
                    System.err.println("[TRANSFER_CLEANUP] 清理轉接時發生錯誤: " + e.getMessage());
                }
                return true;
            }
            return false;
        });
    }
    
    // ========================================
    // 以下是原有的輔助方法 - 保持不變
    // ========================================
    
    /**
     * 找到分機的活躍通話
     */
    private Call findActiveCall(String extension, Object conn) {
        try {
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            if (extensionConn.terminal != null) {
                TerminalConnection[] termConnections = extensionConn.terminal.getTerminalConnections();
                if (termConnections != null) {
                    for (TerminalConnection termConn : termConnections) {
                        if (termConn.getState() == TerminalConnection.ACTIVE) {
                            return termConn.getConnection().getCall();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[TRANSFER] 找尋活躍通話時發生錯誤: " + e.getMessage());
        }
        return null;
    }
    
    // ========================================
    // 以下是一段轉接的輔助方法 - 保持原邏輯不變
    // ========================================
    
    /**
     * 方法1：使用 Redirect 進行轉接 (最直接的方法)
     */
    private String blindTransferUsingRedirectMethod(String extension, String targetExtension, String originalCaller, Call activeCall, Object conn) throws Exception {
        System.out.println("[BLIND_TRANSFER] 嘗試使用 Redirect 方法");
        
        // 找到轉接者的連線
        Connection[] connections = activeCall.getConnections();
        for (Connection connection : connections) {
            if (connection.getAddress().getName().equals(extension)) {
                if (connection instanceof CallControlConnection) {
                    CallControlConnection controlConn = (CallControlConnection) connection;
                    System.out.println("[BLIND_TRANSFER] 執行 redirect: " + originalCaller + " → " + targetExtension);
                    controlConn.redirect(targetExtension);
                    
                    
                    return "一段轉接成功：" + originalCaller + " 的通話已轉接到分機 " + targetExtension + "（使用 redirect 方法）";
                }
            }
        }
        throw new Exception("找不到可用的 CallControlConnection 進行 redirect");
    }
    
    /**
     * 方法2：使用JTAPI標準的single-step transfer方法（正確的盲轉接實現）
     */
    private String blindTransferUsingReconnectMethod(String extension, String targetExtension, String originalCaller, Call activeCall, Object conn) throws Exception {
        System.out.println("[BLIND_TRANSFER] 嘗試使用 JTAPI Single-Step Transfer 方法");
        
        // 驗證原來電者是否為有效的分機號碼
        if (originalCaller == null || originalCaller.trim().isEmpty()) {
            throw new Exception("原來電者號碼無效或為空");
        }
        
        
        System.out.println("[BLIND_TRANSFER] 驗證通過 - 原來電者: " + originalCaller + ", 目標: " + targetExtension);
        
        // 確保這是一個 CallControlCall
        if (!(activeCall instanceof CallControlCall)) {
            throw new Exception("通話不支援 CallControl 功能");
        }
        
        CallControlCall controlCall = (CallControlCall) activeCall;
        
        // 設定轉接控制器（轉接者）
        System.out.println("[BLIND_TRANSFER] 設定轉接控制器: " + extension);
        
        Connection[] connections = controlCall.getConnections();
        TerminalConnection transferController = null;
        
        for (Connection connection : connections) {
            if (connection.getAddress().getName().equals(extension)) {
                TerminalConnection[] termConns = connection.getTerminalConnections();
                for (TerminalConnection tc : termConns) {
                    if (tc instanceof CallControlTerminalConnection) {
                        CallControlTerminalConnection cctc = (CallControlTerminalConnection) tc;
                        if (cctc.getCallControlState() == CallControlTerminalConnection.TALKING) {
                            transferController = tc;
                            System.out.println("[BLIND_TRANSFER] 找到轉接控制器，狀態: TALKING");
                            break;
                        }
                    }
                }
                break;
            }
        }
        
        if (transferController == null) {
            throw new Exception("找不到處於 TALKING 狀態的轉接控制器");
        }
        
        try {
            // 設定轉接控制器
            controlCall.setTransferController(transferController);
            System.out.println("[BLIND_TRANSFER] 轉接控制器設定完成");
            
            // 執行JTAPI標準的單步轉接（盲轉接）
            System.out.println("[BLIND_TRANSFER] 執行單步轉接到: " + targetExtension);
            Connection newConnection = controlCall.transfer(targetExtension);
            
            System.out.println("[BLIND_TRANSFER] 單步轉接執行成功");
            
            if (newConnection != null) {
                System.out.println("[BLIND_TRANSFER] 新連線建立: " + newConnection.getAddress().getName());
            } else {
                System.out.println("[BLIND_TRANSFER] 轉接到外部號碼，無新連線返回");
            }
            
            
            return "一段轉接成功：" + originalCaller + " 的通話已轉接到分機 " + targetExtension + "（使用 JTAPI single-step transfer）";
            
        } catch (Exception e) {
            System.out.println("[BLIND_TRANSFER] JTAPI single-step transfer 失敗: " + e.getMessage());
            
            // 備用方案：使用傳統的斷開重連方法
            try {
                System.out.println("[BLIND_TRANSFER] 嘗試備用方案：斷開重連");
                
                // 找到轉接者連線並斷開
                Connection transferrerConnection = null;
                for (Connection connection : connections) {
                    if (connection.getAddress().getName().equals(extension)) {
                        transferrerConnection = connection;
                        break;
                    }
                }
                
                if (transferrerConnection != null) {
                    transferrerConnection.disconnect();
                    System.out.println("[BLIND_TRANSFER] 轉接者連線已斷開");
                    Thread.sleep(1500);
                }
                
                // 讓原來電者撥打給目標
                var extensionConn = (PhoneCallService.ExtensionConnection) conn;
                Address callerAddress = extensionConn.provider.getAddress(originalCaller);
                if (callerAddress == null) {
                    throw new Exception("原來電者 " + originalCaller + " 地址獲取失敗");
                }
                
                Terminal[] callerTerminals = callerAddress.getTerminals();
                if (callerTerminals == null || callerTerminals.length == 0) {
                    throw new Exception("原來電者 " + originalCaller + " 沒有可用的終端");
                }
                
                Terminal callerTerminal = callerTerminals[0];
                CallControlCall newCall = (CallControlCall) extensionConn.provider.createCall();
                newCall.connect(callerTerminal, callerAddress, targetExtension);
                
                System.out.println("[BLIND_TRANSFER] 備用方案成功: " + originalCaller + " → " + targetExtension);
                
                
                return "一段轉接成功：" + originalCaller + " 的通話已轉接到分機 " + targetExtension + "（使用備用方法）";
                
            } catch (Exception e2) {
                System.out.println("[BLIND_TRANSFER] 備用方案也失敗: " + e2.getMessage());
                throw new Exception("JTAPI transfer 失敗: " + e.getMessage() + ", 備用方法失敗: " + e2.getMessage());
            }
        }
    }
    
    /**
     * 方法3：使用會議通話然後退出的方法
     */
    private String blindTransferUsingConferenceMethod(String extension, String targetExtension, String originalCaller, Call activeCall, Object conn) throws Exception {
        System.out.println("[BLIND_TRANSFER] 嘗試使用 Conference 方法");
        
        var extensionConn = (PhoneCallService.ExtensionConnection) conn;
        
        // 1. 保持原始通話
        System.out.println("[BLIND_TRANSFER] 保持原始通話");
        Connection[] connections = activeCall.getConnections();
        for (Connection connection : connections) {
            if (connection.getAddress().getName().equals(extension)) {
                TerminalConnection[] termConns = connection.getTerminalConnections();
                for (TerminalConnection termConn : termConns) {
                    if (termConn instanceof CallControlTerminalConnection) {
                        CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                        ccTermConn.hold();
                        System.out.println("[BLIND_TRANSFER] 原始通話已保持");
                        break;
                    }
                }
                break;
            }
        }
        
        Thread.sleep(1000);
        
        // 2. 撥打給目標分機
        System.out.println("[BLIND_TRANSFER] 撥打給目標分機 " + targetExtension);
        CallControlCall consultCall = (CallControlCall) extensionConn.provider.createCall();
        consultCall.connect(extensionConn.terminal, extensionConn.address, targetExtension);
        
        Thread.sleep(2000);
        
        // 3. 建立會議通話
        System.out.println("[BLIND_TRANSFER] 建立三方會議");
        if (activeCall instanceof CallControlCall) {
            CallControlCall controlCall = (CallControlCall) activeCall;
            controlCall.conference(consultCall);
            
            Thread.sleep(1000);
            
            // 4. 轉接者退出會議
            System.out.println("[BLIND_TRANSFER] 轉接者退出會議");
            Connection[] conferenceConnections = controlCall.getConnections();
            for (Connection connection : conferenceConnections) {
                if (connection.getAddress().getName().equals(extension)) {
                    connection.disconnect();
                    System.out.println("[BLIND_TRANSFER] 轉接者已退出，轉接完成");
                    break;
                }
            }
            
            
            return "一段轉接成功：" + originalCaller + " 的通話已轉接到分機 " + targetExtension + "（使用 conference 方法）";
        } else {
            throw new Exception("通話不支援會議功能");
        }
    }
}
