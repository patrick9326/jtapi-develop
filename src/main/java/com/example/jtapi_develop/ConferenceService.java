package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.telephony.*;
import javax.telephony.callcontrol.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConferenceService {
    
    @Autowired
    private PhoneCallService phoneCallService;
    
    // 會議會話管理 - 改為 public 讓 Controller 可以存取
    public static class ConferenceSession {
        public String hostExtension;              // 會議主持人
        public String invitedExtension;          // 被邀請的參與者
        public Call originalCall;                // 原始通話 (A ↔ Host)
        public Call consultCall;                 // 諮詢通話 (Host ↔ C)
        public Call conferenceCall;              // 會議通話 (A + Host + C)
        public long startTime;
        public String sessionId;
        public boolean isActive;                 // 會議是否進行中
        public List<String> participants;       // 會議參與者列表
        
        public ConferenceSession(String host, String invited) {
            this.hostExtension = host;
            this.invitedExtension = invited;
            this.startTime = System.currentTimeMillis();
            this.sessionId = host + "_conf_" + System.currentTimeMillis();
            this.isActive = false;
            this.participants = new ArrayList<>();
        }
    }
    
    // 改為 public 讓 Controller 可以存取
    public final ConcurrentHashMap<String, ConferenceSession> activeSessions = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, String> extensionToSessionMap = new ConcurrentHashMap<>();
    
    /**
     * 發起三方通話 - 步驟1：邀請第三方加入
     */
    public String startConference(String hostExtension, String invitedExtension) {
        try {
            System.out.println("[CONFERENCE] 開始三方通話: " + hostExtension + " 邀請 " + invitedExtension);
            
            // 檢查是否已有會議進行中
            if (extensionToSessionMap.containsKey(hostExtension)) {
                return "錯誤：分機 " + hostExtension + " 已有進行中的會議";
            }
            
            // 1. 取得主持人連線
            var conn = phoneCallService.getExtensionConnection(hostExtension);
            if (conn == null) {
                return "錯誤：分機 " + hostExtension + " 未登入或連線不可用";
            }
            
            // 2. 找到原始通話（Host ↔ A）
            Call originalCall = findActiveCall(hostExtension, conn);
            if (originalCall == null) {
                return "錯誤：分機 " + hostExtension + " 沒有活躍的通話可以建立會議";
            }
            
            // 找到原始通話的另一方參與者
            String originalParticipant = null;
            Connection[] connections = originalCall.getConnections();
            for (Connection connection : connections) {
                String addressName = connection.getAddress().getName();
                if (!addressName.equals(hostExtension) && !addressName.startsWith("49")) {
                    originalParticipant = addressName;
                    break;
                }
            }
            
            if (originalParticipant == null) {
                return "錯誤：無法識別原始通話的參與者";
            }
            
            // 3. 保持原始通話（A 進入等待）
            if (originalCall instanceof CallControlCall) {
                Connection[] origConnections = originalCall.getConnections();
                for (Connection connection : origConnections) {
                    if (connection.getAddress().getName().equals(hostExtension)) {
                        TerminalConnection[] termConns = connection.getTerminalConnections();
                        for (TerminalConnection termConn : termConns) {
                            if (termConn instanceof CallControlTerminalConnection) {
                                CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                                ccTermConn.hold();
                                System.out.println("[CONFERENCE] 原始通話已保持，" + originalParticipant + " 進入等待");
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            
            // 4. 撥打給被邀請者（Host ↔ C）
            System.out.println("[CONFERENCE] 撥打給被邀請者 " + invitedExtension);
            Call consultCall = conn.provider.createCall();
            consultCall.connect(conn.terminal, conn.address, invitedExtension);
            
            // 5. 建立會議會話記錄
            ConferenceSession session = new ConferenceSession(hostExtension, invitedExtension);
            session.originalCall = originalCall;
            session.consultCall = consultCall;
            session.participants.add(originalParticipant);
            session.participants.add(hostExtension);
            activeSessions.put(session.sessionId, session);
            extensionToSessionMap.put(hostExtension, session.sessionId);
            
            System.out.println("[CONFERENCE] 會議會話已建立，會話ID: " + session.sessionId);
            return "三方通話邀請已發送：\n" +
                   "原始參與者: " + originalParticipant + " (等待中)\n" +
                   "會議主持人: " + hostExtension + "\n" +
                   "被邀請者: " + invitedExtension + " (撥打中)\n" +
                   "會話ID: " + session.sessionId + "\n" +
                   "提示：等待被邀請者接聽後，調用建立會議 API";
            
        } catch (Exception e) {
            System.err.println("[CONFERENCE] 開始會議失敗: " + e.getMessage());
            e.printStackTrace();
            extensionToSessionMap.remove(hostExtension);
            return "三方通話開始失敗: " + e.getMessage();
        }
    }
    
    /**
     * 建立三方會議 - 步驟2：所有人加入會議 (修正版本)
     */
    public String establishConference(String hostExtension) {
        StringBuilder debugInfo = new StringBuilder();
        
        try {
            debugInfo.append("=== AVAYA 會議建立修正版 ===\n");
            
            String sessionId = extensionToSessionMap.get(hostExtension);
            if (sessionId == null) {
                return "錯誤：分機 " + hostExtension + " 沒有進行中的會議邀請";
            }
            
            ConferenceSession session = activeSessions.get(sessionId);
            if (session == null) {
                return "錯誤：找不到會議會話";
            }
            
            debugInfo.append("會話ID: ").append(sessionId).append("\n");
            debugInfo.append("轉接者: ").append(session.hostExtension).append("\n");
            debugInfo.append("目標: ").append(session.invitedExtension).append("\n");
            
            // 檢查通話狀態
            if (session.originalCall == null || session.consultCall == null) {
                debugInfo.append("錯誤：通話狀態異常\n");
                return debugInfo.toString();
            }
            
            CallControlCall originalControlCall = (CallControlCall) session.originalCall;
            CallControlCall consultControlCall = (CallControlCall) session.consultCall;
            
            debugInfo.append("原始通話類型: ").append(originalControlCall.getClass().getSimpleName()).append("\n");
            debugInfo.append("諮詢通話類型: ").append(consultControlCall.getClass().getSimpleName()).append("\n");
            
            // 檢查並確保兩個通話都處於正確狀態
            debugInfo.append("\n--- 通話狀態檢查與修正 ---\n");
            
            // 檢查原始通話狀態
            Connection[] originalConnections = originalControlCall.getConnections();
            debugInfo.append("原始通話連線數: ").append(originalConnections.length).append("\n");
            
            boolean hostInOriginalCall = false;
            TerminalConnection hostOriginalTermConn = null;
            
            for (Connection conn : originalConnections) {
                debugInfo.append("原始通話連線: ").append(conn.getAddress().getName())
                         .append(" (").append(getConnectionStateName(conn.getState())).append(")\n");
                
                if (conn.getAddress().getName().equals(hostExtension)) {
                    hostInOriginalCall = true;
                    TerminalConnection[] termConns = conn.getTerminalConnections();
                    if (termConns.length > 0) {
                        hostOriginalTermConn = termConns[0];
                        if (hostOriginalTermConn instanceof CallControlTerminalConnection) {
                            CallControlTerminalConnection cctc = (CallControlTerminalConnection) hostOriginalTermConn;
                            debugInfo.append("Host 在原始通話的狀態: ")
                                     .append(getCallControlStateName(cctc.getCallControlState())).append("\n");
                        }
                    }
                }
            }
            
            // 檢查諮詢通話狀態
            Connection[] consultConnections = consultControlCall.getConnections();
            debugInfo.append("諮詢通話連線數: ").append(consultConnections.length).append("\n");
            
            boolean hostInConsultCall = false;
            boolean targetConnected = false;
            
            for (Connection conn : consultConnections) {
                debugInfo.append("諮詢通話連線: ").append(conn.getAddress().getName())
                         .append(" (").append(getConnectionStateName(conn.getState())).append(")\n");
                
                if (conn.getAddress().getName().equals(hostExtension)) {
                    hostInConsultCall = true;
                }
                if (conn.getAddress().getName().equals(session.invitedExtension) && 
                    conn.getState() == Connection.CONNECTED) {
                    targetConnected = true;
                }
            }
            
            // 狀態驗證
            if (!hostInOriginalCall) {
                debugInfo.append("❌ Host 不在原始通話中\n");
                return debugInfo.toString();
            }
            if (!hostInConsultCall) {
                debugInfo.append("❌ Host 不在諮詢通話中\n");
                return debugInfo.toString();
            }
            if (!targetConnected) {
                debugInfo.append("❌ 目標分機未連線到諮詢通話\n");
                return debugInfo.toString();
            }
            
            debugInfo.append("✅ 所有參與者狀態正常\n");
            
            // 準備會議：調整通話狀態
            debugInfo.append("\n--- 準備會議：調整通話狀態 ---\n");
            
            // 如果原始通話是 HELD 狀態，先恢復它
            if (hostOriginalTermConn instanceof CallControlTerminalConnection) {
                CallControlTerminalConnection cctc = (CallControlTerminalConnection) hostOriginalTermConn;
                int currentState = cctc.getCallControlState();
                
                if (currentState == CallControlTerminalConnection.HELD) {
                    debugInfo.append("原始通話處於 HELD 狀態，先恢復\n");
                    try {
                        cctc.unhold();
                        Thread.sleep(1000); // 等待狀態變更
                        debugInfo.append("✅ 原始通話已恢復\n");
                    } catch (Exception e) {
                        debugInfo.append("⚠️ 恢復原始通話時發生錯誤: ").append(e.getMessage()).append("\n");
                    }
                } else {
                    debugInfo.append("原始通話狀態: ").append(getCallControlStateName(currentState)).append("\n");
                }
            }
            
            // 執行會議建立
            debugInfo.append("\n--- 執行會議建立 ---\n");
            
            try {
                // 方法1：標準 AVAYA 會議方式
                debugInfo.append("嘗試方法1: 標準 AVAYA 會議建立\n");
                
                // 確保兩個通話都是活躍狀態
                debugInfo.append("確認通話狀態...\n");
                
                if (originalControlCall.getState() == Call.INVALID) {
                    throw new Exception("原始通話已失效");
                }
                
                if (consultControlCall.getState() == Call.INVALID) {
                    throw new Exception("諮詢通話已失效");
                }
                
                debugInfo.append("兩個通話都有效，開始建立會議\n");
                
                // 執行會議建立
                originalControlCall.conference(consultControlCall);
                
                debugInfo.append("✅ 會議建立成功！\n");
                
                // 等待會議穩定
                Thread.sleep(1500);
                
                // 更新會議狀態
                session.conferenceCall = originalControlCall;
                session.isActive = true;
                session.participants.add(session.invitedExtension);
                
                // 驗證會議狀態
                Connection[] conferenceConnections = originalControlCall.getConnections();
                debugInfo.append("會議建立後連線數: ").append(conferenceConnections.length).append("\n");
                
                for (Connection conn : conferenceConnections) {
                    debugInfo.append("會議參與者: ").append(conn.getAddress().getName())
                             .append(" (").append(getConnectionStateName(conn.getState())).append(")\n");
                }
                
                debugInfo.append("=== 會議建立完成 ===\n");
                return debugInfo.toString() + "\n🎉 三方會議建立成功！所有參與者已加入會議。";
                
            } catch (Exception conferenceError) {
                debugInfo.append("方法1失敗: ").append(conferenceError.getMessage()).append("\n");
                
                // 方法2：嘗試相反方向的會議建立
                try {
                    debugInfo.append("\n嘗試方法2: 使用諮詢通話作為基礎\n");
                    consultControlCall.conference(originalControlCall);
                    
                    session.conferenceCall = consultControlCall;
                    session.isActive = true;
                    session.participants.add(session.invitedExtension);
                    
                    debugInfo.append("✅ 方法2成功！\n");
                    return debugInfo.toString() + "\n🎉 三方會議建立成功（方法2）！";
                    
                } catch (Exception method2Error) {
                    debugInfo.append("方法2也失敗: ").append(method2Error.getMessage()).append("\n");
                    
                    // 詳細的錯誤分析
                    debugInfo.append("\n--- 詳細錯誤分析 ---\n");
                    debugInfo.append("原始錯誤類型: ").append(conferenceError.getClass().getSimpleName()).append("\n");
                    debugInfo.append("錯誤訊息: ").append(conferenceError.getMessage()).append("\n");
                    
                    // 檢查是否為特定的 CSTA 錯誤
                    if (conferenceError.getMessage().contains("CSTA Error: 33")) {
                        debugInfo.append("\n🔍 CSTA Error 33 深度分析：\n");
                        debugInfo.append("- 不是權限問題（基本通話功能正常）\n");
                        debugInfo.append("- 可能是 AVAYA 系統的特定限制\n");
                        debugInfo.append("- 建議：檢查系統 DSP 資源或會議功能設定\n");
                        debugInfo.append("- 或者該系統只支援硬體會議，不支援軟體會議\n");
                    }
                    
                    return debugInfo.toString() + 
                           "\n❌ 所有會議建立方法都失敗。建議使用替代方案或檢查系統設定。";
                }
            }
            
        } catch (Exception e) {
            debugInfo.append("會議建立過程發生嚴重錯誤: ").append(e.getMessage()).append("\n");
            return debugInfo.toString();
        }
    }
    
    /**
     * 結束會議
     */
    public String endConference(String hostExtension) {
        try {
            String sessionId = extensionToSessionMap.get(hostExtension);
            if (sessionId == null) {
                return "錯誤：分機 " + hostExtension + " 沒有進行中的會議";
            }
            
            ConferenceSession session = activeSessions.remove(sessionId);
            if (session == null) {
                return "錯誤：找不到會議會話";
            }
            
            System.out.println("[CONFERENCE] 結束會議，會話ID: " + sessionId);
            
            // 掛斷會議通話
            if (session.conferenceCall != null) {
                Connection[] connections = session.conferenceCall.getConnections();
                for (Connection conn : connections) {
                    if (conn.getState() != Connection.DISCONNECTED) {
                        conn.disconnect();
                    }
                }
                System.out.println("[CONFERENCE] 會議通話已結束");
            }
            
            // 掛斷諮詢通話（如果還存在）
            if (session.consultCall != null && session.consultCall != session.conferenceCall) {
                Connection[] consultConnections = session.consultCall.getConnections();
                for (Connection conn : consultConnections) {
                    if (conn.getState() != Connection.DISCONNECTED) {
                        conn.disconnect();
                    }
                }
            }
            
            // 清理映射
            extensionToSessionMap.remove(hostExtension);
            
            return "會議已結束，所有參與者已斷線";
            
        } catch (Exception e) {
            System.err.println("[CONFERENCE] 結束會議失敗: " + e.getMessage());
            e.printStackTrace();
            return "結束會議失敗: " + e.getMessage();
        }
    }
    
    /**
     * 主持人退出會議（讓其他人繼續通話）
     */
    public String leaveConference(String hostExtension) {
        try {
            String sessionId = extensionToSessionMap.get(hostExtension);
            if (sessionId == null) {
                return "錯誤：分機 " + hostExtension + " 沒有進行中的會議";
            }
            
            ConferenceSession session = activeSessions.get(sessionId);
            if (session == null || !session.isActive) {
                return "錯誤：會議未處於活躍狀態";
            }
            
            System.out.println("[CONFERENCE] 主持人退出會議: " + hostExtension);
            
            // 主持人從會議中退出
            if (session.conferenceCall != null) {
                Connection[] connections = session.conferenceCall.getConnections();
                for (Connection connection : connections) {
                    if (connection.getAddress().getName().equals(hostExtension)) {
                        connection.disconnect();
                        System.out.println("[CONFERENCE] 主持人已退出會議");
                        break;
                    }
                }
            }
            
            // 清理主持人的映射，但保留會話
            extensionToSessionMap.remove(hostExtension);
            session.participants.remove(hostExtension);
            
            return "主持人已退出會議，其他參與者繼續通話\n" +
                   "剩餘參與者: " + String.join(", ", session.participants);
            
        } catch (Exception e) {
            System.err.println("[CONFERENCE] 主持人退出失敗: " + e.getMessage());
            e.printStackTrace();
            return "退出會議失敗: " + e.getMessage();
        }
    }
    
    /**
     * 取消會議邀請（在建立會議前）
     */
    public String cancelConferenceInvitation(String hostExtension) {
        try {
            String sessionId = extensionToSessionMap.get(hostExtension);
            if (sessionId == null) {
                return "錯誤：分機 " + hostExtension + " 沒有進行中的會議邀請";
            }
            
            ConferenceSession session = activeSessions.remove(sessionId);
            if (session == null) {
                return "錯誤：找不到會議會話";
            }
            
            System.out.println("[CONFERENCE] 取消會議邀請，會話ID: " + sessionId);
            
            // 掛斷諮詢通話
            if (session.consultCall != null) {
                Connection[] consultConnections = session.consultCall.getConnections();
                for (Connection conn : consultConnections) {
                    if (conn.getState() != Connection.DISCONNECTED) {
                        conn.disconnect();
                    }
                }
                System.out.println("[CONFERENCE] 邀請通話已掛斷");
            }
            
            // 恢復原始通話
            if (session.originalCall != null) {
                Connection[] originalConnections = session.originalCall.getConnections();
                for (Connection connection : originalConnections) {
                    if (connection.getAddress().getName().equals(hostExtension)) {
                        TerminalConnection[] termConns = connection.getTerminalConnections();
                        for (TerminalConnection termConn : termConns) {
                            if (termConn instanceof CallControlTerminalConnection) {
                                CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                                if (ccTermConn.getCallControlState() == CallControlTerminalConnection.HELD) {
                                    ccTermConn.unhold();
                                    System.out.println("[CONFERENCE] 原始通話已恢復");
                                    break;
                                }
                            }
                        }
                        break;
                    }
                }
            }
            
            // 清理映射
            extensionToSessionMap.remove(hostExtension);
            
            return "會議邀請已取消，原始通話已恢復";
            
        } catch (Exception e) {
            System.err.println("[CONFERENCE] 取消邀請失敗: " + e.getMessage());
            e.printStackTrace();
            return "取消邀請失敗: " + e.getMessage();
        }
    }
    
    /**
     * 查看會議狀態
     */
    public String getConferenceStatus() {
        if (activeSessions.isEmpty()) {
            return "目前沒有活躍的會議";
        }
        
        StringBuilder status = new StringBuilder("活躍的會議:\n");
        for (ConferenceSession session : activeSessions.values()) {
            long duration = (System.currentTimeMillis() - session.startTime) / 1000;
            status.append("會話ID: ").append(session.sessionId).append("\n");
            status.append("主持人: ").append(session.hostExtension).append("\n");
            status.append("狀態: ").append(session.isActive ? "會議進行中" : "邀請中").append("\n");
            status.append("持續時間: ").append(duration).append("秒\n");
            
            if (!session.participants.isEmpty()) {
                status.append("參與者: ").append(String.join(", ", session.participants)).append("\n");
            }
            if (session.invitedExtension != null) {
                status.append("被邀請者: ").append(session.invitedExtension).append("\n");
            }
            status.append("---\n");
        }
        return status.toString();
    }
    
    /**
     * 根據分機號查看會議狀態
     */
    public String getConferenceStatusByExtension(String extension) {
        String sessionId = extensionToSessionMap.get(extension);
        if (sessionId == null) {
            return "分機 " + extension + " 沒有進行中的會議";
        }
        
        ConferenceSession session = activeSessions.get(sessionId);
        if (session == null) {
            extensionToSessionMap.remove(extension);
            return "分機 " + extension + " 沒有進行中的會議";
        }
        
        long duration = (System.currentTimeMillis() - session.startTime) / 1000;
        StringBuilder status = new StringBuilder();
        status.append("分機 ").append(extension).append(" 的會議狀態:\n");
        status.append("會話ID: ").append(session.sessionId).append("\n");
        status.append("主持人: ").append(session.hostExtension).append("\n");
        status.append("狀態: ").append(session.isActive ? "會議進行中" : "邀請中").append("\n");
        status.append("持續時間: ").append(duration).append("秒\n");
        
        if (!session.participants.isEmpty()) {
            status.append("參與者: ").append(String.join(", ", session.participants)).append("\n");
        }
        if (session.invitedExtension != null) {
            status.append("被邀請者: ").append(session.invitedExtension).append("\n");
        }
        
        return status.toString();
    }
    
    /**
     * 會議前的完整狀態檢查
     */
    public String validateConferenceReadiness(String hostExtension) {
        try {
            String sessionId = extensionToSessionMap.get(hostExtension);
            if (sessionId == null) {
                return "沒有進行中的會議邀請";
            }
            
            ConferenceSession session = activeSessions.get(sessionId);
            if (session == null) {
                return "會議會話不存在";
            }
            
            StringBuilder status = new StringBuilder();
            status.append("=== 會議準備狀態完整檢查 ===\n");
            
            // 檢查原始通話
            if (session.originalCall != null) {
                status.append("✅ 原始通話存在\n");
                status.append("原始通話狀態: ").append(session.originalCall.getState()).append("\n");
                
                Connection[] origConns = session.originalCall.getConnections();
                for (Connection conn : origConns) {
                    status.append("- ").append(conn.getAddress().getName())
                          .append(" (").append(getConnectionStateName(conn.getState())).append(")\n");
                }
            } else {
                status.append("❌ 原始通話不存在\n");
            }
            
            // 檢查諮詢通話
            if (session.consultCall != null) {
                status.append("✅ 諮詢通話存在\n");
                status.append("諮詢通話狀態: ").append(session.consultCall.getState()).append("\n");
                
                Connection[] consultConns = session.consultCall.getConnections();
                for (Connection conn : consultConns) {
                    status.append("- ").append(conn.getAddress().getName())
                          .append(" (").append(getConnectionStateName(conn.getState())).append(")\n");
                }
            } else {
                status.append("❌ 諮詢通話不存在\n");
            }
            
            // 給出建議
            status.append("\n建議：\n");
            if (session.originalCall != null && session.consultCall != null) {
                status.append("✅ 準備就緒，可以嘗試建立會議\n");
            } else {
                status.append("❌ 狀態不完整，請重新開始會議流程\n");
            }
            
            return status.toString();
            
        } catch (Exception e) {
            return "狀態檢查失敗: " + e.getMessage();
        }
    }
    
    /**
     * 清理過期會議（超過30分鐘自動清理）
     */
    public void cleanupExpiredConferences() {
        long currentTime = System.currentTimeMillis();
        long thirtyMinutes = 30 * 60 * 1000; // 30分鐘
        
        activeSessions.entrySet().removeIf(entry -> {
            ConferenceSession session = entry.getValue();
            if (currentTime - session.startTime > thirtyMinutes) {
                System.out.println("[CONFERENCE_CLEANUP] 清理過期會議: " + entry.getKey());
                try {
                    endConference(session.hostExtension);
                    extensionToSessionMap.remove(session.hostExtension);
                } catch (Exception e) {
                    System.err.println("[CONFERENCE_CLEANUP] 清理會議時發生錯誤: " + e.getMessage());
                }
                return true;
            }
            return false;
        });
    }
    
    // === 輔助方法 ===
    
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
            System.err.println("[CONFERENCE] 找尋活躍通話時發生錯誤: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 輔助方法：取得連線狀態名稱
     */
    public String getConnectionStateName(int state) {
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
    public String getTerminalConnectionStateName(int state) {
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
    public String getCallControlStateName(int state) {
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
}