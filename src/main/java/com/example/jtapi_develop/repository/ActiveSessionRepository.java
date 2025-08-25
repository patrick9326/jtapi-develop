package com.example.jtapi_develop.repository;

import com.example.jtapi_develop.entity.ActiveSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActiveSessionRepository extends JpaRepository<ActiveSession, String> {
    // JpaRepository 已經提供了基本的 count(), save(), deleteById() 等方法
    // 我們不需要自己寫 SQL
}