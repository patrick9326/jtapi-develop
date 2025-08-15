package com.example.jtapi_develop;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Service
public class MethodLogService {
    
    private final ConcurrentLinkedQueue<LogEntry> successLogs = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LogEntry> failureLogs = new ConcurrentLinkedQueue<>();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final String logFilePath = "method_logs.txt";
    
    public static class LogEntry {
        public String timestamp;
        public String function;
        public String method;
        public String details;
        public String extension;
        public String target;
        public boolean isSuccess;
        
        public LogEntry(String function, String method, String details, String extension, String target, boolean isSuccess) {
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            this.function = function;
            this.method = method;
            this.details = details;
            this.extension = extension;
            this.target = target;
            this.isSuccess = isSuccess;
        }
        
        @Override
        public String toString() {
            String status = isSuccess ? "✅ SUCCESS" : "❌ FAILURE";
            return String.format("[%s] %s - %s - %s: %s (分機:%s, 目標:%s)", 
                timestamp, status, function, method, details, extension, target != null ? target : "無");
        }
    }
    
    /**
     * 記錄成功的方法
     */
    public void logSuccess(String function, String method, String details, String extension, String target) {
        LogEntry entry = new LogEntry(function, method, details, extension, target, true);
        successLogs.offer(entry);
        
        // 限制記錄數量，保留最新的100筆
        if (successLogs.size() > 100) {
            successLogs.poll();
        }
        
        // 寫入檔案和控制台
        writeToFile(entry);
        System.out.println("✅ [SUCCESS_LOG] " + entry);
    }
    
    /**
     * 記錄失敗的方法
     */
    public void logFailure(String function, String method, String details, String extension, String target) {
        LogEntry entry = new LogEntry(function, method, details, extension, target, false);
        failureLogs.offer(entry);
        
        // 限制記錄數量，保留最新的100筆
        if (failureLogs.size() > 100) {
            failureLogs.poll();
        }
        
        // 寫入檔案和控制台
        writeToFile(entry);
        System.out.println("❌ [FAILURE_LOG] " + entry);
    }
    
    /**
     * 記錄成功的方法（簡化版）
     */
    public void logSuccess(String function, String method, String extension) {
        logSuccess(function, method, "操作成功", extension, null);
    }
    
    /**
     * 記錄成功的方法（帶目標）
     */
    public void logSuccess(String function, String method, String extension, String target) {
        logSuccess(function, method, "操作成功", extension, target);
    }
    
    /**
     * 記錄失敗的方法（簡化版）
     */
    public void logFailure(String function, String method, String extension) {
        logFailure(function, method, "操作失敗", extension, null);
    }
    
    /**
     * 記錄失敗的方法（帶目標）
     */
    public void logFailure(String function, String method, String extension, String target) {
        logFailure(function, method, "操作失敗", extension, target);
    }
    
    /**
     * 寫入檔案
     */
    private void writeToFile(LogEntry entry) {
        try {
            Files.write(Paths.get(logFilePath), 
                       (entry.toString() + "\n").getBytes(), 
                       StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("寫入日誌檔案失敗: " + e.getMessage());
        }
    }
    
    /**
     * 獲取所有記錄（成功+失敗）
     */
    public String getAllLogs() {
        StringBuilder result = new StringBuilder();
        result.append("=== 所有方法記錄 ===\n");
        result.append("成功記錄數：").append(successLogs.size()).append("\n");
        result.append("失敗記錄數：").append(failureLogs.size()).append("\n\n");
        
        // 合併並按時間排序顯示最近的記錄
        java.util.List<LogEntry> allEntries = new java.util.ArrayList<>();
        allEntries.addAll(successLogs);
        allEntries.addAll(failureLogs);
        
        allEntries.stream()
                .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
                .forEach(entry -> result.append(entry.toString()).append("\n"));
        
        return result.toString();
    }
    
    /**
     * 獲取所有成功記錄
     */
    public String getAllSuccessLogs() {
        if (successLogs.isEmpty()) {
            return "目前沒有成功記錄";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("=== 成功方法記錄 ===\n");
        result.append("總記錄數：").append(successLogs.size()).append("\n\n");
        
        successLogs.forEach(entry -> {
            result.append(entry.toString()).append("\n");
        });
        
        return result.toString();
    }
    
    /**
     * 獲取所有失敗記錄
     */
    public String getAllFailureLogs() {
        if (failureLogs.isEmpty()) {
            return "目前沒有失敗記錄";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("=== 失敗方法記錄 ===\n");
        result.append("總記錄數：").append(failureLogs.size()).append("\n\n");
        
        failureLogs.forEach(entry -> {
            result.append(entry.toString()).append("\n");
        });
        
        return result.toString();
    }
    
    /**
     * 獲取特定功能的成功記錄
     */
    public String getSuccessLogsByFunction(String function) {
        StringBuilder result = new StringBuilder();
        result.append("=== ").append(function).append(" 成功記錄 ===\n");
        
        long count = successLogs.stream()
                .filter(entry -> entry.function.equals(function))
                .count();
        
        if (count == 0) {
            result.append("此功能暫無成功記錄\n");
        } else {
            result.append("記錄數：").append(count).append("\n\n");
            successLogs.stream()
                    .filter(entry -> entry.function.equals(function))
                    .forEach(entry -> result.append(entry.toString()).append("\n"));
        }
        
        return result.toString();
    }
    
    /**
     * 獲取方法使用統計
     */
    public String getMethodStatistics() {
        StringBuilder result = new StringBuilder();
        result.append("=== 方法使用統計 ===\n\n");
        
        // 按功能分組統計
        successLogs.stream()
                .collect(java.util.stream.Collectors.groupingBy(entry -> entry.function))
                .forEach((function, entries) -> {
                    result.append("📋 ").append(function).append("：\n");
                    
                    entries.stream()
                            .collect(java.util.stream.Collectors.groupingBy(entry -> entry.method,
                                    java.util.stream.Collectors.counting()))
                            .forEach((method, count) -> {
                                result.append("   - ").append(method).append(": ").append(count).append("次\n");
                            });
                    result.append("\n");
                });
        
        return result.toString();
    }
    
    /**
     * 清除所有記錄
     */
    public String clearAllLogs() {
        int successCount = successLogs.size();
        int failureCount = failureLogs.size();
        successLogs.clear();
        failureLogs.clear();
        
        // 清除檔案內容
        try {
            Files.write(Paths.get(logFilePath), "".getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("清除日誌檔案失敗: " + e.getMessage());
        }
        
        return "已清除 " + successCount + " 筆成功記錄和 " + failureCount + " 筆失敗記錄";
    }
}