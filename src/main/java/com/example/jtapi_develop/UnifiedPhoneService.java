package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
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
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private ConferenceService conferenceService;
    
    /**
     * 話機線路狀態
     */
    public enum LineState {
        IDLE,           // 空閒
        RINGING,        // 響鈴
        TALKING,        // 通話中
        HELD,           // 保持
        CONFERENCING,   // 會議中
        TRANSFERRING,   // 轉接中
        DISCONNECTED    // 已斷線
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
        public int maxLines = 3;               // 最大線路數（1420分機實際支援3條）
        private int nextLineNumber = 1;
        
        public PhoneState(String extension) {
            this.extension = extension;
            this.lines = new ArrayList<>();
        }
        
        public String generateLineId() {
            // 找到第一個可用的線路編號 (1-3)
            for (int i = 1; i <= maxLines; i++) {
                String candidateId = extension + "_L" + i;
                boolean isUsed = lines.stream().anyMatch(line -> 
                    line.lineId.equals(candidateId) && line.state != LineState.DISCONNECTED);
                if (!isUsed) {
                    return candidateId;
                }
            }
            // 如果都被占用，回到 L1（這種情況不應該發生，因為前面會檢查線路上限）
            return extension + "_L1";
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
        
        public List<PhoneLine> getRingingLines() {
            return lines.stream().filter(l -> l.state == LineState.RINGING).collect(java.util.stream.Collectors.toList());
        }
        
        public int getActiveLineCount() {
            return (int) lines.stream().filter(l -> l.state != LineState.IDLE && l.state != LineState.DISCONNECTED).count();
        }
        
        public void removeDisconnectedLines() {
            lines.removeIf(line -> line.state == LineState.DISCONNECTED);
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
            // 檢查目標分機Agent狀態
            if (!isAgentAvailable(target)) {
                return "撥打失敗: 目標分機 " + target + " 的Agent目前不接受來電";
            }
            
            PhoneState phone = getOrCreatePhone(extension);
            
            // 清理斷開的線路，防止累積無效資料
            cleanupDisconnectedLines(phone);
            
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
            activeLine.state = LineState.DISCONNECTED;
            phone.lines.remove(activeLine);
            
            // 自動切換到下一條線路
            PhoneLine nextLine = phone.getHeldLines().stream().findFirst().orElse(null);
            if (nextLine != null) {
                unholdLine(extension, nextLine);
                phone.activeLine = nextLine.lineId;
                return "線路 " + activeLine.lineId + " 已掛斷，切換到 " + nextLine.lineId;
            } else {
                phone.activeLine = null;
                
                // 檢查 Agent 模式，如果是 Manual-in 則自動切換到 AUX
                String result = "線路 " + activeLine.lineId + " 已掛斷";
                String auxResult = checkAndSwitchToAuxIfManualIn(extension);
                if (auxResult != null) {
                    result += "\n" + auxResult;
                }
                
                return result;
            }
            
        } catch (Exception e) {
            return "掛斷失敗: " + e.getMessage();
        }
    }

    /**
     * 掛斷指定線路
     */
    public String hangupSpecificLine(String extension, String lineId) {
    try {
        // 先檢查是否是會議通話
        String sessionId = conferenceService.extensionToSessionMap.get(extension);
        if (sessionId != null) {
            ConferenceService.ConferenceSession session = conferenceService.activeSessions.get(sessionId);
            if (session != null && session.isActive) {
                // 使用會議服務掛斷
                String result = conferenceService.leaveConference(extension);
                return "會議掛斷結果: " + result;
            }
        }
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            if (lineId == null || lineId.isEmpty()) {
                return "請指定要掛斷的線路ID";
            }
            
            PhoneLine targetLine = phone.findLine(lineId);
            if (targetLine == null) {
                return "找不到線路 " + lineId;
            }
            
            if (targetLine.state == LineState.DISCONNECTED) {
                return "線路 " + lineId + " 已經斷線";
            }
            
            // 如果線路是 Hold 狀態，先恢復再掛斷
            if (targetLine.state == LineState.HELD) {
                try {
                    unholdLine(extension, targetLine);
                    Thread.sleep(500); // 等待 unhold 完成
                } catch (Exception e) {
                    System.err.println("[UNIFIED_PHONE] 恢復 Hold 線路失敗，嘗試直接掛斷: " + e.getMessage());
                }
            }
            
            // 掛斷指定線路
            disconnectLine(extension, targetLine);
            targetLine.state = LineState.DISCONNECTED;
            phone.lines.remove(targetLine);
            
            // 如果掛斷的是當前活躍線路，需要處理活躍線路切換
            if (lineId.equals(phone.activeLine)) {
                // 自動切換到下一條線路
                PhoneLine nextLine = phone.getHeldLines().stream().findFirst().orElse(null);
                if (nextLine != null) {
                    unholdLine(extension, nextLine);
                    phone.activeLine = nextLine.lineId;
                    return "線路 " + lineId + " 已掛斷，切換到 " + nextLine.lineId;
                } else {
                    phone.activeLine = null;
                    
                    // 檢查 Agent 模式，如果是 Manual-in 則自動切換到 AUX
                    String result = "線路 " + lineId + " 已掛斷";
                    String auxResult = checkAndSwitchToAuxIfManualIn(extension);
                    if (auxResult != null) {
                        result += "\n" + auxResult;
                    }
                    
                    return result;
                }
            } else {
                // 掛斷的不是活躍線路，只需要移除該線路
                return "線路 " + lineId + " 已掛斷（非活躍線路）";
            }
            
        } catch (Exception e) {
            return "掛斷指定線路失敗: " + e.getMessage();
        }
    }
    
    // ========================================
    // 多線功能（像話機上的線路切換鍵）- 修正版
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
     * 一般的 Unhold 功能（恢復被 Hold 的通話）
     */
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
    // 指定線路撥號功能（新增）
    // ========================================
    
    /**
     * 指定線路撥打電話（新增線路選擇功能）
     */
    public String makeCallOnSpecificLine(String extension, String target, String preferredLineId) {
        try {
            // 檢查目標分機Agent狀態
            if (!isAgentAvailable(target)) {
                return "撥打失敗: 目標分機 " + target + " 的Agent目前不接受來電";
            }
            
            PhoneState phone = getOrCreatePhone(extension);
            
            // 清理斷開的線路，防止累積無效資料
            cleanupDisconnectedLines(phone);
            
            // 檢查指定線路是否真的可用
            if (preferredLineId != null) {
                PhoneLine existingLine = phone.findLine(preferredLineId);
                if (existingLine != null && existingLine.state != LineState.IDLE) {
                    return "線路 " + preferredLineId + " 正在使用中，無法撥號";
                }
            }
            
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
     * 取得可用線路列表 (本地狀態)
     */
    public String getAvailableLines(String extension) {
        PhoneState phone = getOrCreatePhone(extension);
        
        StringBuilder result = new StringBuilder();
        result.append("=== 可用線路狀態 (本地) ===\n");
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
    
    /**
     * 從Server查詢實際可用線路數量
     */
    public String getServerAvailableLines(String extension) {
        StringBuilder result = new StringBuilder();
        result.append("=== Server端實際線路狀態 ===\n");
        result.append("分機：").append(extension).append("\n");
        result.append("查詢時間：").append(new java.util.Date()).append("\n\n");
        
        try {
            // 從Server獲取實際連線狀態
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                result.append("❌ 分機未連線到CTI系統\n");
                return result.toString();
            }
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            if (extensionConn.terminal == null) {
                result.append("❌ 分機終端不可用\n");
                return result.toString();
            }
            
            // 查詢Server端的終端連線
            TerminalConnection[] termConnections = extensionConn.terminal.getTerminalConnections();
            int activeConnections = 0;
            int ringingConnections = 0;
            int heldConnections = 0;
            int totalConnections = 0;
            
            if (termConnections != null) {
                totalConnections = termConnections.length;
                result.append("Server回報終端連線數：").append(totalConnections).append("\n\n");
                
                for (int i = 0; i < termConnections.length; i++) {
                    TerminalConnection termConn = termConnections[i];
                    int state = termConn.getState();
                    
                    // 嘗試找到對應的線路ID
                    String lineId = null;
                    PhoneState phone = phoneStates.get(extension);
                    if (phone != null) {
                        for (PhoneLine line : phone.lines) {
                            if (line.call != null && line.call.equals(termConn.getConnection().getCall())) {
                                lineId = line.lineId;
                                break;
                            }
                        }
                    }
                    
                    result.append("連線 ").append(i + 1);
                    if (lineId != null) {
                        result.append(" [LineID: ").append(lineId).append("]");
                    }
                    result.append(": ");
                    
                    switch (state) {
                        case TerminalConnection.ACTIVE:
                            result.append("🟢 通話中 (ACTIVE)");
                            activeConnections++;
                            break;
                        case TerminalConnection.RINGING:
                            result.append("🔔 響鈴中 (RINGING)");
                            ringingConnections++;
                            break;
                        case TerminalConnection.PASSIVE:
                            result.append("🟡 被動狀態 (PASSIVE)");
                            break;
                        case TerminalConnection.DROPPED:
                            result.append("❌ 已斷開 (DROPPED)");
                            break;
                        case TerminalConnection.IDLE:
                            result.append("⚪ 空閒 (IDLE)");
                            break;
                        case TerminalConnection.UNKNOWN:
                            result.append("❓ 未知 (UNKNOWN)");
                            break;
                        default:
                            result.append("❓ 狀態碼: " + state);
                    }
                    
                    try {
                        // 獲取通話資訊
                        Call call = termConn.getConnection().getCall();
                        if (call != null) {
                            Connection[] callConnections = call.getConnections();
                            if (callConnections != null) {
                                result.append(" [通話方數: ").append(callConnections.length).append("]");
                            } else {
                                result.append(" [通話方數: 0]");
                            }
                            
                            // 檢查是否為Hold狀態 (根據JTAPI標準)
                            if (termConn instanceof javax.telephony.callcontrol.CallControlTerminalConnection) {
                                javax.telephony.callcontrol.CallControlTerminalConnection cctc = 
                                    (javax.telephony.callcontrol.CallControlTerminalConnection) termConn;
                                int callControlState = cctc.getCallControlState();
                                
                                // 詳細狀態檢查
                                switch (callControlState) {
                                    case javax.telephony.callcontrol.CallControlTerminalConnection.HELD:
                                        result.append(" 🟠 [HELD]");
                                        heldConnections++;
                                        // 如果是Hold狀態，修改主要狀態顯示
                                        if (state == TerminalConnection.ACTIVE) {
                                            result.setLength(result.length() - "🟢 通話中 (ACTIVE)".length());
                                            result.append("🟠 Hold中 [HELD]");
                                        }
                                        break;
                                    case javax.telephony.callcontrol.CallControlTerminalConnection.TALKING:
                                        result.append(" 💬 [TALKING]");
                                        break;
                                    case javax.telephony.callcontrol.CallControlTerminalConnection.RINGING:
                                        result.append(" 📞 [CC_RINGING]");
                                        break;
                                    case javax.telephony.callcontrol.CallControlTerminalConnection.BRIDGED:
                                        result.append(" 🌉 [BRIDGED]");
                                        break;
                                    case javax.telephony.callcontrol.CallControlTerminalConnection.INUSE:
                                        result.append(" 📱 [INUSE]");
                                        break;
                                }
                            }
                            
                            // 找到對方號碼
                            for (Connection callConn : callConnections) {
                                String addr = callConn.getAddress().getName();
                                if (!addr.equals(extension)) {
                                    result.append(" ↔ ").append(addr);
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        result.append(" [查詢通話資訊失敗: ").append(e.getMessage()).append("]");
                    }
                    
                    result.append("\n");
                }
            } else {
                result.append("Server回報：無終端連線\n");
            }
            
            // 統計摘要
            result.append("\n=== 統計摘要 ===\n");
            result.append("總連線數：").append(totalConnections).append("\n");
            result.append("通話中：").append(activeConnections).append("\n");
            result.append("響鈴中：").append(ringingConnections).append("\n");
            result.append("Hold中：").append(heldConnections).append("\n");
            
            // 計算可用線路數（基於Avaya系統通常的限制）
            int busyLines = activeConnections + ringingConnections + heldConnections;
            int maxLines = 3; // 1420分機實際支援3條線路
            int availableLines = maxLines - busyLines;
            
            result.append("忙線數：").append(busyLines).append("\n");
            result.append("預估可用線路：").append(Math.max(0, availableLines)).append("/").append(maxLines).append("\n");
            
            if (availableLines > 0) {
                result.append("✅ 可以建立新通話\n");
            } else {
                result.append("❌ 已達線路上限，無法建立新通話\n");
            }
            
        } catch (Exception e) {
            result.append("❌ 查詢Server狀態失敗：").append(e.getMessage()).append("\n");
            e.printStackTrace();
        }
        
        return result.toString();
    }
    
    // ========================================
    // 轉接功能（像話機的Transfer鍵）
    // ========================================
    
    /**
     * 一段轉接（盲轉）- 直接轉接不諮詢
     */
    public String blindTransfer(String extension, String target) {
        try {
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            PhoneLine activeLine = phone.getActiveLine();
            if (activeLine == null) return "沒有活躍線路可轉接";
            
            String originalParty = activeLine.remoteParty;
            
            // 執行一段轉接 - 直接重新連接
            try {
                if (activeLine.call instanceof CallControlCall) {
                    CallControlCall controlCall = (CallControlCall) activeLine.call;
                    
                    // 找到原通話的另一方連線
                    Connection[] connections = controlCall.getConnections();
                    for (Connection connection : connections) {
                        if (connection.getAddress().getName().equals(extension)) {
                            // 使用 redirect 方法進行一段轉接
                            if (connection instanceof CallControlConnection) {
                                CallControlConnection ccConn = (CallControlConnection) connection;
                                ccConn.redirect(target);
                                
                                // 移除線路
                                phone.lines.remove(activeLine);
                                phone.activeLine = null;
                                
                                return "一段轉接成功：" + originalParty + " → " + target;
                            }
                        }
                    }
                }
                
                // 如果 redirect 不支援，使用替代方法
                return blindTransferAlternative(extension, target, activeLine, phone);
                
            } catch (Exception e) {
                System.err.println("[BLIND_TRANSFER] Redirect 失敗: " + e.getMessage());
                return blindTransferAlternative(extension, target, activeLine, phone);
            }
            
        } catch (Exception e) {
            return "一段轉接失敗: " + e.getMessage();
        }
    }
    
    /**
     * 一段轉接的替代方法
     */
    private String blindTransferAlternative(String extension, String target, PhoneLine activeLine, PhoneState phone) {
        try {
            String originalParty = activeLine.remoteParty;
            
            // 先掛斷現有通話
            disconnectLine(extension, activeLine);
            phone.lines.remove(activeLine);
            phone.activeLine = null;
            
            // 等待一下
            Thread.sleep(1000);
            
            // 建立新通話：目標 → 原通話方
            var conn = phoneCallService.getExtensionConnection(extension);
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            
            // 嘗試讓目標分機撥打給原通話方
            // 注意：這個方法可能需要系統支援第三方通話控制
            Call bridgeCall = extensionConn.provider.createCall();
            
            // 使用分機作為橋接，快速掛斷讓兩方直接連線
            // 這是一個簡化的實現，實際效果可能因系統而異
            
            return "一段轉接完成（簡化模式）：已斷開 " + extension + "，請手動聯繫 " + target + " 接聽來自 " + originalParty + " 的通話";
            
        } catch (Exception e) {
            return "一段轉接替代方法失敗: " + e.getMessage();
        }
    }
    
    /**
     * 開始轉接（Transfer鍵 + 撥號）- 二段轉接
     */
    public String startTransfer(String extension, String target) {
        try {
            // 檢查轉接目標Agent狀態
            if (!isAgentAvailable(target)) {
                return "轉接失敗: 目標分機 " + target + " 的Agent目前不接受來電";
            }
            
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
            
            return "二段轉接諮詢開始：正在連接 " + target + "，確認後按【完成轉接】或按【取消轉接】";
            
        } catch (Exception e) {
            return "二段轉接開始失敗: " + e.getMessage();
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
                    
                    return "二段轉接完成：" + transferringLine.remoteParty + " → " + consultLine.remoteParty;
                }
            } catch (Exception e) {
                // 轉接失敗，恢復狀態
                transferringLine.isTransferring = false;
                transferringLine.transferTarget = null;
                return "轉接失敗: " + e.getMessage() + "，可按【取消轉接】恢復通話";
            }
            
            return "轉接失敗";
            
        } catch (Exception e) {
            return "完成轉接失敗: " + e.getMessage();
        }
    }
    
    /**
     * 取消轉接（在諮詢階段取消）
     */
    public String cancelTransfer(String extension) {
        try {
            PhoneState phone = phoneStates.get(extension);
            if (phone == null) return "話機未初始化";
            
            // 找轉接中的線路
            PhoneLine transferringLine = phone.lines.stream()
                .filter(l -> l.isTransferring)
                .findFirst().orElse(null);
                
            if (transferringLine == null) return "沒有進行中的轉接可取消";
            
            PhoneLine consultLine = phone.getActiveLine();
            
            // 掛斷諮詢通話
            if (consultLine != null) {
                disconnectLine(extension, consultLine);
                phone.lines.remove(consultLine);
            }
            
            // 恢復原始通話
            transferringLine.isTransferring = false;
            transferringLine.transferTarget = null;
            unholdLine(extension, transferringLine);
            transferringLine.state = LineState.TALKING;
            phone.activeLine = transferringLine.lineId;
            
            return "轉接已取消，已恢復與 " + transferringLine.remoteParty + " 的通話";
            
        } catch (Exception e) {
            return "取消轉接失敗: " + e.getMessage();
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
            case DISCONNECTED: return "已斷線";
            default: return "空閒";
        }
    }
    
    /**
     * 檢查 Agent 是否處於 Manual-in 模式，提供 ACW 狀態資訊
     */
    private String checkAndSwitchToAuxIfManualIn(String extension) {
        try {
            // 獲取 AgentService
            AgentService agentService = applicationContext.getBean(AgentService.class);
            
            // 檢查 Agent 狀態
            String agentStatus = agentService.getAgentStatus(extension);
            
            // 如果Agent處於Manual-in模式，提醒ACW自動轉換
            if (agentStatus.contains("手動接聽")) {
                return "🔄 Manual-in 模式：通話結束後系統將自動切換到 ACW (話後工作) 狀態\n" +
                       "💡 完成話後工作後，請手動點擊 Manual-in 按鈕重新就緒";
            }
            
            return null; // 不是 Manual-in 模式，不需要特殊處理
            
        } catch (Exception e) {
            return "⚠️ 檢查 Agent 模式時發生錯誤: " + e.getMessage();
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
                
                // 檢查是否已經存在相同對方的通話（不管狀態）
                PhoneLine existingLine = phone.lines.stream()
                    .filter(line -> line.remoteParty.equals(remoteParty))
                    .findFirst()
                    .orElse(null);
                
                if (existingLine != null) {
                    // 更新現有線路的狀態（只在JTAPI狀態與本地狀態不一致時）
                    LineState actualState = mapTerminalConnectionToLineState(termConn);
                    if (actualState != existingLine.state) {
                        System.out.println("[UNIFIED_PHONE] 更新線路狀態: " + existingLine.lineId + 
                                         " 從 " + existingLine.state + " 到 " + actualState + 
                                         " (對方: " + remoteParty + ")");
                        existingLine.state = actualState;
                        
                        // 更新活躍線路
                        if (actualState == LineState.TALKING && phone.activeLine == null) {
                            phone.activeLine = existingLine.lineId;
                        } else if (actualState == LineState.HELD && existingLine.lineId.equals(phone.activeLine)) {
                            phone.activeLine = null;
                        }
                    }
                } else {
                    // 新的通話，創建新線路
                    String lineId = phone.generateLineId();
                    boolean isIncoming = determineCallDirection(existingCall, extension);
                    LineState lineState = mapTerminalConnectionToLineState(termConn);
                    
                    if (lineState != LineState.IDLE && lineState != LineState.DISCONNECTED) {
                        PhoneLine importedLine = new PhoneLine(lineId, existingCall, remoteParty, isIncoming);
                        importedLine.state = lineState;
                        phone.lines.add(importedLine);
                        
                        // 設定活躍線路
                        if (lineState == LineState.TALKING && phone.activeLine == null) {
                            phone.activeLine = lineId;
                        }
                        
                        System.out.println("[UNIFIED_PHONE] 導入新通話: " + lineId + 
                                         " 狀態: " + lineState + " 對方: " + remoteParty + 
                                         " 方向: " + (isIncoming ? "來電" : "撥出"));
                    }
                }
            }
            
            // 移除已經不存在的通話（基於對方號碼檢查）
            java.util.Set<String> currentRemoteParties = new java.util.HashSet<>();
            for (String callId : currentCallIds) {
                String remoteParty = callId.split("_")[0]; // 提取對方號碼
                currentRemoteParties.add(remoteParty);
            }
            
            phone.lines.removeIf(line -> {
                return !currentRemoteParties.contains(line.remoteParty);
            });
            
        } catch (Exception e) {
            System.err.println("[UNIFIED_PHONE] 檢測現有通話失敗: " + e.getMessage());
        }
    }
    
    /**
     * 清理已斷開的線路（增強版）
     */
    private void cleanupDisconnectedLines(PhoneState phone) {
        phone.lines.removeIf(line -> {
            try {
                if (line.call != null) {
                    // 檢查通話是否還有效
                    int callState = line.call.getState();
                    if (callState == Call.INVALID) {
                        System.out.println("[UNIFIED_PHONE] 清理無效通話線路: " + line.lineId);
                        return true; // 移除此線路
                    }
                    
                    Connection[] connections = line.call.getConnections();
                    if (connections == null) {
                        System.out.println("[UNIFIED_PHONE] 清理無連線的線路: " + line.lineId);
                        return true; // 移除此線路
                    }
                    
                    boolean hasActiveConnection = false;
                    int connectedCount = 0;
                    
                    for (Connection conn : connections) {
                        int connState = conn.getState();
                        if (connState == Connection.CONNECTED) {
                            connectedCount++;
                            hasActiveConnection = true;
                        } else if (connState != Connection.DISCONNECTED && 
                                  connState != Connection.FAILED) {
                            hasActiveConnection = true;
                        }
                    }
                    
                    // 如果沒有活躍連線，或者連線數少於2（正常通話需要至少2個連線）
                    if (!hasActiveConnection || connectedCount < 2) {
                        System.out.println("[UNIFIED_PHONE] 清理斷開的線路: " + line.lineId + 
                                          " (活躍連線: " + hasActiveConnection + ", 連線數: " + connectedCount + ")");
                        return true; // 移除此線路
                    }
                }
            } catch (Exception e) {
                // 如果檢查過程出錯，也移除此線路
                System.out.println("[UNIFIED_PHONE] 清理異常線路: " + line.lineId + " - " + e.getMessage());
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
                // 檢查是否為 HELD 狀態（即使 TerminalConnection 是 ACTIVE）
                if (termConn instanceof CallControlTerminalConnection) {
                    CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                    if (ccTermConn.getCallControlState() == CallControlTerminalConnection.HELD) {
                        return LineState.HELD;
                    }
                }
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
            case TerminalConnection.DROPPED:
                return LineState.DISCONNECTED;
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
            case DISCONNECTED:
                return TerminalConnection.DROPPED;
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
            
            // 方法2：簡單規則 - 如果對方是4位數分機號，假設為撥出
            String remoteParty = findRemoteParty(call, localExtension);
            if (remoteParty != null && remoteParty.matches("\\d{4}")) {
                // 4位數分機號，假設為撥出
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
            if (connections == null) {
                throw new Exception("通話連線已失效，無法Hold");
            }
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
            if (connections == null) {
                throw new Exception("通話連線已失效，無法Unhold");
            }
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
    // 檢查是否為三方通話
    if (line.isConference || line.state == LineState.CONFERENCING) {
        System.out.println("[UNIFIED_PHONE] 檢測到三方通話，使用會議掛斷方式");
        disconnectConferenceLine(extension, line);
        return;
    }
        
        // 一般通話的掛斷邏輯
        if (line.call != null) {
            Connection[] connections = line.call.getConnections();
            if (connections == null) {
                System.out.println("[UNIFIED_PHONE] 通話連線已失效，跳過掛斷");
                return;
            }
            
            // 尋找並斷開該分機的連線
            boolean foundConnection = false;
            for (Connection connection : connections) {
                if (connection.getAddress().getName().equals(extension)) {
                    try {
                        // 檢查連線狀態
                        if (connection.getState() == Connection.DISCONNECTED) {
                            System.out.println("[UNIFIED_PHONE] 連線已斷開，跳過");
                            foundConnection = true;
                            break;
                        }
                        
                        System.out.println("[UNIFIED_PHONE] 嘗試斷開一般通話連線: " + extension + " 狀態: " + connection.getState());
                        connection.disconnect();
                        foundConnection = true;
                        break;
                    } catch (Exception e) {
                        System.err.println("[UNIFIED_PHONE] 斷開連線失敗: " + e.getMessage());
                        // 拋出簡化的錯誤信息
                        throw new Exception("線路處於保持狀態，無法直接掛斷");
                    }
                }
            }
            
            if (!foundConnection) {
                System.err.println("[UNIFIED_PHONE] 未找到分機 " + extension + " 的連線");
                throw new Exception("未找到分機連線");
            }
        }
    }
    
   /**
    * 專用的三方通話掛斷方法
     */
    private void disconnectConferenceLine(String extension, PhoneLine line) throws Exception {
         System.out.println("[UNIFIED_PHONE] 開始處理三方通話掛斷: " + extension);
    
        if (line.call == null) {
        System.out.println("[UNIFIED_PHONE] 會議通話對象為空");
        return;
         }
    
        try {
        // 方法1：嘗試找到該分機在會議中的連線並斷開
        Connection[] connections = line.call.getConnections();
        System.out.println("[UNIFIED_PHONE] 會議通話連線數: " + connections.length);
        
        boolean foundAndDisconnected = false;
        for (Connection connection : connections) {
            String address = connection.getAddress().getName();
            System.out.println("[UNIFIED_PHONE] 檢查會議參與者: " + address + " 狀態: " + connection.getState());
            
            if (address.equals(extension)) {
                if (connection.getState() != Connection.DISCONNECTED) {
                    System.out.println("[UNIFIED_PHONE] 斷開會議參與者: " + extension);
                    connection.disconnect();
                    foundAndDisconnected = true;
                    
                    // 給其他參與者一些時間處理
                    Thread.sleep(500);
                    break;
                } else {
                    System.out.println("[UNIFIED_PHONE] 參與者已斷線: " + extension);
                    foundAndDisconnected = true;
                    break;
                }
            }
        }
        
        if (foundAndDisconnected) {
            System.out.println("[UNIFIED_PHONE] 三方通話掛斷成功");
            return;
        }
        
        // 方法2：如果找不到特定連線，嘗試使用ConferenceService的方法
        System.out.println("[UNIFIED_PHONE] 未找到特定連線，嘗試備選方法");
        
        // 檢查是否有 ConferenceService 會話
        String sessionId = conferenceService.extensionToSessionMap.get(extension);
        if (sessionId != null) {
            System.out.println("[UNIFIED_PHONE] 找到會議會話，使用 ConferenceService 結束會議");
            String result = conferenceService.endConference(extension);
            System.out.println("[UNIFIED_PHONE] ConferenceService 結果: " + result);
            return;
        }
        
        // 方法3：最後的備選方案 - 直接斷開所有連線
        System.out.println("[UNIFIED_PHONE] 使用最後備選方案：斷開所有會議連線");
        for (Connection connection : connections) {
            if (connection.getState() != Connection.DISCONNECTED) {
                try {
                    connection.disconnect();
                    System.out.println("[UNIFIED_PHONE] 斷開連線: " + connection.getAddress().getName());
                } catch (Exception e) {
                    System.err.println("[UNIFIED_PHONE] 斷開連線失敗: " + connection.getAddress().getName() + " - " + e.getMessage());
                }
            }
        }
        
        } catch (Exception e) {
        System.err.println("[UNIFIED_PHONE] 三方通話掛斷失敗: " + e.getMessage());
        throw new Exception("三方通話掛斷失敗: " + e.getMessage());
        }
    }

    /**
     * 根據通話對象和本地分機號，找到遠端分機號
     */
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
                
                // 優先選擇4位數分機號
                if (addressName.matches("\\d{4}")) {
                    return addressName; // 找到分機號，立即返回
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
    
    // ========================================
    // Agent狀態檢查功能
    // ========================================
    
    /**
     * 檢查Agent是否可接受來電
     */
    private boolean isAgentAvailable(String extension) {
        try {
            // 使用ApplicationContext來避免循環依賴
            AgentService agentService = applicationContext.getBean(AgentService.class);
            
            // 先檢查Agent是否登入
            String agentStatus = agentService.getAgentStatus(extension);
            
            // 如果沒有Agent登入，則允許通話（普通分機模式）
            if (agentStatus.contains("沒有 Agent 登入")) {
                System.out.println("[UNIFIED_AGENT_CHECK] 分機 " + extension + " 沒有Agent登入，允許通話");
                return true;
            }
            
            // 有Agent登入，檢查狀態
            if (agentStatus.contains("待機中")) {
                System.out.println("[UNIFIED_AGENT_CHECK] 分機 " + extension + " Agent處於待機狀態，允許通話");
                return true;
            } else if (agentStatus.contains("忙碌中") || agentStatus.contains("休息中")) {
                System.out.println("[UNIFIED_AGENT_CHECK] 分機 " + extension + " Agent處於" + 
                                 (agentStatus.contains("忙碌中") ? "忙碌" : "休息") + "狀態，拒絕通話");
                return false;
            }
            
            // 其他狀態預設允許
            return true;
            
        } catch (Exception e) {
            System.err.println("[UNIFIED_AGENT_CHECK] 檢查Agent狀態失敗: " + e.getMessage());
            // 發生錯誤時預設允許通話
            return true;
        }
    }
    
    /**
     * 檢查並顯示分機Agent狀態
     */
    public String checkAgentStatus(String extension) {
        try {
            AgentService agentService = applicationContext.getBean(AgentService.class);
            String agentStatus = agentService.getAgentStatus(extension);
            
            if (agentStatus.contains("沒有 Agent 登入")) {
                return "分機 " + extension + " - 普通分機模式（未登入Agent）- 可接受來電";
            } else if (agentStatus.contains("待機中")) {
                return "分機 " + extension + " - Agent待機中 - 可接受來電";
            } else if (agentStatus.contains("忙碌中")) {
                return "分機 " + extension + " - Agent忙碌中 - 拒絕來電";
            } else if (agentStatus.contains("休息中")) {
                return "分機 " + extension + " - Agent休息中 - 拒絕來電";
            } else {
                return "分機 " + extension + " - Agent狀態未知 - " + agentStatus;
            }
        } catch (Exception e) {
            return "檢查Agent狀態失敗: " + e.getMessage();
        }
    }
    
    /**
     * 獲取所有話機的通話狀態總覽
     */
    public String getAllPhoneStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== 所有話機通話狀態總覽 ===\n");
        status.append("查詢時間: ").append(new java.util.Date()).append("\n\n");
        
        // 定義要檢查的分機列表
        String[] extensions = {"1411", "1420", "1424", "1425", "1001", "1002", "1003"};
        
        for (String ext : extensions) {
            status.append("📞 分機 ").append(ext).append(":\n");
            
            try {
                // 檢查是否有線路狀態
                PhoneState phoneState = phoneStates.get(ext);
                if (phoneState == null || phoneState.lines.isEmpty()) {
                    status.append("   狀態: 空閒 (無活動線路)\n");
                    
                    // 檢查Agent狀態
                    String agentStatus = checkAgentStatus(ext);
                    if (!agentStatus.contains("普通分機模式")) {
                        status.append("   Agent: ").append(agentStatus.substring(agentStatus.indexOf("Agent") + 5)).append("\n");
                    }
                } else {
                    status.append("   線路數: ").append(phoneState.lines.size()).append("\n");
                    
                    for (PhoneLine line : phoneState.lines) {
                        status.append("   └─ 線路 ").append(line.lineId).append(": ");
                        status.append(getLineStateDisplay(line.state)).append(" ");
                        
                        if (line.remoteParty != null && !line.remoteParty.trim().isEmpty()) {
                            status.append("對方: ").append(line.remoteParty).append(" ");
                        }
                        
                        if (line.state == LineState.TALKING || line.state == LineState.CONFERENCING) {
                            long duration = (System.currentTimeMillis() - line.startTime) / 1000;
                            status.append("(通話時長: ").append(duration).append("秒)");
                        }
                        
                        if (line.isConference) {
                            status.append(" [會議]");
                        }
                        
                        if (line.isTransferring) {
                            status.append(" [轉接中]");
                        }
                        
                        status.append("\n");
                    }
                    
                    // 檢查Agent狀態
                    String agentStatus = checkAgentStatus(ext);
                    if (!agentStatus.contains("普通分機模式")) {
                        status.append("   Agent: ").append(agentStatus.substring(agentStatus.indexOf("Agent") + 5)).append("\n");
                    }
                }
            } catch (Exception e) {
                status.append("   錯誤: ").append(e.getMessage()).append("\n");
            }
            
            status.append("\n");
        }
        
        // 統計資訊
        status.append("=== 系統統計 ===\n");
        int totalActiveLines = 0;
        int busyExtensions = 0;
        
        for (String ext : extensions) {
            PhoneState phoneState = phoneStates.get(ext);
            if (phoneState != null && !phoneState.lines.isEmpty()) {
                totalActiveLines += phoneState.lines.size();
                busyExtensions++;
            }
        }
        
        status.append("活躍線路總數: ").append(totalActiveLines).append("\n");
        status.append("忙碌分機數: ").append(busyExtensions).append("/").append(extensions.length).append("\n");
        status.append("空閒分機數: ").append(extensions.length - busyExtensions).append("/").append(extensions.length).append("\n");
        
        return status.toString();
    }
    
    /**
     * 獲取線路狀態的顯示文字
     */
    private String getLineStateDisplay(LineState state) {
        switch (state) {
            case IDLE: return "空閒";
            case RINGING: return "響鈴中";
            case TALKING: return "通話中";
            case HELD: return "保持中";
            case CONFERENCING: return "會議中";
            case TRANSFERRING: return "轉接中";
            case DISCONNECTED: return "已斷線";
            default: return "未知狀態";
        }
    }
}