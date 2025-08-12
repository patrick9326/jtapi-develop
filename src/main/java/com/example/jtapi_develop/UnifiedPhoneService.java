package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.telephony.*;
import javax.telephony.callcontrol.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * 統一話機服務 - 模擬真實IP話機的所有功能
 * 整合：基本通話、多線、轉接、三方通話
 */
@Service
public class UnifiedPhoneService {
    
    @Autowired
    private PhoneCallService phoneCallService;
    
    /**
     * 話機線路狀態
     */
    public enum LineState {
        IDLE,           // 空閒
        RINGING,        // 響鈴
        TALKING,        // 通話中
        HELD,           // 保持
        CONFERENCING,   // 會議中
        TRANSFERRING    // 轉接中
    }
    
    /**
     * 通話線路類（模擬話機上的一條線路）
     */
    public static class PhoneLine {
        public String lineId;
        public Call call;
        public String remoteParty;
        public LineState state;
        public long startTime;
        public boolean isIncoming;
        
        // 特殊狀態標記
        public boolean isConference = false;      // 是否為會議通話
        public boolean isTransferring = false;    // 是否正在轉接
        public String transferTarget = null;      // 轉接目標
        
        public PhoneLine(String lineId, Call call, String remoteParty, boolean isIncoming) {
            this.lineId = lineId;
            this.call = call;
            this.remoteParty = remoteParty;
            this.isIncoming = isIncoming;
            this.startTime = System.currentTimeMillis();
            this.state = LineState.RINGING;
        }
        
        public String getDisplayName() {
            String direction = isIncoming ? "來電" : "撥出";
            String status = "";
            
            if (isConference) status = " [會議]";
            if (isTransferring) status = " [轉接中→" + transferTarget + "]";
            
            return direction + ": " + remoteParty + status;
        }
    }
    
    /**
     * 話機狀態（每個分機一個）
     */
    public static class PhoneState {
        public String extension;
        public List<PhoneLine> lines;           // 所有線路
        public String activeLine;              // 當前活躍線路
        public int maxLines = 6;               // 最大線路數
        private int nextLineNumber = 1;
        
        public PhoneState(String extension) {
            this.extension = extension;
            this.lines = new ArrayList<>();
        }
        
        public String generateLineId() {
            return extension + "_L" + (nextLineNumber++);
        }
        
        public PhoneLine findLine(String lineId) {
            return lines.stream().filter(l -> l.lineId.equals(lineId)).findFirst().orElse(null);
        }
        
        public PhoneLine getActiveLine() {
            return lines.stream().filter(l -> l.lineId.equals(activeLine)).findFirst().orElse(null);
        }
        
        public List<PhoneLine> getHeldLines() {
            return lines.stream().filter(l -> l.state == LineState.HELD).collect(java.util.stream.Collectors.toList());
        }
        
        public int getActiveLineCount() {
            return (int) lines.stream().filter(l -> l.state != LineState.IDLE).count();
        }
    }
    
    // 每個分機的話機狀態
    private final ConcurrentHashMap<String, PhoneState> phoneStates = new ConcurrentHashMap<>();
    
    // ========================================
    // 基本通話功能（像話機上的接聽/掛斷鍵）
    // ========================================
    
    /**
     * 接聽來電（綠色接聽鍵）
     */
    public String answerCall(String extension) {
        try {
            PhoneState phone = getOrCreatePhone(extension);
            
            // 找響鈴的線路
            PhoneLine ringingLine = phone.lines.stream()
                .filter(l -> l.state == LineState.RINGING)
                .findFirst().orElse(null);
                
            if (ringingLine == null) {
                // 檢查是否有新來電
                ringingLine = detectIncomingCall(extension, phone);
            }
            
            if (ringingLine == null) {
                return "沒有來電可接聽";
            }
            
            // Hold其他活躍線路
            holdOtherLines(phone, ringingLine.lineId);
            
            // 接聽
            answerLine(extension, ringingLine);
            ringingLine.state = LineState.TALKING;
            phone.activeLine = ringingLine.lineId;
            
            return "線路 " + ringingLine.lineId + " 接聽：" + ringingLine.getDisplayName();
            
        } catch (Exception e) {
            return "接聽失敗: " + e.getMessage();
        }
    }
    
