package com.example.jtapi_develop.repository;

import com.example.jtapi_develop.entity.ActiveSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime; // 引入
import java.util.List; // 引入

@Repository
public interface ActiveSessionRepository extends JpaRepository<ActiveSession, String> {
    // Spring Data JPA 會自動根據方法名稱產生 SQL 查詢
    List<ActiveSession> findAllByLastHeartbeatTimeBefore(LocalDateTime timeoutTime);
}