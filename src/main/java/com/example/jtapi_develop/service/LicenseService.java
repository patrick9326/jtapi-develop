package com.example.jtapi_develop.service;

import com.example.jtapi_develop.entity.ActiveSession;
import com.example.jtapi_develop.entity.Role;
import com.example.jtapi_develop.entity.User;
import com.example.jtapi_develop.repository.ActiveSessionRepository;
import com.example.jtapi_develop.repository.RoleRepository;
import com.example.jtapi_develop.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class LicenseService {

    @Value("${license.total:20}")
    private int totalLicenses;

    @Autowired
    private ActiveSessionRepository activeSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    public void updateHeartbeat(String sessionId) {
        activeSessionRepository.findById(sessionId).ifPresent(session -> {
            session.setLastHeartbeatTime(LocalDateTime.now());
            activeSessionRepository.save(session);
        });
    }

    public Map<String, String> acquireLicense(String userId) {
        Optional<User> userOptional = userRepository.findById(userId);
        if (userOptional.isEmpty()) {
            return null;
        }
        User user = userOptional.get();
        String userRoleName = user.getRoleName();

        Optional<Role> roleOptional = roleRepository.findById("director");
        if (roleOptional.isEmpty()) {
            return null;
        }
        int directorGuaranteed = roleOptional.get().getGuaranteedLicenses();

        long totalActive = activeSessionRepository.count();

        if (totalActive >= totalLicenses) {
            return null;
        }

        boolean canLogin = false;
        if ("director".equalsIgnoreCase(userRoleName)) {
            canLogin = true;
        } else if ("staff".equalsIgnoreCase(userRoleName)) {
            long activeStaff = activeSessionRepository.countByRoleName("staff");
            int maxAllowedStaff = totalLicenses - directorGuaranteed;
            if (activeStaff < maxAllowedStaff) {
                canLogin = true;
            }
        }

        if (canLogin) {
            String sessionId = UUID.randomUUID().toString();
            ActiveSession newSession = new ActiveSession(sessionId, user.getUserId(), user.getRoleName(), LocalDateTime.now(), LocalDateTime.now());
            activeSessionRepository.save(newSession);

            Map<String, String> result = new HashMap<>();
            result.put("sessionId", sessionId);
            result.put("role", user.getRoleName());
            return result;
        } else {
            return null;
        }
    }

    public void releaseLicense(String sessionId) {
        activeSessionRepository.deleteById(sessionId);
    }
}