    /**
     * 撥打電話（輸入號碼後按撥號鍵）
     */
    public String makeCall(String extension, String target) {
        try {
            PhoneState phone = getOrCreatePhone(extension);
            
            if (phone.getActiveLineCount() >= phone.maxLines) {
                return "已達線路上限";
            }
            
            // Hold當前活躍線路
            PhoneLine currentActive = phone.getActiveLine();
            if (currentActive != null) {
                holdLine(extension, currentActive);
            }
            
            // 建立新線路
            String lineId = phone.generateLineId();
            Call newCall = createCall(extension, target);
            
            PhoneLine newLine = new PhoneLine(lineId, newCall, target, false);
            newLine.state = LineState.TALKING;
            phone.lines.add(newLine);
            phone.activeLine = lineId;
            
            return "線路 " + lineId + " 撥打：" + newLine.getDisplayName();
            
        } catch (Exception e) {
            return "撥打失敗: " + e.getMessage();
        }
    }
    
    /**
     * 掛斷當前線路（紅色掛斷鍵）
     */
    public String hangupCurrentLine(String extension) {
        try {
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            PhoneLine activeLine = phone.getActiveLine();
            if (activeLine == null) return "沒有活躍線路";
            
            // 掛斷
            disconnectLine(extension, activeLine);
            activeLine.state = LineState.IDLE;
            phone.lines.remove(activeLine);
            
            // 自動切換到下一條線路
            PhoneLine nextLine = phone.getHeldLines().stream().findFirst().orElse(null);
            if (nextLine != null) {
                unholdLine(extension, nextLine);
                phone.activeLine = nextLine.lineId;
                return "線路 " + activeLine.lineId + " 已掛斷，切換到 " + nextLine.lineId;
            } else {
                phone.activeLine = null;
                return "線路 " + activeLine.lineId + " 已掛斷";
            }
            
        } catch (Exception e) {
            return "掛斷失敗: " + e.getMessage();
        }
    }
    
    // ========================================
    // 多線功能（像話機上的線路切換鍵）
    // ========================================
    
    /**
     * Hold當前線路（Hold鍵）
     */
    public String holdCurrentLine(String extension) {
        try {
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            PhoneLine activeLine = phone.getActiveLine();
            if (activeLine == null) return "沒有活躍線路";
            
            holdLine(extension, activeLine);
            activeLine.state = LineState.HELD;
            phone.activeLine = null;
            
            return "線路 " + activeLine.lineId + " 已保持";
            
        } catch (Exception e) {
            return "Hold失敗: " + e.getMessage();
        }
    }
    
    /**
     * 切換到指定線路（線路選擇鍵）
     */
    public String switchToLine(String extension, String lineId) {
        try {
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            PhoneLine targetLine = phone.findLine(lineId);
            if (targetLine == null) return "線路不存在";
            
            // Hold當前活躍線路
            PhoneLine currentActive = phone.getActiveLine();
            if (currentActive != null && !currentActive.lineId.equals(lineId)) {
                holdLine(extension, currentActive);
                currentActive.state = LineState.HELD;
            }
            
            // 激活目標線路
            if (targetLine.state == LineState.HELD) {
                unholdLine(extension, targetLine);
            }
            targetLine.state = LineState.TALKING;
            phone.activeLine = lineId;
            
            return "已切換到線路 " + lineId + ": " + targetLine.getDisplayName();
            
        } catch (Exception e) {
            return "切換失敗: " + e.getMessage();
        }
    }
    
    /**
     * 快速切換（像話機的Flash鍵，在兩條線路間切換）
     */
    public String flashSwitch(String extension) {
        try {
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            List<PhoneLine> availableLines = phone.lines.stream()
                .filter(l -> l.state == LineState.TALKING || l.state == LineState.HELD)
                .collect(java.util.stream.Collectors.toList());
                
            if (availableLines.size() < 2) {
                return "需要至少兩條線路才能切換";
            }
            
            // 找到非當前活躍的線路
            PhoneLine targetLine = availableLines.stream()
                .filter(l -> !l.lineId.equals(phone.activeLine))
                .findFirst().orElse(null);
                
            if (targetLine != null) {
                return switchToLine(extension, targetLine.lineId);
            }
            
            return "無法切換";
            
        } catch (Exception e) {
            return "Flash切換失敗: " + e.getMessage();
        }
    }
    
