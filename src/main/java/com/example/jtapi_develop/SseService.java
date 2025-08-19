package com.example.jtapi_develop;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
public class SseService {

    // 使用 ConcurrentHashMap 儲存每個分機對應的 SseEmitter
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 新增一個 Emitter (當前端連接時調用)
     * @param extension 分機號
     * @return SseEmitter 實例
     */
    public SseEmitter addEmitter(String extension) {
        // 設置超時時間為 1 小時，避免連接過早斷開
        SseEmitter emitter = new SseEmitter(3600_000L);
        emitters.put(extension, emitter);

        // 設置 Emitter 的完成和超時回調，以便在斷開時從 Map 中移除
        emitter.onCompletion(() -> emitters.remove(extension));
        emitter.onTimeout(() -> emitters.remove(extension));
        emitter.onError(e -> emitters.remove(extension));

        System.out.println("[SSE] 分機 " + extension + " 已連接 SSE");
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
}