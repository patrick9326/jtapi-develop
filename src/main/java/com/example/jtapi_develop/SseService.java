package com.example.jtapi_develop;

import com.example.jtapi_develop.repository.MonitorPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.List;

@Service
public class SseService {

    // 使用 ConcurrentHashMap 儲存每個分機對應的 SseEmitter
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    
    @Autowired
    private MonitorPermissionRepository monitorPermissionRepository;
    
    // 心跳定時器
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1);
    
    public SseService() {
        // 每60秒發送心跳給所有連接的客戶端
        heartbeatScheduler.scheduleWithFixedDelay(this::sendHeartbeats, 60, 60, TimeUnit.SECONDS);
        System.out.println("[SSE] 心跳服務已啟動，每60秒發送一次心跳");
    }

    /**
     * 新增一個 Emitter (當前端連接時調用)
     * @param extension 分機號
     * @return SseEmitter 實例
     */
    public SseEmitter addEmitter(String extension) {
        // 設置超時時間為 1 小時，避免連接過早斷開
        SseEmitter emitter = new SseEmitter(3600_000L);
        
        // 設置 Emitter 的完成和超時回調，以便在斷開時從 Map 中移除
        emitter.onCompletion(() -> {
            emitters.remove(extension);
            System.out.println("[SSE] 分機 " + extension + " SSE連接已完成");
        });
        emitter.onTimeout(() -> {
            emitters.remove(extension);
            System.out.println("[SSE] 分機 " + extension + " SSE連接超時");
        });
        emitter.onError(e -> {
            emitters.remove(extension);
            System.err.println("[SSE] 分機 " + extension + " SSE連接錯誤: " + e.getMessage());
        });

        // 加入到連接Map
        emitters.put(extension, emitter);
        
        try {
            // 立即發送連接確認訊息
            emitter.send(SseEmitter.event()
                .name("connection")
                .data("{\"status\":\"connected\",\"extension\":\"" + extension + "\",\"timestamp\":" + System.currentTimeMillis() + "}"));
            System.out.println("[SSE] 分機 " + extension + " 已連接 SSE，已發送確認訊息");
        } catch (IOException e) {
            System.err.println("[SSE] 發送連接確認失敗: " + e.getMessage());
            emitters.remove(extension);
        }
        
        return emitter;
    }

    /**
     * 向指定分機發送事件
     * @param extension 分機號
     * @param eventName 事件名稱
     * @param data 事件數據
     */
    public void sendEvent(String extension, String eventName, Object data) {
        SseEmitter emitter = emitters.get(extension);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
                System.out.println("[SSE] 已向分機 " + extension + " 推送事件: " + eventName);
            } catch (IOException e) {
                System.err.println("[SSE] 向分機 " + extension + " 推送事件失敗: " + e.getMessage());
                emitters.remove(extension);
            }
        }
    }
    
    /**
     * 發送心跳給所有連接的客戶端
     */
    private void sendHeartbeats() {
        emitters.entrySet().removeIf(entry -> {
            String extension = entry.getKey();
            SseEmitter emitter = entry.getValue();
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                System.out.println("[SSE] 💓 向分機 " + extension + " 發送心跳");
                return false; // 保留此連接
            } catch (IOException e) {
                System.err.println("[SSE] 💔 向分機 " + extension + " 發送心跳失敗，移除連接: " + e.getMessage());
                return true; // 移除失效連接
            }
        });
        
        System.out.println("[SSE] 💓 心跳檢查完成，目前活躍連接數: " + emitters.size());
    }
    
    /**
     * 獲取目前連接數量
     */
    public int getActiveConnectionCount() {
        return emitters.size();
    }
    
    /**
     * 獲取所有活躍連接的分機號碼
     */
    public String[] getActiveExtensions() {
        return emitters.keySet().toArray(new String[0]);
    }
    
    /**
     * 廣播通話狀態變化給有權限的監聽者
     * @param targetExtension 發生通話狀態變化的分機
     * @param eventData 事件資料
     */
    public void broadcastCallStatusChange(String targetExtension, Object eventData) {
        try {
            System.out.println("[SSE_BROADCAST] 開始廣播 " + targetExtension + " 的狀態變化");
            
            // 查詢所有可以監聽此分機的用戶ID
            List<String> supervisorIds = monitorPermissionRepository.findSupervisorsByTargetExtension(targetExtension);
            System.out.println("[SSE_BROADCAST] 找到 " + supervisorIds.size() + " 個有權限的監聽者ID: " + supervisorIds);
            
            if (supervisorIds.isEmpty()) {
                System.out.println("[SSE_BROADCAST] 沒有找到有權限的監聽者，跳過廣播");
                return;
            }
            
            int broadcastCount = 0;
            // 遍歷所有活躍連接，找到屬於有權限監聽者的連接
            for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
                String connectedExt = entry.getKey();
                SseEmitter emitter = entry.getValue();
                
                // 這裡假設分機號就是用戶ID（根據你的系統設計）
                // 如果你的系統中分機號和用戶ID不同，需要調整這裡
                boolean hasPermission = false;
                for (String supervisorId : supervisorIds) {
                    if (supervisorId.equals(connectedExt)) {
                        hasPermission = true;
                        break;
                    }
                }
                
                if (hasPermission) {
                    try {
                        emitter.send(SseEmitter.event().name("call_status_change").data(eventData));
                        System.out.println("[SSE_BROADCAST] ✅ 已向有權限監聽者 " + connectedExt + " 推送 " + targetExtension + " 的狀態變化");
                        broadcastCount++;
                    } catch (IOException e) {
                        System.err.println("[SSE_BROADCAST] ❌ 向 " + connectedExt + " 推送失敗: " + e.getMessage());
                        emitters.remove(connectedExt);
                    }
                } else {
                    System.out.println("[SSE_BROADCAST] 跳過無權限的連接: " + connectedExt);
                }
            }
            
            System.out.println("[SSE_BROADCAST] 廣播完成，成功推送給 " + broadcastCount + " 個有權限的連接");
        } catch (Exception e) {
            System.err.println("[SSE_BROADCAST] 廣播失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }
}