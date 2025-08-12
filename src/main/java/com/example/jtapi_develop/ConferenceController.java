package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import javax.telephony.*;
import javax.telephony.callcontrol.*;

@RestController
@RequestMapping("/api/conference")
public class ConferenceController {
    
    @Autowired
    private ConferenceService conferenceService;
    
    /**
     * 發起三方通話 - 步驟1：邀請第三方加入
     * GET /api/conference/start?host=1420&invited=1425
     */
    @GetMapping("/start")
    public String startConference(@RequestParam String host,
                                 @RequestParam String invited) {
        return conferenceService.startConference(host, invited);
    }
    
    /**
     * 建立三方會議 - 步驟2：所有人加入會議
     * GET /api/conference/establish?host=1420
     */
    @GetMapping("/establish")
    public String establishConference(@RequestParam String host) {
        return conferenceService.establishConference(host);
    }
    
    /**
     * 結束會議 - 所有人都斷線
     * GET /api/conference/end?host=1420
     */
    @GetMapping("/end")
    public String endConference(@RequestParam String host) {
        return conferenceService.endConference(host);
    }
    
    /**
     * 主持人退出會議 - 讓其他人繼續通話
     * GET /api/conference/leave?host=1420
     */
    @GetMapping("/leave")
    public String leaveConference(@RequestParam String host) {
        return conferenceService.leaveConference(host);
    }
    
    /**
     * 取消會議邀請 - 在建立會議前取消
     * GET /api/conference/cancel?host=1420
     */
    @GetMapping("/cancel")
    public String cancelConferenceInvitation(@RequestParam String host) {
        return conferenceService.cancelConferenceInvitation(host);
    }
    
    /**
     * 查看所有會議狀態
     * GET /api/conference/status
     */
    @GetMapping("/status")
    public String getConferenceStatus() {
        return conferenceService.getConferenceStatus();
    }
    
    /**
     * 查看特定分機的會議狀態
     * GET /api/conference/status-by-extension?extension=1420
     */
    @GetMapping("/status-by-extension")
    public String getConferenceStatusByExtension(@RequestParam String extension) {
        return conferenceService.getConferenceStatusByExtension(extension);
    }
    
    /**
     * 清理過期會議
     * GET /api/conference/cleanup
     */
    @GetMapping("/cleanup")
    public String cleanupExpiredConferences() {
        conferenceService.cleanupExpiredConferences();
        return "過期會議清理完成";
    }
    
    /**
     * 檢查會議準備狀態
     * GET /api/conference/validate-readiness?host=1420
     */
    @GetMapping("/validate-readiness")
    public String validateConferenceReadiness(@RequestParam String host) {
        return conferenceService.validateConferenceReadiness(host);
    }
    
    /**
     * 強制修正會議狀態並建立
     * GET /api/conference/force-establish?host=1420
     */
    @GetMapping("/force-establish")
    public String forceEstablishConference(@RequestParam String host) {
        return conferenceService.establishConference(host);
    }
    
    /**
     * 詳細的通話狀態檢查
     * GET /api/conference/check-calls?host=1420
     */
    @GetMapping("/check-calls")
    public String checkCallStates(@RequestParam String host) {
        try {
            String sessionId = conferenceService.extensionToSessionMap.get(host);
            if (sessionId == null) {
                return "沒有進行中的會議會話";
            }
            
            ConferenceService.ConferenceSession session = conferenceService.activeSessions.get(sessionId);
            if (session == null) {
                return "會議會話不存在";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("=== 詳細通話狀態檢查 ===\n");
            
            // 檢查原始通話的詳細狀態
            if (session.originalCall != null) {
                result.append("\n--- 原始通話狀態 ---\n");
                result.append("通話物件: ").append(session.originalCall.getClass().getSimpleName()).append("\n");
                result.append("通話狀態: ").append(session.originalCall.getState()).append("\n");
                
                try {
                    Connection[] connections = session.originalCall.getConnections();
                    result.append("連線數量: ").append(connections.length).append("\n");
                    
                    for (int i = 0; i < connections.length; i++) {
                        Connection conn = connections[i];
                        result.append("連線 ").append(i).append(": ")
                              .append(conn.getAddress().getName())
                              .append(" - 狀態: ").append(conferenceService.getConnectionStateName(conn.getState())).append("\n");
                        
                        // 檢查終端連線
                        TerminalConnection[] termConns = conn.getTerminalConnections();
                        for (int j = 0; j < termConns.length; j++) {
                            TerminalConnection tc = termConns[j];
                            result.append("  終端 ").append(j).append(": ")
                                  .append(conferenceService.getTerminalConnectionStateName(tc.getState()));
                            
                            if (tc instanceof CallControlTerminalConnection) {
                                CallControlTerminalConnection cctc = (CallControlTerminalConnection) tc;
                                result.append(" (CC: ")
                                      .append(conferenceService.getCallControlStateName(cctc.getCallControlState()))
                                      .append(")");
                            }
                            result.append("\n");
                        }
                    }
                } catch (Exception e) {
                    result.append("檢查原始通話時發生錯誤: ").append(e.getMessage()).append("\n");
                }
            }
            
            // 檢查諮詢通話的詳細狀態
            if (session.consultCall != null) {
                result.append("\n--- 諮詢通話狀態 ---\n");
                result.append("通話物件: ").append(session.consultCall.getClass().getSimpleName()).append("\n");
                result.append("通話狀態: ").append(session.consultCall.getState()).append("\n");
                
                try {
                    Connection[] connections = session.consultCall.getConnections();
                    result.append("連線數量: ").append(connections.length).append("\n");
                    
                    for (int i = 0; i < connections.length; i++) {
                        Connection conn = connections[i];
                        result.append("連線 ").append(i).append(": ")
                              .append(conn.getAddress().getName())
                              .append(" - 狀態: ").append(conferenceService.getConnectionStateName(conn.getState())).append("\n");
                    }
                } catch (Exception e) {
                    result.append("檢查諮詢通話時發生錯誤: ").append(e.getMessage()).append("\n");
                }
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return "檢查通話狀態失敗: " + e.getMessage();
        }
    }
}