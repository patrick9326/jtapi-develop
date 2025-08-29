package com.example.jtapi_develop;

import com.example.jtapi_develop.repository.MonitorPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

@RestController
@RequestMapping("/api/monitor-setup")
public class MonitorSetupController {
    
    @Autowired
    private PhoneCallService phoneCallService;
    
    @Autowired
    private MonitorPermissionRepository monitorPermissionRepository;
    
    /**
     * 為所有可被監聽的分機設置 JTAPI 監聽器
     */
    @PostMapping("/setup-all-listeners")
    public String setupAllMonitorListeners() {
        try {
            // 從數據庫獲取所有被監聽的分機
            List<String> allTargetExtensions = getAllTargetExtensions();
            
            if (allTargetExtensions.isEmpty()) {
                return "❌ 沒有找到任何可被監聽的分機";
            }
            
            int successCount = 0;
            StringBuilder result = new StringBuilder();
            result.append("🔧 開始為所有可被監聽的分機設置 JTAPI 監聽器:\n\n");
            
            for (String extension : allTargetExtensions) {
                try {
                    phoneCallService.setupListenerForExtension(extension);
                    result.append("✅ 分機 ").append(extension).append(" - 監聽器設置成功\n");
                    successCount++;
                } catch (Exception e) {
                    result.append("❌ 分機 ").append(extension).append(" - 設置失敗: ").append(e.getMessage()).append("\n");
                }
            }
            
            result.append("\n📊 設置完成: ").append(successCount).append("/").append(allTargetExtensions.size()).append(" 成功");
            result.append("\n\n🎯 現在這些分機的通話狀態變化會自動推送 SSE 事件到監聽列表");
            
            return result.toString();
            
        } catch (Exception e) {
            return "❌ 設置監聽器失敗: " + e.getMessage();
        }
    }
    
    /**
     * 獲取所有可被監聽的分機號碼（公開方法）
     */
    public List<String> getAllTargetExtensions() {
        return monitorPermissionRepository.findAllTargetExtensions();
    }
}