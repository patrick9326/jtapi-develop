package com.example.jtapi_develop.service;
import com.example.jtapi_develop.entity.ActiveSession;
import com.example.jtapi_develop.repository.ActiveSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Optional; // 引入 Optional
import java.time.LocalDateTime;
import java.util.UUID;
@Service
public class LicenseService {
    private static final int MAX_CONCURRENT_USERS = 20;
    @Autowired
    private ActiveSessionRepository activeSessionRepository;
    /**
     * 更新一個授權的心跳時間
     * @param sessionId 要更新的 session ID
     */
    public void updateHeartbeat(String sessionId) {
        Optional<ActiveSession> sessionOptional = activeSessionRepository.findById(sessionId);
        if (sessionOptional.isPresent()) {
            ActiveSession session = sessionOptional.get();
            session.setLastHeartbeatTime(LocalDateTime.now());
            activeSessionRepository.save(session);
        }
    }
    /**
     * 嘗試為使用者取得一個授權 (登入)
     * @param userId 使用者 ID
     * @return 如果成功，返回一個新的 session ID；如果失敗 (人數已滿)，返回 null
     */
    public String acquireLicense(String userId) {
        long currentUsers = activeSessionRepository.count();

        if (currentUsers < MAX_CONCURRENT_USERS) {
            String sessionId = UUID.randomUUID().toString();
            ActiveSession newSession = new ActiveSession(sessionId, userId, LocalDateTime.now(),LocalDateTime.now());
            activeSessionRepository.save(newSession);
            return sessionId;
        } else {
            // 人數已滿，拒絕登入
            return null;
        }
    }
    /**
     * 釋放一個授權 (登出)
     * @param sessionId 要釋放的 session ID
     */
    public void releaseLicense(String sessionId) {
        activeSessionRepository.deleteById(sessionId);
    }
}