    // ========================================
    // 轉接功能（像話機的Transfer鍵）
    // ========================================
    
    /**
     * 開始轉接（Transfer鍵 + 撥號）
     */
    public String startTransfer(String extension, String target) {
        try {
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            PhoneLine activeLine = phone.getActiveLine();
            if (activeLine == null) return "沒有活躍線路可轉接";
            
            // 標記為轉接狀態
            activeLine.isTransferring = true;
            activeLine.transferTarget = target;
            
            // Hold當前線路
            holdLine(extension, activeLine);
            activeLine.state = LineState.HELD;
            
            // 撥打給轉接目標
            String lineId = phone.generateLineId();
            Call consultCall = createCall(extension, target);
            
            PhoneLine consultLine = new PhoneLine(lineId, consultCall, target, false);
            consultLine.state = LineState.TALKING;
            phone.lines.add(consultLine);
            phone.activeLine = lineId;
            
            return "轉接開始：正在連接 " + target + "，完成後按 Transfer 鍵";
            
        } catch (Exception e) {
            return "轉接開始失敗: " + e.getMessage();
        }
    }
    
    /**
     * 完成轉接（再按一次Transfer鍵）
     */
    public String completeTransfer(String extension) {
        try {
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            // 找轉接中的線路
            PhoneLine transferringLine = phone.lines.stream()
                .filter(l -> l.isTransferring)
                .findFirst().orElse(null);
                
            if (transferringLine == null) return "沒有進行中的轉接";
            
            PhoneLine consultLine = phone.getActiveLine();
            if (consultLine == null) return "沒有諮詢線路";
            
            // 執行轉接
            try {
                if (transferringLine.call instanceof CallControlCall && 
                    consultLine.call instanceof CallControlCall) {
                    CallControlCall heldCall = (CallControlCall) transferringLine.call;
                    CallControlCall consultCall = (CallControlCall) consultLine.call;
                    
                    // 嘗試轉接
                    consultCall.transfer(heldCall);
                    
                    // 清理線路
                    phone.lines.remove(transferringLine);
                    phone.lines.remove(consultLine);
                    phone.activeLine = null;
                    
                    return "轉接完成：" + transferringLine.remoteParty + " → " + consultLine.remoteParty;
                }
            } catch (Exception e) {
                // 轉接失敗，恢復狀態
                transferringLine.isTransferring = false;
                transferringLine.transferTarget = null;
                return "轉接失敗: " + e.getMessage() + "，可按 Flash 鍵恢復通話";
            }
            
            return "轉接失敗";
            
        } catch (Exception e) {
            return "完成轉接失敗: " + e.getMessage();
        }
    }
    
    // ========================================
    // 三方通話功能（像話機的Conference鍵）
    // ========================================
    
    /**
     * 建立三方通話（Conference鍵）
     */
    public String startConference(String extension) {
        try {
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            List<PhoneLine> availableLines = phone.lines.stream()
                .filter(l -> l.state == LineState.TALKING || l.state == LineState.HELD)
                .collect(java.util.stream.Collectors.toList());
                
            if (availableLines.size() < 2) {
                return "需要至少兩條線路才能建立會議";
            }
            
            PhoneLine line1 = availableLines.get(0);
            PhoneLine line2 = availableLines.get(1);
            
            try {
                if (line1.call instanceof CallControlCall && 
                    line2.call instanceof CallControlCall) {
                    CallControlCall call1 = (CallControlCall) line1.call;
                    CallControlCall call2 = (CallControlCall) line2.call;
                    
                    // 建立會議
                    call1.conference(call2);
                    
                    // 標記為會議狀態
                    line1.isConference = true;
                    line1.state = LineState.CONFERENCING;
                    line2.isConference = true;
                    line2.state = LineState.CONFERENCING;
                    
                    phone.activeLine = line1.lineId;
                    
                    return "三方會議建立成功：" + extension + " + " + 
                           line1.remoteParty + " + " + line2.remoteParty;
                }
            } catch (Exception e) {
                return "會議建立失敗: " + e.getMessage();
            }
            
            return "會議建立失敗";
            
        } catch (Exception e) {
            return "建立會議失敗: " + e.getMessage();
        }
    }
    
