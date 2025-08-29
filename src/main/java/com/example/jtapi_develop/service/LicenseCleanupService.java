package com.example.jtapi_develop.service;

import com.example.jtapi_develop.entity.ActiveSession;
import com.example.jtapi_develop.repository.ActiveSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class LicenseCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(LicenseCleanupService.class);
    
    // 定義超時時間（秒），例如超過 90 秒沒有心跳就視為過期
    private static final long SESSION_TIMEOUT_SECONDS = 40;

    @Autowired
    private ActiveSessionRepository activeSessionRepository;

    // fixedRate=60000 代表每 60 秒執行一次這個任務
    @Scheduled(fixedRate = 20000)
    public void cleanupExpiredSessions() {
        logger.info("Running scheduled license cleanup task...");
        
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusSeconds(SESSION_TIMEOUT_SECONDS);
        
        List<ActiveSession> expiredSessions = activeSessionRepository.findAllByLastHeartbeatTimeBefore(timeoutThreshold);
        
        if (!expiredSessions.isEmpty()) {
            logger.warn("Found {} expired sessions to clean up.", expiredSessions.size());
            for (ActiveSession session : expiredSessions) {
                logger.warn("Removing expired session for user: {}, Session ID: {}", session.getUserId(), session.getSessionId());
                activeSessionRepository.delete(session);
            }
        } else {
            logger.info("No expired sessions found.");
        }
    }
}