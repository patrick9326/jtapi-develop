package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.telephony.*;
import javax.telephony.callcontrol.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Service
public class MultiLineService {
    
    @Autowired
    private PhoneCallService phoneCallService;
    
    /**
     * 通話線路狀態枚舉
     */
    public enum LineState {
        IDLE,           // 空閒
        RINGING,        // 響鈴中
        ACTIVE,         // 活躍通話中
        HELD,           // 保持中
        DISCONNECTED,   // 已斷線
        DIALING         // 撥號中
    }
    
    /**
     * 單條通話線路類
     */
    public static class CallLine {
        public String lineId;                    // 線路ID
        public Call call;                        // 通話對象
        public String remoteParty;              // 對方號碼
        public LineState state;                 // 線路狀態
        public long startTime;                  // 開始時間
        public boolean isIncoming;              // 是否為來電
        public String callDirection;            // 通話方向描述
        
        public CallLine(String lineId, Call call, String remoteParty, boolean isIncoming) {
            this.lineId = lineId;
            this.call = call;
            this.remoteParty = remoteParty;
            this.isIncoming = isIncoming;
            this.startTime = System.currentTimeMillis();
            this.state = LineState.RINGING;
            this.callDirection = isIncoming ? "來電從 " + remoteParty : "撥出到 " + remoteParty;
        }
        
        public long getDurationSeconds() {
            return (System.currentTimeMillis() - startTime) / 1000;
        }
        
        public String getStateDescription() {
            switch (state) {
                case IDLE: return "空閒";
                case RINGING: return isIncoming ? "來電響鈴" : "撥出響鈴";
                case ACTIVE: return "通話中";
                case HELD: return "保持中";
                case DISCONNECTED: return "已斷線";
                case DIALING: return "撥號中";
                default: return "未知狀態";
            }
        }
    }
    
    /**
     * 多線管理會話類
     */
    public static class MultiLineSession {
        public String extension;                             // 分機號
        public List<CallLine> lines;                        // 線路列表
        public String activeLineId;                         // 當前活躍線路ID
        public boolean autoAnswerEnabled;                   // 自動接聽模式
        public int maxLines;                                // 最大線路數
        private int nextLineNumber = 1;                     // 下一個線路編號
        
        public MultiLineSession(String extension) {
            this.extension = extension;
            this.lines = new ArrayList<>();
            this.autoAnswerEnabled = false;
            this.maxLines = 6; // 預設最多6線
        }
        
        public String generateNextLineId() {
            return extension + "_line_" + (nextLineNumber++);
        }
        
        public CallLine findLineById(String lineId) {
            return lines.stream()
                    .filter(line -> line.lineId.equals(lineId))
                    .findFirst()
                    .orElse(null);
        }
        
        public CallLine findActiveeLine() {
            return lines.stream()
                    .filter(line -> line.state == LineState.ACTIVE)
                    .findFirst()
                    .orElse(null);
        }
        
        public List<CallLine> getHeldLines() {
            return lines.stream()
                    .filter(line -> line.state == LineState.HELD)
                    .collect(java.util.stream.Collectors.toList());
        }
        
        public List<CallLine> getRingingLines() {
            return lines.stream()
                    .filter(line -> line.state == LineState.RINGING)
                    .collect(java.util.stream.Collectors.toList());
        }
        
        public int getActiveLineCount() {
            return (int) lines.stream()
                    .filter(line -> line.state != LineState.DISCONNECTED)
                    .count();
        }
        
        public void removeDisconnectedLines() {
            lines.removeIf(line -> line.state == LineState.DISCONNECTED);
        }
    }
    
    // 存儲每個分機的多線會話
    private final ConcurrentHashMap<String, MultiLineSession> extensionSessions = new ConcurrentHashMap<>();
    
