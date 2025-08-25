package com.example.jtapi_develop.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "ActiveSessions")
public class ActiveSession {

    @Id
    private String sessionId;
    private String userId;
    private LocalDateTime loginTime;

    // Constructors, Getters and Setters
    public ActiveSession() {
    }

    public ActiveSession(String sessionId, String userId, LocalDateTime loginTime) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.loginTime = loginTime;
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
}