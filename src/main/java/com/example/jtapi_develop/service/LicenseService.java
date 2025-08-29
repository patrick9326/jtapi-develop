package com.example.jtapi_develop.service;

import com.example.jtapi_develop.entity.ActiveSession;
import com.example.jtapi_develop.entity.Role;
import com.example.jtapi_develop.entity.User;
import com.example.jtapi_develop.repository.ActiveSessionRepository;
import com.example.jtapi_develop.repository.RoleRepository;
import com.example.jtapi_develop.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

@Service
public class LicenseService {

    private static final int MAX_CONCURRENT_USERS = 20;

    @Autowired
    private ActiveSessionRepository activeSessionRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private RoleRepository roleRepository;

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
     * @return 如果成功，返回一個新的 session ID；如果失敗，返回 null
     */
    public String acquireLicense(String userId) {
        // 獲取用戶角色
        Optional<User> userOpt = userRepository.findById(userId);
        if (!userOpt.isPresent()) {
            return null; // 用戶不存在
        }
        
        User user = userOpt.get();
        String userRole = user.getRoleName();
        
        // 獲取角色設定
        Optional<Role> roleOpt = roleRepository.findById(userRole);
        if (!roleOpt.isPresent()) {
            return null; // 角色不存在
        }
        
        Role role = roleOpt.get();
        long currentUsers = activeSessionRepository.count();
        
        // 檢查是否可以登入
        if (canUserLogin(userRole, role, currentUsers)) {
            String sessionId = UUID.randomUUID().toString();
            ActiveSession newSession = new ActiveSession(sessionId, userId, userRole, LocalDateTime.now(), LocalDateTime.now());
            activeSessionRepository.save(newSession);
            return sessionId;
        } else {
            return null;
        }
    }
    
    /**
     * 檢查用戶是否可以登入
     */
    private boolean canUserLogin(String userRole, Role role, long currentUsers) {
        List<ActiveSession> sessions = activeSessionRepository.findAll();
        long directorCount = sessions.stream().filter(s -> "director".equals(s.getUserRole())).count();
        long staffCount = sessions.stream().filter(s -> "staff".equals(s.getUserRole())).count();
        
        Role directorRole = roleRepository.findById("director").orElse(null);
        int guaranteedForDirector = (directorRole != null) ? directorRole.getGuaranteedLicenses() : 2;
        
        if ("director".equals(userRole)) {
            // Director 可以無限使用，但至少保證2個名額
            return currentUsers < MAX_CONCURRENT_USERS;
        } else {
            // Staff 的可用名額 = 總名額 - 已使用的 Director 名額
            // 但 Director 至少要保留 guaranteedForDirector 個名額
            long maxStaffSlots = MAX_CONCURRENT_USERS - Math.max(directorCount, guaranteedForDirector);
            return staffCount < maxStaffSlots && currentUsers < MAX_CONCURRENT_USERS;
        }
    }

    /**
     * 釋放一個授權 (登出)
     * @param sessionId 要釋放的 session ID
     */
    public void releaseLicense(String sessionId) {
        activeSessionRepository.deleteById(sessionId);
    }
    
    /**
     * 獲取用戶角色
     */
    public String getUserRole(String userId) {
        return userRepository.findById(userId)
                .map(User::getRoleName)
                .orElse("staff"); // 預設為 staff
    }
}