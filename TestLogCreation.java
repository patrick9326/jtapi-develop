// 測試檔案創建 - 可以放在任何 Controller 或 Service 中測試

@Autowired
private MethodLogService methodLogService;

// 測試方法 - 呼叫這個就會創建檔案
public void testLogFileCreation() {
    // 這行會自動創建 method_logs.txt 檔案
    methodLogService.logSuccess("測試", "檔案創建測試", "測試檔案是否自動創建", "1420", null);
    
    System.out.println("檔案已創建在: " + System.getProperty("user.dir") + "/method_logs.txt");
}