    // ========================================
    // 話機狀態查看
    // ========================================
    
    /**
     * 顯示話機狀態（像話機LCD顯示屏）
     */
    public String getPhoneDisplay(String extension) {
        PhoneState phone = getOrCreatePhone(extension);
        
        // 自動檢測並導入現有通話
        detectAndImportExistingCalls(extension, phone);
        
        StringBuilder display = new StringBuilder();
        display.append("=== 話機 ").append(extension).append(" ===\n");
        
        if (phone.lines.isEmpty()) {
            display.append("所有線路空閒\n");
            return display.toString();
        }
        
        for (int i = 0; i < phone.lines.size(); i++) {
            PhoneLine line = phone.lines.get(i);
            String marker = line.lineId.equals(phone.activeLine) ? ">>> " : "    ";
            
            display.append(marker)
                   .append("L").append(i + 1).append(": ")
                   .append(getStateDisplay(line.state)).append(" ")
                   .append(line.getDisplayName()).append("\n");
        }
        
        return display.toString();
    }
    
    /**
     * 清理並刷新話機狀態
     */
    public String cleanupAndRefresh(String extension) {
        PhoneState phone = getOrCreatePhone(extension);
        
        // 完全清除現有線路
        int beforeCount = phone.lines.size();
        phone.lines.clear();
        phone.activeLine = null;
        
        // 重新檢測並導入
        detectAndImportExistingCalls(extension, phone);
        
        int afterCount = phone.lines.size();
        
        return "清理完成：移除 " + beforeCount + " 條舊線路，重新導入 " + afterCount + " 條線路\n" +
               getPhoneDisplay(extension);
    }
    
    /**
     * 手動導入現有通話
     */
    public String importExistingCalls(String extension) {
        PhoneState phone = getOrCreatePhone(extension);
        int beforeCount = phone.lines.size();
        
        detectAndImportExistingCalls(extension, phone);
        
        int afterCount = phone.lines.size();
        int importedCount = afterCount - beforeCount;
        
        if (importedCount > 0) {
            return "成功導入 " + importedCount + " 條現有通話\n" + getPhoneDisplay(extension);
        } else {
            return "沒有發現新的通話可導入\n" + getPhoneDisplay(extension);
        }
    }
    
    /**
     * 調試現有通話的原始信息
     */
    public String debugExistingCalls(String extension) {
        StringBuilder debug = new StringBuilder();
        debug.append("=== 調試通話信息 ").append(extension).append(" ===\n");
        
        try {
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return debug.append("無法取得分機連線").toString();
            }
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            if (extensionConn.terminal == null) {
                return debug.append("分機沒有Terminal").toString();
            }
            
            TerminalConnection[] termConnections = extensionConn.terminal.getTerminalConnections();
            if (termConnections == null || termConnections.length == 0) {
                return debug.append("沒有找到TerminalConnection").toString();
            }
            
            debug.append("找到 ").append(termConnections.length).append(" 個TerminalConnection:\n");
            
            for (int i = 0; i < termConnections.length; i++) {
                TerminalConnection termConn = termConnections[i];
                debug.append("\n--- TerminalConnection ").append(i + 1).append(" ---\n");
                debug.append("狀態: ").append(getTerminalConnectionStateName(termConn.getState())).append("\n");
                
                if (termConn instanceof CallControlTerminalConnection) {
                    CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                    debug.append("CallControl狀態: ").append(getCallControlStateName(ccTermConn.getCallControlState())).append("\n");
                }
                
                Call call = termConn.getConnection().getCall();
                debug.append("Call對象: ").append(call.getClass().getSimpleName()).append("\n");
                debug.append("Call狀態: ").append(call.getState()).append("\n");
                
                Connection[] connections = call.getConnections();
                debug.append("連線數量: ").append(connections.length).append("\n");
                
                for (int j = 0; j < connections.length; j++) {
                    Connection connection = connections[j];
                    debug.append("  連線 ").append(j + 1).append(": ")
                          .append(connection.getAddress().getName())
                          .append(" (").append(getConnectionStateName(connection.getState())).append(")\n");
                }
                
                // 判斷通話方向
                boolean isIncoming = determineCallDirection(call, extension);
                String remoteParty = findRemoteParty(call, extension);
                debug.append("判斷結果: ").append(isIncoming ? "來電" : "撥出")
                      .append(" 對方: ").append(remoteParty).append("\n");
            }
            
        } catch (Exception e) {
            debug.append("調試過程出錯: ").append(e.getMessage()).append("\n");
        }
        
