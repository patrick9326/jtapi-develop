package com.example.jtapi_develop;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Map;

@Service
public class SseService {

    // 使用 ConcurrentHashMap 儲存每個分機對應的 SseEmitter
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    
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
}