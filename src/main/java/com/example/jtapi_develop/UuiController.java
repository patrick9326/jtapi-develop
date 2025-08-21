// 檔案: src/main/java/com/example/jtapi_develop/UuiController.java

package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/uui")
public class UuiController {

    @Autowired
    private UuiService uuiService;

    /**
     * 獲取指定分機當前來電的 UUI 資料
     * GET /api/uui/data?extension=1420
     */
    @GetMapping("/data")
    public Map<String, Object> getUuiData(@RequestParam String extension) {
        UuiService.UuiData uuiData = uuiService.getUuiData(extension);
        Map<String, Object> response = new HashMap<>();

        if (uuiData != null) {
            response.put("status", "success");
            response.put("extension", extension);
            response.put("rawData", uuiData.rawData);
            response.put("decodedData", uuiData.decodedData);
            response.put("parsedFields", uuiData.parsedFields);
            response.put("callerName", uuiData.callerName);
            response.put("callerNumber", uuiData.callerNumber);
            response.put("calledNumber", uuiData.calledNumber);
            response.put("timestamp", uuiData.timestamp);
        } else {
            response.put("status", "not_found");
            response.put("message", "分機 " + extension + " 目前沒有 UUI 資料。");
        }
        return response;
    }

    /**
     * 測試 UUI 功能 - 為指定分機創建模擬的 UUI 資料
     * GET /api/uui/test?extension=1420
     */
    @GetMapping("/test")
    public Map<String, Object> testUuiData(@RequestParam String extension) {
        // 創建測試用的 UUI 資料 (模擬來電的客戶資訊)
        String testData = "id=CUST12345;queue=SALES;lang=ZH-TW;ivr_path=/main/sales/vip;ticket_id=TKT789";
        
        // 將測試資料轉為HEX格式 (模擬真實的UUI資料傳輸)
        StringBuilder hexString = new StringBuilder();
        for (byte b : testData.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hexString.append(String.format("%02x", b));
        }
        
        // 使用 UuiService 的 UuiData 類來處理測試資料
        UuiService.UuiData testUuiData = new UuiService.UuiData(hexString.toString());
        
        // 儲存測試資料到服務中
        uuiService.getUuiStore().put(extension, testUuiData);
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "已為分機 " + extension + " 創建測試 UUI 資料");
        response.put("testData", testData);
        response.put("parsedFields", testUuiData.parsedFields);
        
        return response;
    }

    /**
     * 清除指定分機的 UUI 資料
     * GET /api/uui/clear?extension=1420
     */
    @GetMapping("/clear")
    public Map<String, Object> clearUuiData(@RequestParam String extension) {
        uuiService.clearUuiData(extension);
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "已清除分機 " + extension + " 的 UUI 資料");
        
        return response;
    }

    /**
     * 測試後端 UUI 處理邏輯 - 模擬 RINGING 事件
     * GET /api/uui/test-backend?extension=1420
     */
    @GetMapping("/test-backend")
    public Map<String, Object> testBackendUuiProcessing(@RequestParam String extension) {
        try {
            // 先創建測試資料
            String testData = "id=BACKEND_TEST;queue=TEST_QUEUE;caller_id=12345678";
            StringBuilder hexString = new StringBuilder();
            for (byte b : testData.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                hexString.append(String.format("%02x", b));
            }
            
            // 直接儲存到 UUI 服務中（模擬後端處理）
            UuiService.UuiData testUuiData = new UuiService.UuiData(hexString.toString());
            uuiService.getUuiStore().put(extension, testUuiData);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "後端 UUI 處理測試完成");
            response.put("extension", extension);
            response.put("testData", testData);
            response.put("parsedFields", testUuiData.parsedFields);
            response.put("note", "現在您可以呼叫 /api/uui/data?extension=" + extension + " 來獲取資料");
            
            return response;
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "後端測試失敗: " + e.getMessage());
            return response;
        }
    }
}