    /**
     * 接聽來電
     */
    public String answerIncomingCall(String extension) {
        try {
            System.out.println("[MULTILINE] 分機 " + extension + " 嘗試接聽來電");
            
            MultiLineSession session = getOrCreateSession(extension);
            
            // 查找響鈴的來電
            List<CallLine> ringingLines = session.getRingingLines();
            if (ringingLines.isEmpty()) {
                // 如果沒有響鈴的線路，檢查是否有新來電
                var conn = phoneCallService.getExtensionConnection(extension);
                if (conn == null) {
                    return "錯誤：分機 " + extension + " 未登入";
                }
                
                // 檢查終端是否有響鈴的連線
                var extensionConn = (PhoneCallService.ExtensionConnection) conn;
                if (extensionConn.terminal != null) {
                    TerminalConnection[] termConnections = extensionConn.terminal.getTerminalConnections();
                    if (termConnections != null) {
                        for (TerminalConnection termConn : termConnections) {
                            if (termConn.getState() == TerminalConnection.RINGING) {
                                // 找到響鈴的通話，創建新線路
                                Call incomingCall = termConn.getConnection().getCall();
                                String remoteParty = findRemoteParty(incomingCall, extension);
                                
                                String lineId = session.generateNextLineId();
                                CallLine newLine = new CallLine(lineId, incomingCall, remoteParty, true);
                                session.lines.add(newLine);
                                
                                // 接聽電話
                                if (termConn instanceof CallControlTerminalConnection) {
                                    ((CallControlTerminalConnection) termConn).answer();
                                }
                                
                                // 如果有其他活躍線路，先Hold它們
                                holdOtherActiveLines(session, lineId);
                                
                                newLine.state = LineState.ACTIVE;
                                session.activeLineId = lineId;
                                
                                System.out.println("[MULTILINE] 線路 " + lineId + " 接聽成功");
                                return "線路 " + lineId + " 接聽成功：來電從 " + remoteParty + 
                                       "\n當前活躍線路數：" + session.getActiveLineCount();
                            }
                        }
                    }
                }
                
                return "沒有找到響鈴的來電";
            }
            
            // 接聽第一個響鈴的來電
            CallLine ringingLine = ringingLines.get(0);
            
            // 先Hold其他活躍線路
            holdOtherActiveLines(session, ringingLine.lineId);
            
            // 接聽這條線路
            if (ringingLine.call != null) {
                Connection[] connections = ringingLine.call.getConnections();
                for (Connection connection : connections) {
                    if (connection.getAddress().getName().equals(extension)) {
                        TerminalConnection[] termConns = connection.getTerminalConnections();
                        for (TerminalConnection termConn : termConns) {
                            if (termConn instanceof CallControlTerminalConnection) {
                                ((CallControlTerminalConnection) termConn).answer();
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            
            ringingLine.state = LineState.ACTIVE;
            session.activeLineId = ringingLine.lineId;
            
            System.out.println("[MULTILINE] 線路 " + ringingLine.lineId + " 接聽成功");
            return "線路 " + ringingLine.lineId + " 接聽成功：" + ringingLine.callDirection + 
                   "\n當前活躍線路數：" + session.getActiveLineCount();
            
        } catch (Exception e) {
            System.err.println("[MULTILINE] 接聽來電失敗: " + e.getMessage());
            e.printStackTrace();
            return "接聽來電失敗: " + e.getMessage();
        }
    }
    
    /**
     * 撥打新電話
     */
    public String makeNewCall(String extension, String target) {
        try {
            System.out.println("[MULTILINE] 分機 " + extension + " 撥打新電話給 " + target);
            
            MultiLineSession session = getOrCreateSession(extension);
            
            // 檢查線路數量限制
            if (session.getActiveLineCount() >= session.maxLines) {
                return "錯誤：已達到最大線路數限制 (" + session.maxLines + ")";
            }
            
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：分機 " + extension + " 未登入";
            }
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            
            // 先Hold當前活躍的線路
            CallLine activeLine = session.findActiveeLine();
            if (activeLine != null) {
                holdLine(session, activeLine);
            }
            
            // 創建新通話
            String lineId = session.generateNextLineId();
            Call newCall = extensionConn.provider.createCall();
            newCall.connect(extensionConn.terminal, extensionConn.address, target);
            
            // 創建新線路
            CallLine newLine = new CallLine(lineId, newCall, target, false);
            newLine.state = LineState.DIALING;
            session.lines.add(newLine);
            session.activeLineId = lineId;
            
            System.out.println("[MULTILINE] 新線路 " + lineId + " 撥打中");
            
            // 等待一下確定撥打狀態
            Thread.sleep(1000);
            
            // 檢查通話狀態
            Connection[] connections = newCall.getConnections();
            boolean isConnected = false;
            for (Connection connection : connections) {
                if (connection.getState() == Connection.CONNECTED) {
                    isConnected = true;
                    break;
                }
            }
            
            if (isConnected) {
                newLine.state = LineState.ACTIVE;
                return "線路 " + lineId + " 撥打成功：撥出到 " + target + 
                       "\n當前活躍線路數：" + session.getActiveLineCount();
            } else {
                newLine.state = LineState.DIALING;
                return "線路 " + lineId + " 撥打中：撥出到 " + target + 
                       "\n當前活躍線路數：" + session.getActiveLineCount();
            }
            
        } catch (Exception e) {
            System.err.println("[MULTILINE] 撥打新電話失敗: " + e.getMessage());
            e.printStackTrace();
            return "撥打新電話失敗: " + e.getMessage();
        }
    }
    
    /**
     * Hold 指定通話
     */
    public String holdCall(String extension, String lineId) {
        try {
            MultiLineSession session = extensionSessions.get(extension);
            if (session == null) {
                return "錯誤：分機 " + extension + " 沒有活躍的線路";
            }
            
            CallLine targetLine;
            if (lineId == null || lineId.isEmpty()) {
                // 如果沒有指定線路ID，Hold當前活躍的線路
                targetLine = session.findActiveeLine();
                if (targetLine == null) {
                    return "沒有找到活躍的線路可以Hold";
                }
            } else {
                targetLine = session.findLineById(lineId);
                if (targetLine == null) {
                    return "錯誤：找不到線路 " + lineId;
                }
            }
            
            if (targetLine.state != LineState.ACTIVE) {
                return "錯誤：線路 " + targetLine.lineId + " 不是活躍狀態，無法Hold";
            }
            
            // 執行Hold操作
            holdLine(session, targetLine);
            
            return "線路 " + targetLine.lineId + " 已Hold：" + targetLine.callDirection;
            
        } catch (Exception e) {
            System.err.println("[MULTILINE] Hold通話失敗: " + e.getMessage());
            e.printStackTrace();
            return "Hold通話失敗: " + e.getMessage();
        }
    }
    
    /**
     * 恢復被Hold的通話
     */
    public String unholdCall(String extension, String lineId) {
        try {
            MultiLineSession session = extensionSessions.get(extension);
            if (session == null) {
                return "錯誤：分機 " + extension + " 沒有線路會話";
            }
            
            CallLine targetLine = session.findLineById(lineId);
            if (targetLine == null) {
                return "錯誤：找不到線路 " + lineId;
            }
            
            if (targetLine.state != LineState.HELD) {
                return "錯誤：線路 " + lineId + " 不是Hold狀態";
            }
            
            // 先Hold其他活躍線路
            holdOtherActiveLines(session, lineId);
            
            // 恢復指定線路
            if (targetLine.call != null) {
                Connection[] connections = targetLine.call.getConnections();
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
            
            targetLine.state = LineState.ACTIVE;
            session.activeLineId = lineId;
            
            System.out.println("[MULTILINE] 線路 " + lineId + " 已恢復");
            return "線路 " + lineId + " 已恢復：" + targetLine.callDirection;
            
        } catch (Exception e) {
            System.err.println("[MULTILINE] 恢復通話失敗: " + e.getMessage());
            e.printStackTrace();
            return "恢復通話失敗: " + e.getMessage();
        }
    }
    
    /**
     * 切換到指定線路
     */
    public String switchToLine(String extension, String lineId) {
        try {
            MultiLineSession session = extensionSessions.get(extension);
            if (session == null) {
                return "錯誤：分機 " + extension + " 沒有線路會話";
            }
            
            CallLine targetLine = session.findLineById(lineId);
            if (targetLine == null) {
                return "錯誤：找不到線路 " + lineId;
            }
            
            if (targetLine.state == LineState.ACTIVE) {
                return "線路 " + lineId + " 已經是活躍狀態";
            }
            
            if (targetLine.state == LineState.DISCONNECTED) {
                return "錯誤：線路 " + lineId + " 已斷線";
            }
            
            // 先Hold當前活躍線路
            CallLine currentActive = session.findActiveeLine();
            if (currentActive != null && !currentActive.lineId.equals(lineId)) {
                holdLine(session, currentActive);
            }
            
            // 如果目標線路是HELD狀態，恢復它
            if (targetLine.state == LineState.HELD) {
                return unholdCall(extension, lineId);
            }
            
            // 如果是響鈴狀態，接聽它
            if (targetLine.state == LineState.RINGING) {
                return answerIncomingCall(extension);
            }
            
            return "無法切換到線路 " + lineId + "，狀態：" + targetLine.getStateDescription();
            
        } catch (Exception e) {
            System.err.println("[MULTILINE] 切換線路失敗: " + e.getMessage());
            e.printStackTrace();
            return "切換線路失敗: " + e.getMessage();
        }
    }
    
    /**
     * 掛斷指定通話
     */
    public String hangupCall(String extension, String lineId) {
        try {
            MultiLineSession session = extensionSessions.get(extension);
            if (session == null) {
                return "錯誤：分機 " + extension + " 沒有線路會話";
            }
            
            CallLine targetLine;
            if (lineId == null || lineId.isEmpty()) {
                // 如果沒有指定線路，掛斷當前活躍的線路
                targetLine = session.findActiveeLine();
                if (targetLine == null) {
                    return "沒有找到活躍的線路可以掛斷";
                }
            } else {
                targetLine = session.findLineById(lineId);
                if (targetLine == null) {
                    return "錯誤：找不到線路 " + lineId;
                }
            }
            
            if (targetLine.state == LineState.DISCONNECTED) {
                return "線路 " + targetLine.lineId + " 已經斷線";
            }
            
            // 掛斷通話
            if (targetLine.call != null) {
                Connection[] connections = targetLine.call.getConnections();
                for (Connection connection : connections) {
                    if (connection.getAddress().getName().equals(extension)) {
                        connection.disconnect();
                        break;
                    }
                }
            }
            
            targetLine.state = LineState.DISCONNECTED;
            
            // 如果掛斷的是活躍線路，清除活躍線路ID
            if (targetLine.lineId.equals(session.activeLineId)) {
                session.activeLineId = null;
            }
            
            String result = "線路 " + targetLine.lineId + " 已掛斷：" + targetLine.callDirection;
            
            // 清理斷線的線路
            session.removeDisconnectedLines();
            
            System.out.println("[MULTILINE] " + result);
            return result + "\n剩餘活躍線路數：" + session.getActiveLineCount();
            
        } catch (Exception e) {
            System.err.println("[MULTILINE] 掛斷通話失敗: " + e.getMessage());
            e.printStackTrace();
            return "掛斷通話失敗: " + e.getMessage();
        }
    }
    
    /**
     * 掛斷所有通話
     */
    public String hangupAllCalls(String extension) {
        try {
            MultiLineSession session = extensionSessions.get(extension);
            if (session == null) {
                return "分機 " + extension + " 沒有活躍的線路";
            }
            
            int hangupCount = 0;
            List<CallLine> linesToHangup = new ArrayList<>(session.lines);
            
            for (CallLine line : linesToHangup) {
                if (line.state != LineState.DISCONNECTED) {
                    try {
                        if (line.call != null) {
                            Connection[] connections = line.call.getConnections();
                            for (Connection connection : connections) {
                                if (connection.getAddress().getName().equals(extension)) {
                                    connection.disconnect();
                                    break;
                                }
                            }
                        }
                        line.state = LineState.DISCONNECTED;
                        hangupCount++;
                    } catch (Exception e) {
                        System.err.println("[MULTILINE] 掛斷線路 " + line.lineId + " 失敗: " + e.getMessage());
                    }
                }
            }
            
            // 清理所有線路
            session.lines.clear();
            session.activeLineId = null;
            
            System.out.println("[MULTILINE] 分機 " + extension + " 所有線路已清理");
            return "已掛斷 " + hangupCount + " 條線路，所有通話已結束";
            
        } catch (Exception e) {
            System.err.println("[MULTILINE] 掛斷所有通話失敗: " + e.getMessage());
            e.printStackTrace();
            return "掛斷所有通話失敗: " + e.getMessage();
        }
    }
    
    /**
     * 查看分機的線路狀態
     */
    public String getExtensionLineStatus(String extension) {
        MultiLineSession session = extensionSessions.get(extension);
        if (session == null) {
            return "分機 " + extension + " 沒有線路會話";
        }
        
        if (session.lines.isEmpty()) {
            return "分機 " + extension + " 沒有活躍的線路";
        }
        
        StringBuilder status = new StringBuilder();
        status.append("=== 分機 ").append(extension).append(" 多線狀態 ===\n");
        status.append("活躍線路數：").append(session.getActiveLineCount()).append("/").append(session.maxLines).append("\n");
        status.append("當前活躍線路：").append(session.activeLineId != null ? session.activeLineId : "無").append("\n");
        status.append("自動接聽：").append(session.autoAnswerEnabled ? "開啟" : "關閉").append("\n\n");
        
        status.append("線路詳情：\n");
        for (int i = 0; i < session.lines.size(); i++) {
            CallLine line = session.lines.get(i);
            status.append(i + 1).append(". 線路 ").append(line.lineId).append("\n");
            status.append("   對方：").append(line.remoteParty).append("\n");
            status.append("   狀態：").append(line.getStateDescription()).append("\n");
            status.append("   方向：").append(line.callDirection).append("\n");
            status.append("   時長：").append(line.getDurationSeconds()).append("秒\n");
            if (line.lineId.equals(session.activeLineId)) {
                status.append("   >>> 當前活躍線路 <<<\n");
            }
            status.append("\n");
        }
        
        return status.toString();
    }
    
    /**
     * 查看所有分機的多線狀態
     */
    public String getAllMultiLineStatus() {
        if (extensionSessions.isEmpty()) {
            return "目前沒有分機使用多線功能";
        }
        
        StringBuilder status = new StringBuilder("=== 所有分機多線狀態 ===\n");
        for (Map.Entry<String, MultiLineSession> entry : extensionSessions.entrySet()) {
            MultiLineSession session = entry.getValue();
            status.append("分機 ").append(session.extension)
                  .append("：").append(session.getActiveLineCount()).append(" 條線路")
                  .append("（活躍：").append(session.activeLineId != null ? session.activeLineId : "無").append("）\n");
        }
        return status.toString();
    }
    
    /**
     * 設置自動接聽模式
     */
    public String setAutoAnswerMode(String extension, boolean enabled) {
        MultiLineSession session = getOrCreateSession(extension);
        session.autoAnswerEnabled = enabled;
        
        return "分機 " + extension + " 自動接聽模式：" + (enabled ? "已開啟" : "已關閉");
    }
    
    /**
     * 快速操作：Hold當前通話並撥打新電話
     */
    public String holdCurrentAndMakeNewCall(String extension, String target) {
        try {
            MultiLineSession session = getOrCreateSession(extension);
            
            // 先Hold當前通話
            CallLine activeLine = session.findActiveeLine();
            if (activeLine != null) {
                holdLine(session, activeLine);
            }
            
            // 然後撥打新電話
            return makeNewCall(extension, target);
            
        } catch (Exception e) {
            return "Hold並撥打新電話失敗: " + e.getMessage();
        }
    }
    
    /**
     * 在兩條線路間快速切換
     */
    public String toggleBetweenTwoLines(String extension) {
        try {
            MultiLineSession session = extensionSessions.get(extension);
            if (session == null || session.lines.size() < 2) {
                return "需要至少兩條線路才能切換";
            }
            
            CallLine activeLine = session.findActiveeLine();
            List<CallLine> heldLines = session.getHeldLines();
            
            if (heldLines.isEmpty()) {
                return "沒有保持中的線路可以切換";
            }
            
            // 切換到第一條保持中的線路
            return switchToLine(extension, heldLines.get(0).lineId);
            
        } catch (Exception e) {
            return "線路切換失敗: " + e.getMessage();
        }
    }
    
    /**
     * 獲取線路詳細信息
     */
    public String getLineDetails(String extension, String lineId) {
        MultiLineSession session = extensionSessions.get(extension);
        if (session == null) {
            return "分機 " + extension + " 沒有線路會話";
        }
        
        CallLine line = session.findLineById(lineId);
        if (line == null) {
            return "找不到線路 " + lineId;
        }
        
        StringBuilder details = new StringBuilder();
        details.append("=== 線路 ").append(lineId).append(" 詳細信息 ===\n");
        details.append("對方號碼：").append(line.remoteParty).append("\n");
        details.append("通話方向：").append(line.callDirection).append("\n");
        details.append("當前狀態：").append(line.getStateDescription()).append("\n");
        details.append("通話時長：").append(line.getDurationSeconds()).append("秒\n");
        details.append("是否來電：").append(line.isIncoming ? "是" : "否").append("\n");
        details.append("是否活躍：").append(line.lineId.equals(session.activeLineId) ? "是" : "否").append("\n");
        
        // 檢查實際通話狀態
        try {
            if (line.call != null) {
                details.append("\n通話對象狀態：\n");
                details.append("Call狀態：").append(line.call.getState()).append("\n");
                
                Connection[] connections = line.call.getConnections();
                details.append("連線數量：").append(connections.length).append("\n");
                
                for (int i = 0; i < connections.length; i++) {
                    Connection conn = connections[i];
                    details.append("連線").append(i).append("：").append(conn.getAddress().getName())
                           .append(" (").append(getConnectionStateName(conn.getState())).append(")\n");
                }
            }
        } catch (Exception e) {
            details.append("無法獲取詳細通話狀態：").append(e.getMessage()).append("\n");
        }
        
        return details.toString();
    }
    
    /**
     * 清理斷開的線路
     */
    public String cleanupDisconnectedLines(String extension) {
        MultiLineSession session = extensionSessions.get(extension);
        if (session == null) {
            return "分機 " + extension + " 沒有線路會話";
        }
        
        int beforeCount = session.lines.size();
        session.removeDisconnectedLines();
        int afterCount = session.lines.size();
        int removedCount = beforeCount - afterCount;
        
        return "已清理 " + removedCount + " 條斷開的線路，剩餘 " + afterCount + " 條線路";
    }
    
    /**
     * 測試多線能力
     */
    public String testMultiLineCapability(String extension) {
        try {
            var conn = phoneCallService.getExtensionConnection(extension);
            if (conn == null) {
                return "錯誤：分機 " + extension + " 未登入";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("=== 分機 ").append(extension).append(" 多線能力測試 ===\n");
            
            var extensionConn = (PhoneCallService.ExtensionConnection) conn;
            
            // 檢查終端能力
            if (extensionConn.terminal != null) {
                try {
                    TerminalConnection[] termConnections = extensionConn.terminal.getTerminalConnections();
                    result.append("當前終端連線數：").append(termConnections != null ? termConnections.length : 0).append("\n");
                    
                    if (termConnections != null) {
                        for (int i = 0; i < termConnections.length; i++) {
                            TerminalConnection tc = termConnections[i];
                            result.append("連線").append(i).append("：狀態 ")
                                  .append(getTerminalConnectionStateName(tc.getState())).append("\n");
                        }
                    }
                } catch (Exception e) {
                    result.append("檢查終端連線時發生錯誤：").append(e.getMessage()).append("\n");
                }
            }
            
            // 檢查當前會話狀態
            MultiLineSession session = extensionSessions.get(extension);
            if (session != null) {
                result.append("\n當前會話狀態：\n");
                result.append("線路數：").append(session.lines.size()).append("/").append(session.maxLines).append("\n");
                result.append("活躍線路：").append(session.activeLineId).append("\n");
            } else {
                result.append("\n尚未建立多線會話\n");
            }
            
            result.append("\n多線功能支援：✓\n");
            result.append("建議最大線路數：").append(6).append("\n");
            result.append("支援功能：Hold/Unhold、線路切換、同時撥打/接聽\n");
            
            return result.toString();
            
        } catch (Exception e) {
            return "多線能力測試失敗: " + e.getMessage();
        }
    }
    
    // ========================================
    // 輔助方法
    // ========================================
    
    /**
     * 獲取或創建分機的多線會話
     */
    private MultiLineSession getOrCreateSession(String extension) {
        return extensionSessions.computeIfAbsent(extension, MultiLineSession::new);
    }
    
    /**
     * Hold指定線路
     */
    private void holdLine(MultiLineSession session, CallLine line) throws Exception {
        if (line.call != null && line.state == LineState.ACTIVE) {
            Connection[] connections = line.call.getConnections();
            for (Connection connection : connections) {
                if (connection.getAddress().getName().equals(session.extension)) {
                    TerminalConnection[] termConns = connection.getTerminalConnections();
                    for (TerminalConnection termConn : termConns) {
                        if (termConn instanceof CallControlTerminalConnection) {
                            CallControlTerminalConnection ccTermConn = (CallControlTerminalConnection) termConn;
                            if (ccTermConn.getCallControlState() == CallControlTerminalConnection.TALKING) {
                                ccTermConn.hold();
                                line.state = LineState.HELD;
                                if (line.lineId.equals(session.activeLineId)) {
                                    session.activeLineId = null;
                                }
                                System.out.println("[MULTILINE] 線路 " + line.lineId + " 已Hold");
                                return;
                            }
                        }
                    }
                    break;
                }
            }
        }
    }
    
    /**
     * Hold除了指定線路外的所有活躍線路
     */
    private void holdOtherActiveLines(MultiLineSession session, String exceptLineId) {
        for (CallLine line : session.lines) {
            if (!line.lineId.equals(exceptLineId) && line.state == LineState.ACTIVE) {
                try {
                    holdLine(session, line);
                } catch (Exception e) {
                    System.err.println("[MULTILINE] Hold線路 " + line.lineId + " 失敗: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 找到通話中的對方號碼
     */
    private String findRemoteParty(Call call, String localExtension) {
        try {
            Connection[] connections = call.getConnections();
            for (Connection connection : connections) {
                String addressName = connection.getAddress().getName();
                if (!addressName.equals(localExtension)) {
                    return addressName;
                }
            }
        } catch (Exception e) {
            System.err.println("[MULTILINE] 查找對方號碼失敗: " + e.getMessage());
        }
        return "未知";
    }
    
    /**
     * 獲取連線狀態名稱
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
     * 獲取終端連線狀態名稱
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
}