package com.example.jtapi_develop.controller;

import com.example.jtapi_develop.service.LicenseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity; 
import java.util.Map;

@RestController
@RequestMapping("/api/license")
public class LicenseController {

    @Autowired
    private LicenseService licenseService;

    @PostMapping("/heartbeat")
    public ResponseEntity<String> heartbeat(@RequestBody Map<String, String> payload) {
        String sessionId = payload.get("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body("Session ID is required.");
        }
        licenseService.updateHeartbeat(sessionId);
        return ResponseEntity.ok("Heartbeat received.");
    }

   @PostMapping("/login")
public ResponseEntity<?> login(@RequestBody Map<String, String> payload) {
    String userId = payload.get("userId");
    if (userId == null || userId.isEmpty()) {
        return ResponseEntity.badRequest().body("User ID is required.");
    }

    Map<String, String> result = licenseService.acquireLicense(userId);

    if (result != null && !result.isEmpty()) {
        // 登入成功，回傳包含 sessionId 和 role 的 JSON 物件
        return ResponseEntity.ok(result);
    } else {
        // 登入失敗
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("License limit reached or user/role not found. Cannot log in.");
    }
}

    @PostMapping("/logout")
    public ResponseEntity<String> logout(@RequestBody Map<String, String> payload) {
         String sessionId = payload.get("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body("Session ID is required.");
        }
        licenseService.releaseLicense(sessionId);
        return ResponseEntity.ok("Logout successful.");
    }
}