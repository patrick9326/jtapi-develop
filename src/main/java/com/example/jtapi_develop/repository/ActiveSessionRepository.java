package com.example.jtapi_develop.repository;

import com.example.jtapi_develop.entity.ActiveSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ActiveSessionRepository extends JpaRepository<ActiveSession, String> {
    List<ActiveSession> findAllByLastHeartbeatTimeBefore(LocalDateTime timeoutTime);
    long countByRoleName(String roleName); // 新增方法
}