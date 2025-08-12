package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/phone")
public class PhoneCallController {
    
    @Autowired
    private PhoneCallService phoneCallService;
    
    /**
     * 登入分機
     * GET /api/phone/login?extension=2510043&password=password456
     */
    @GetMapping("/login")
    public CompletableFuture<String> login(@RequestParam String extension,
                                         @RequestParam String password) {
        return phoneCallService.loginExtension(extension, password);
    }
    
    /**
     * 撥打電話
     * GET /api/phone/call?caller=2510043&callee=2510044
     */
    @GetMapping("/call")
    public String makeCall(@RequestParam String caller,
                          @RequestParam String callee) {
        return phoneCallService.makeCall(caller, callee);
    }
    
    /**
     * 接聽電話
     * GET /api/phone/answer?extension=2510044
     */
    @GetMapping("/answer")
    public String answerCall(@RequestParam String extension) {
        return phoneCallService.answerCall(extension);
    }
    
    /**
     * 掛斷電話
     * GET /api/phone/hangup?extension=2510043
     */
    @GetMapping("/hangup")
    public String hangupCall(@RequestParam String extension) {
        return phoneCallService.hangupCall(extension);
    }
    
    /**
     * 登出分機
     * GET /api/phone/logout?extension=2510043
     */
    @GetMapping("/logout")
    public String logout(@RequestParam String extension) {
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
}