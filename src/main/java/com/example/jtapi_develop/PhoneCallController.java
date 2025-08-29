package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

@RestController
@RequestMapping("/api/phone")
public class PhoneCallController {
    
    @Autowired
    private PhoneCallService phoneCallService;
    
    /**
     * 登入分機 (POST版本 - 安全版本)
     * POST /api/phone/login
     * Body: {"extension": "2510043", "password": "password456"}
     */
    @PostMapping("/login")
    public CompletableFuture<String> login(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        String password = request.get("password");
        
        if (extension == null || password == null) {
            return CompletableFuture.completedFuture("❌ 參數錯誤：需要提供 extension 和 password");
        }
        
        return phoneCallService.loginExtension(extension, password);
    }
    
    /**
     * 撥打電話 (POST版本 - 安全版本)
     * POST /api/phone/call
     * Body: {"caller": "2510043", "callee": "2510044"}
     */
    @PostMapping("/call")
    public String makeCall(@RequestBody Map<String, String> request) {
        String caller = request.get("caller");
        String callee = request.get("callee");
        
        if (caller == null || callee == null) {
            return "❌ 參數錯誤：需要提供 caller 和 callee";
        }
        
        return phoneCallService.makeCall(caller, callee);
    }
    
    /**
     * 接聽電話 (POST版本 - 安全版本)
     * POST /api/phone/answer
     * Body: {"extension": "2510044"}
     */
    @PostMapping("/answer")
    public String answerCall(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
        return phoneCallService.answerCall(extension);
    }
    
    /**
     * 掛斷電話 (POST版本 - 安全版本)
     * POST /api/phone/hangup
     * Body: {"extension": "2510043"}
     */
    @PostMapping("/hangup")
    public String hangupCall(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
        return phoneCallService.hangupCall(extension);
    }
    
    /**
     * 登出分機 (POST版本 - 安全版本)
     * POST /api/phone/logout
     * Body: {"extension": "2510043"}
     */
    @PostMapping("/logout")
    public String logout(@RequestBody Map<String, String> request) {
        String extension = request.get("extension");
        
        if (extension == null) {
            return "❌ 參數錯誤：需要提供 extension";
        }
        
        return phoneCallService.logoutExtension(extension);
    }
    
    /**
     * 查看所有分機狀態
     * GET /api/phone/status
     */
    @GetMapping("/status")
    public String getStatus() {
        return phoneCallService.getExtensionStatus();
    }
    
    /**
     * 強制設置監聽器
     * POST /api/phone/force-setup-listener?extension=1420
     */
    @PostMapping("/force-setup-listener")
    public String forceSetupListener(@RequestParam String extension) {
        return phoneCallService.forceSetupListener(extension);
    }
}