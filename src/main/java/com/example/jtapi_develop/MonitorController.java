package com.example.jtapi_develop;

import com.example.jtapi_develop.repository.MonitorPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {
    
    @Autowired
    private MonitorService monitorService;
    
    @Autowired
    private MonitorPermissionRepository monitorPermissionRepository;
    
    /**
     * 獲取用戶可監聽的分機列表
     * GET /api/monitor/permissions?supervisorId=2420
     */
    @GetMapping("/permissions")
    public List<String> getMonitorPermissions(@RequestParam String supervisorId) {
        return monitorPermissionRepository.findTargetExtensionsBySupervisorIdNative(supervisorId);
    }
    
    /**
     * 查詢可監聽的通話
     * GET /api/monitor/available-calls?supervisorExtension=1001
     */
    @GetMapping("/available-calls")
    public String getAvailableCalls(@RequestParam String supervisorExtension) {
        return monitorService.getAvailableCalls(supervisorExtension);
    }
    
    /**
     * 開始監聽通話 (Silent Monitor)
     * GET /api/monitor/start?supervisorExtension=1001&targetExtension=1420
     */
    @GetMapping("/start")
    public String startMonitoring(@RequestParam String supervisorExtension,
                                 @RequestParam String targetExtension) {
        return monitorService.startMonitoring(supervisorExtension, targetExtension);
    }
    
    /**
     * 停止監聽通話
     * GET /api/monitor/stop?supervisorExtension=1001
     */
    @GetMapping("/stop")
    public String stopMonitoring(@RequestParam String supervisorExtension) {
        return monitorService.stopMonitoring(supervisorExtension);
    }
    
    /**
     * 闖入通話 (Barge-in)
     * GET /api/monitor/barge-in?supervisorExtension=1001&targetExtension=1420
     */
    @GetMapping("/barge-in")
    public String bargeInCall(@RequestParam String supervisorExtension,
                             @RequestParam String targetExtension) {
        return monitorService.bargeInCall(supervisorExtension, targetExtension);
    }
    
    /**
     * 教練模式 (只對 Agent 說話，客戶聽不到)
     * GET /api/monitor/coach?supervisorExtension=1001&targetExtension=1420
     */
    @GetMapping("/coach")
    public String coachAgent(@RequestParam String supervisorExtension,
                            @RequestParam String targetExtension) {
        return monitorService.coachAgent(supervisorExtension, targetExtension);
    }
    
    /**
     * 查看監聽狀態
     * GET /api/monitor/status?supervisorExtension=1001
     */
    @GetMapping("/status")
    public String getMonitorStatus(@RequestParam String supervisorExtension) {
        return monitorService.getMonitorStatus(supervisorExtension);
    }
    
    /**
     * 查看所有監聽中的會話
     * GET /api/monitor/all-sessions
     */
    @GetMapping("/all-sessions")
    public String getAllMonitorSessions() {
        return monitorService.getAllMonitorSessions();
    }
    
    /**
     * 掛斷監聽/闖入通話
     * GET /api/monitor/hangup?supervisorExtension=1001
     */
    @GetMapping("/hangup")
    public String hangupMonitorCall(@RequestParam String supervisorExtension) {
        return monitorService.hangupMonitorCall(supervisorExtension);
    }
}