        return debug.toString();
    }
    
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
    
    private String getStateDisplay(LineState state) {
        switch (state) {
            case RINGING: return "響鈴";
            case TALKING: return "通話";
            case HELD: return "保持";
            case CONFERENCING: return "會議";
            case TRANSFERRING: return "轉接";
            default: return "空閒";
        }
    }
    
    // ========================================
    // 輔助方法
    // ========================================
    
    private PhoneState getOrCreatePhone(String extension) {
        return phoneStates.computeIfAbsent(extension, PhoneState::new);
    }
    
    private PhoneLine detectIncomingCall(String extension, PhoneState phone) {
        try {
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) return null;
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            if (extensionConn.terminal != null) {
                TerminalConnection[] termConnections = extensionConn.terminal.getTerminalConnections();
                if (termConnections != null) {
                    for (TerminalConnection termConn : termConnections) {
                        if (termConn.getState() == TerminalConnection.RINGING) {
                            Call incomingCall = termConn.getConnection().getCall();
                            String remoteParty = findRemoteParty(incomingCall, extension);
                            
                            String lineId = phone.generateLineId();
                            PhoneLine newLine = new PhoneLine(lineId, incomingCall, remoteParty, true);
                            phone.lines.add(newLine);
                            return newLine;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略錯誤
        }
        return null;
    }
    
    /**
     * 檢測並導入現有的通話（重要！）
     */
    private void detectAndImportExistingCalls(String extension, PhoneState phone) {
        try {
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) return;
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            if (extensionConn.terminal == null) return;
            
            TerminalConnection[] termConnections = extensionConn.terminal.getTerminalConnections();
            if (termConnections == null) return;
            
            // 清理已斷開的線路
            cleanupDisconnectedLines(phone);
            
            // 收集當前實際的通話
            java.util.Set<String> currentCallIds = new java.util.HashSet<>();
            
            for (TerminalConnection termConn : termConnections) {
                if (termConn.getState() == TerminalConnection.DROPPED) {
                    continue; // 跳過已斷開的連線
                }
                
                Call existingCall = termConn.getConnection().getCall();
                String remoteParty = findRemoteParty(existingCall, extension);
                
                // 使用更可靠的通話標識：對方號碼+通話狀態
                String callId = remoteParty + "_" + termConn.getState();
                currentCallIds.add(callId);
                
                // 檢查是否已經存在相同的通話
                boolean alreadyExists = phone.lines.stream()
                    .anyMatch(line -> {
                        String existingCallId = line.remoteParty + "_" + getTerminalConnectionStateFromLineState(line.state);
                        return existingCallId.equals(callId);
                    });
                
                if (!alreadyExists) {
                    String lineId = phone.generateLineId();
                    boolean isIncoming = determineCallDirection(existingCall, extension);
                    
                    // 根據 TerminalConnection 狀態設定線路狀態
                    LineState lineState = mapTerminalConnectionToLineState(termConn);
                    
                    if (lineState != LineState.IDLE) {
                        PhoneLine importedLine = new PhoneLine(lineId, existingCall, remoteParty, isIncoming);
                        importedLine.state = lineState;
                        phone.lines.add(importedLine);
                        
                        // 設定活躍線路
                        if (lineState == LineState.TALKING && phone.activeLine == null) {
                            phone.activeLine = lineId;
                        }
                        
                        System.out.println("[UNIFIED_PHONE] 導入通話: " + lineId + 
                                         " 狀態: " + lineState + " 對方: " + remoteParty + 
                                         " 方向: " + (isIncoming ? "來電" : "撥出"));
                    }
                }
            }
            
            // 移除已經不存在的通話
            phone.lines.removeIf(line -> {
                String lineCallId = line.remoteParty + "_" + getTerminalConnectionStateFromLineState(line.state);
                return !currentCallIds.contains(lineCallId);
            });
            
        } catch (Exception e) {
            System.err.println("[UNIFIED_PHONE] 檢測現有通話失敗: " + e.getMessage());
        }
    }
    
    /**
     * 清理已斷開的線路
     */
    private void cleanupDisconnectedLines(PhoneState phone) {
        phone.lines.removeIf(line -> {
            try {
                if (line.call != null) {
                    // 檢查通話是否還有效
                    Connection[] connections = line.call.getConnections();
                    boolean hasActiveConnection = false;
                    
                    for (Connection conn : connections) {
                        if (conn.getState() != Connection.DISCONNECTED && 
                            conn.getState() != Connection.FAILED) {
                            hasActiveConnection = true;
                            break;
                        }
                    }
                    
                    if (!hasActiveConnection) {
                        System.out.println("[UNIFIED_PHONE] 清理斷開的線路: " + line.lineId);
                        return true; // 移除此線路
                    }
                }
            } catch (Exception e) {
                // 如果檢查過程出錯，也移除此線路
                System.out.println("[UNIFIED_PHONE] 清理異常線路: " + line.lineId);
                return true;
            }
            return false;
        });
        
        // 如果活躍線路被移除了，清除活躍線路標記
        if (phone.activeLine != null) {
            boolean activeLineExists = phone.lines.stream()
                .anyMatch(line -> line.lineId.equals(phone.activeLine));
            if (!activeLineExists) {
                phone.activeLine = null;
            }
        }
    }
    
    /**
     * 將 TerminalConnection 狀態映射到線路狀態
     */
    private LineState mapTerminalConnectionToLineState(TerminalConnection termConn) {
        switch (termConn.getState()) {
            case TerminalConnection.RINGING:
                return LineState.RINGING;
            case TerminalConnection.ACTIVE:
                return LineState.TALKING;
            case TerminalConnection.PASSIVE:
                // 檢查是否為 HELD 狀態
                if (termConn instanceof CallControlTerminalConnection) {
                    CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                    if (ccTermConn.getCallControlState() == CallControlTerminalConnection.HELD) {
                        return LineState.HELD;
                    }
                }
                return LineState.TALKING;
            default:
                return LineState.IDLE;
        }
    }
    
    /**
     * 從線路狀態反推 TerminalConnection 狀態（用於比較）
     */
    private int getTerminalConnectionStateFromLineState(LineState lineState) {
        switch (lineState) {
            case RINGING:
                return TerminalConnection.RINGING;
            case TALKING:
            case CONFERENCING:
                return TerminalConnection.ACTIVE;
            case HELD:
                return TerminalConnection.PASSIVE;
            default:
                return TerminalConnection.IDLE;
        }
    }
    
    /**
     * 判斷通話方向（修正版）
     */
    private boolean determineCallDirection(Call call, String localExtension) {
        try {
            // 方法1：檢查呼叫發起者
            Connection[] connections = call.getConnections();
            for (Connection connection : connections) {
                String addressName = connection.getAddress().getName();
                
                // 如果連線的發起者是本地分機，通常是撥出
                if (addressName.equals(localExtension)) {
                    // 檢查連線狀態來判斷方向
                    int state = connection.getState();
                    if (state == Connection.INPROGRESS) {
                        return false; // 撥出中
                    }
                }
            }
            
            // 方法2：簡單規則 - 如果對方是4位數分機號，且不是系統號碼，較可能是撥出
            String remoteParty = findRemoteParty(call, localExtension);
            if (remoteParty != null && remoteParty.matches("\\d{4}") && !remoteParty.startsWith("49")) {
                // 4位數分機，非系統號碼，假設為撥出
                return false;
            }
            
            // 預設為來電
            return true;
        } catch (Exception e) {
            return true; // 出錯時預設為來電
        }
    }
    
    private void holdOtherLines(PhoneState phone, String exceptLineId) {
        for (PhoneLine line : phone.lines) {
            if (!line.lineId.equals(exceptLineId) && line.state == LineState.TALKING) {
                try {
                    holdLine(phone.extension, line);
                    line.state = LineState.HELD;
                } catch (Exception e) {
                    // 忽略錯誤
                }
            }
        }
    }
    
    private void answerLine(String extension, PhoneLine line) throws Exception {
        // 實現接聽邏輯
        phoneCallService.answerCall(extension);
    }
    
    private Call createCall(String extension, String target) throws Exception {
        // 實現撥打邏輯
        var conn = phoneCallService.getExtensionConnection(extension);
        var extensionConn = (PhoneCallService.ExtensionConnection) conn;
        Call newCall = extensionConn.provider.createCall();
        newCall.connect(extensionConn.terminal, extensionConn.address, target);
        return newCall;
    }
    
    private void holdLine(String extension, PhoneLine line) throws Exception {
        // 實現Hold邏輯
        if (line.call != null) {
            Connection[] connections = line.call.getConnections();
            for (Connection connection : connections) {
                if (connection.getAddress().getName().equals(extension)) {
                    TerminalConnection[] termConns = connection.getTerminalConnections();
                    for (TerminalConnection termConn : termConns) {
                        if (termConn instanceof CallControlTerminalConnection) {
                            ((CallControlTerminalConnection) termConn).hold();
                            break;
                        }
                    }
                    break;
                }
            }
        }
    }
    
    private void unholdLine(String extension, PhoneLine line) throws Exception {
        // 實現Unhold邏輯
        if (line.call != null) {
            Connection[] connections = line.call.getConnections();
            for (Connection connection : connections) {
                if (connection.getAddress().getName().equals(extension)) {
                    TerminalConnection[] termConns = connection.getTerminalConnections();
                    for (TerminalConnection termConn : termConns) {
                        if (termConn instanceof CallControlTerminalConnection) {
                            CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                            if (ccTermConn.getCallControlState() == CallControlTerminalConnection.HELD) {
                                ccTermConn.unhold();
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        }
    }
    
    private void disconnectLine(String extension, PhoneLine line) throws Exception {
        // 實現掛斷邏輯
        if (line.call != null) {
            Connection[] connections = line.call.getConnections();
            for (Connection connection : connections) {
                if (connection.getAddress().getName().equals(extension)) {
                    connection.disconnect();
                    break;
                }
            }
        }
    }
    
    private String findRemoteParty(Call call, String localExtension) {
        try {
            Connection[] connections = call.getConnections();
            String bestMatch = null;
            
            for (Connection connection : connections) {
                String addressName = connection.getAddress().getName();
                
                // 跳過本地分機
                if (addressName.equals(localExtension)) {
                    continue;
                }
                
                // 優先選擇4位數分機號（不是系統號碼）
                if (addressName.matches("\\d{4}") && !addressName.startsWith("49")) {
                    return addressName; // 找到真正的分機號，立即返回
                }
                
                // 備選：任何非本地的號碼
                if (bestMatch == null) {
                    bestMatch = addressName;
                }
            }
            
            return bestMatch != null ? bestMatch : "未知";
        } catch (Exception e) {
            return "未知";
        }
    }
    public String unholdCall(String extension) {
    try {
        PhoneState phone = phoneStates.get(extension);
        if (phone == null) return "話機未初始化";
        
        // 找到被 Hold 的線路
        List<PhoneLine> heldLines = phone.getHeldLines();
        if (heldLines.isEmpty()) {
            return "沒有保持中的線路可以恢復";
        }
        
        // 恢復第一條被 Hold 的線路
        PhoneLine heldLine = heldLines.get(0);
        
        // 先 Hold 當前活躍線路（如果有的話）
        PhoneLine currentActive = phone.getActiveLine();
        if (currentActive != null && !currentActive.lineId.equals(heldLine.lineId)) {
            holdLine(extension, currentActive);
            currentActive.state = LineState.HELD;
        }
        
        // 恢復被 Hold 的線路
        unholdLine(extension, heldLine);
        heldLine.state = LineState.TALKING;
        phone.activeLine = heldLine.lineId;
        
        return "線路 " + heldLine.lineId + " 已恢復：" + heldLine.getDisplayName();
        
    } catch (Exception e) {
        return "恢復通話失敗: " + e.getMessage();
    }
}

/**
 * 一般的 Hold 功能（保持當前活躍通話）
 */
public String holdActiveCall(String extension) {
    try {
        PhoneState phone = phoneStates.get(extension);
        if (phone == null) return "話機未初始化";
        
        PhoneLine activeLine = phone.getActiveLine();
        if (activeLine == null) return "沒有活躍線路可以保持";
        
        if (activeLine.state != LineState.TALKING) {
            return "線路 " + activeLine.lineId + " 不是通話狀態，無法保持";
        }
        
        // 執行 Hold
        holdLine(extension, activeLine);
        activeLine.state = LineState.HELD;
        phone.activeLine = null;
        
        return "線路 " + activeLine.lineId + " 已保持：" + activeLine.getDisplayName();
        
    } catch (Exception e) {
        return "保持通話失敗: " + e.getMessage();
    }
}

/**
 * 智能 Hold/Unhold 切換
 */
public String toggleHoldCall(String extension) {
    try {
        PhoneState phone = phoneStates.get(extension);
        if (phone == null) return "話機未初始化";
        
        PhoneLine activeLine = phone.getActiveLine();
        List<PhoneLine> heldLines = phone.getHeldLines();
        
        if (activeLine != null && activeLine.state == LineState.TALKING) {
            // 有活躍通話，執行 Hold
            return holdActiveCall(extension);
        } else if (!heldLines.isEmpty()) {
            // 沒有活躍通話但有保持的通話，執行 Unhold
            return unholdCall(extension);
        } else {
            return "沒有可操作的通話";
        }
        
    } catch (Exception e) {
        return "Hold/Unhold 切換失敗: " + e.getMessage();
    }
}

/**
 * 指定線路撥打電話（新增線路選擇功能）
 */
public String makeCallOnSpecificLine(String extension, String target, String preferredLineId) {
    try {
        PhoneState phone = getOrCreatePhone(extension);
        
        if (phone.getActiveLineCount() >= phone.maxLines) {
            return "已達線路上限";
        }
        
        // 檢查指定線路是否可用
        if (preferredLineId != null && !preferredLineId.isEmpty()) {
            PhoneLine existingLine = phone.findLine(preferredLineId);
            if (existingLine != null) {
                return "指定線路 " + preferredLineId + " 已被使用";
            }
        }
        
        // Hold當前活躍線路
        PhoneLine currentActive = phone.getActiveLine();
        if (currentActive != null) {
            holdLine(extension, currentActive);
            currentActive.state = LineState.HELD;
        }
        
        // 建立新線路（使用指定的線路ID或自動生成）
        String lineId = (preferredLineId != null && !preferredLineId.isEmpty()) 
                       ? preferredLineId 
                       : phone.generateLineId();
        
        Call newCall = createCall(extension, target);
        
        PhoneLine newLine = new PhoneLine(lineId, newCall, target, false);
        newLine.state = LineState.TALKING;
        phone.lines.add(newLine);
        phone.activeLine = lineId;
        
        return "線路 " + lineId + " 撥打成功：" + newLine.getDisplayName();
        
    } catch (Exception e) {
        return "指定線路撥打失敗: " + e.getMessage();
    }
}

/**
 * 取得可用線路列表
 */
public String getAvailableLines(String extension) {
    PhoneState phone = getOrCreatePhone(extension);
    
    StringBuilder result = new StringBuilder();
    result.append("=== 可用線路狀態 ===\n");
    result.append("最大線路數：").append(phone.maxLines).append("\n");
    result.append("已使用線路：").append(phone.getActiveLineCount()).append("\n");
    result.append("可用線路數：").append(phone.maxLines - phone.getActiveLineCount()).append("\n\n");
    
    // 顯示建議的線路ID
    if (phone.getActiveLineCount() < phone.maxLines) {
        String nextLineId = phone.generateLineId();
        result.append("建議使用線路ID：").append(nextLineId).append("\n");
    }
    
    return result.toString();
}
}