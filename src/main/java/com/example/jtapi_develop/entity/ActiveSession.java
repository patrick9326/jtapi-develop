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
    private String roleName; // 新增欄位
    private LocalDateTime loginTime;
    private LocalDateTime lastHeartbeatTime;

    public ActiveSession() {
    }

    public ActiveSession(String sessionId, String userId, String roleName, LocalDateTime loginTime, LocalDateTime lastHeartbeatTime) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.roleName = roleName; // 初始化
        this.loginTime = loginTime;
        this.lastHeartbeatTime = lastHeartbeatTime;
    }

    // Getters and Setters
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public LocalDateTime getLoginTime() { return loginTime; }
    public void setLoginTime(LocalDateTime loginTime) { this.loginTime = loginTime; }
    public LocalDateTime getLastHeartbeatTime() { return lastHeartbeatTime; }
    public void setLastHeartbeatTime(LocalDateTime lastHeartbeatTime) { this.lastHeartbeatTime = lastHeartbeatTime; }
}