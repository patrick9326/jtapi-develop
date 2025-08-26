package com.example.jtapi_develop.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "active_sessions")
public class ActiveSession {

    @Id
    private String sessionId;
    private String userId;
    private LocalDateTime loginTime;

    private LocalDateTime lastHeartbeatTime;

    // Constructors, Getters and Setters
    public ActiveSession() {
    }

    public ActiveSession(String sessionId, String userId, LocalDateTime loginTime,LocalDateTime lastHeartbeatTime) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.loginTime = loginTime;
        this.lastHeartbeatTime = LocalDateTime.now(); // 登入時，就當作是第一次心跳
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public LocalDateTime getLoginTime() {
        return loginTime;
    }

    public void setLoginTime(LocalDateTime loginTime) {
        this.loginTime = loginTime;
    }

     public LocalDateTime getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public void setLastHeartbeatTime(LocalDateTime lastHeartbeatTime) {
        this.lastHeartbeatTime = lastHeartbeatTime;
    }
}