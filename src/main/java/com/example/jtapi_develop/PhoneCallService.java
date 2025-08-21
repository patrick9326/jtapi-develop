package com.example.jtapi_develop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import javax.telephony.*;
import javax.telephony.callcontrol.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import javax.telephony.events.TermConnRingingEv;
import javax.telephony.events.TermConnActiveEv;
import javax.telephony.events.TermConnDroppedEv;
import javax.telephony.events.TermConnEv;
import javax.telephony.events.CallEv;
import javax.telephony.callcontrol.events.CallCtlTermConnHeldEv;
import java.util.HashMap;

@Service
public class PhoneCallService {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    // *** UUI-MODIFIED ***: 注入新的 UuiService
    @Autowired
    private UuiService uuiService;
    
    @Autowired
    private SseService sseService; 
    // 存儲每個分機/用戶的連線信息
    private final ConcurrentHashMap<String, ExtensionConnection> extensions = new ConcurrentHashMap<>();
    
    /**
     * 分機連線信息類 (改為 public static 供其他服務使用)
     */
    public static class ExtensionConnection {
        public JtapiPeer peer;
        public Provider provider;
        public Address address;
        public Terminal terminal;
        public boolean isReady;
        public String serviceName;
        public String userType; // "extension", "cti", "cti_proxy"
        
        public ExtensionConnection() {
            this.isReady = false;
            this.userType = "extension"; // 預設為分機
        }
    }
    
    /**
     * 登入分機或 CTI 用戶 (統一的登入方法)
     */
    public CompletableFuture<String> loginExtension(String extension, String password) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("[" + extension + "] 開始登入...");
                
                ExtensionConnection conn = new ExtensionConnection();
                
                // 判斷是否為 CTI 用戶
                if ("ctiuser".equals(extension)) {
                    conn.userType = "cti";
                    System.out.println("[" + extension + "] 識別為 CTI 用戶");
                }
                
                // 建立 JtapiPeer
                try {
                    conn.peer = JtapiPeerFactory.getJtapiPeer(null);
                    System.out.println("[" + extension + "] JtapiPeer 創建成功");
                } catch (Exception e) {
                    try {
                        conn.peer = JtapiPeerFactory.getJtapiPeer("com.avaya.jtapi.tsapi.TsapiPeer");
                        System.out.println("[" + extension + "] JtapiPeer (Avaya) 創建成功");
                    } catch (Exception e2) {
                        throw new Exception("無法創建 JtapiPeer: " + e2.getMessage());
                    }
                }
                
                // 取得服務列表
                String[] services = conn.peer.getServices();
                if (services == null || services.length == 0) {
                    throw new Exception("找不到可用的服務");
                }
                
                conn.serviceName = services[0];
                System.out.println("[" + extension + "] 使用服務: " + conn.serviceName);
                
                // 建立連線字串
                String providerString = conn.serviceName + ";loginID=" + extension + ";passwd=" + password;
                System.out.println("[" + extension + "] 連線字串: " + providerString);
                
                // 建立 Provider
                conn.provider = conn.peer.getProvider(providerString);
                System.out.println("[" + extension + "] Provider 創建成功");
                
                // 等待 Provider 進入服務狀態
                final Object lock = new Object();
                final boolean[] isInService = {false};
                
                ProviderListener providerListener = new ProviderListener() {
                    @Override
                    public void providerEventTransmissionEnded(ProviderEvent event) {}
                    
                    @Override
                    public void providerInService(ProviderEvent event) {
                        synchronized (lock) {
                            isInService[0] = true;
                            lock.notify();
                        }
                    }
                    
                    @Override
                    public void providerOutOfService(ProviderEvent event) {}
                    
                    @Override
                    public void providerShutdown(ProviderEvent event) {}
                };
                
                conn.provider.addProviderListener(providerListener);
                
                // 等待最多 30 秒
                synchronized (lock) {
                    long startTime = System.currentTimeMillis();
                    while (!isInService[0] && (System.currentTimeMillis() - startTime) < 30000) {
                        lock.wait(1000);
                        System.out.println("[" + extension + "] 等待 Provider 進入服務狀態... " + 
                                         ((System.currentTimeMillis() - startTime) / 1000) + "秒");
                    }
                }
                
                if (!isInService[0]) {
                    throw new Exception("Provider 無法進入服務狀態，超時");
                }
                
                System.out.println("[" + extension + "] Provider 已進入服務狀態");
                // 在成功取得 Terminal 後，加入監聽器
                // 對於一般分機，嘗試取得地址和終端
                 if ("extension".equals(conn.userType)) {
                    try {
                        conn.address = conn.provider.getAddress(extension);
                        System.out.println("[" + extension + "] 地址創建成功");
                        
                        Terminal[] terminals = conn.address.getTerminals();
                        if (terminals != null && terminals.length > 0) {
                            conn.terminal = terminals[0];
                            System.out.println("[" + extension + "] 終端創建成功");
                        } else {
                            conn.terminal = conn.provider.getTerminal(extension);
                            System.out.println("[" + extension + "] 終端創建成功 (直接方式)");
                        }
                        
                        // ================== 新增監聽器 ==================
                        if (conn.terminal != null && conn.address != null) { // *** 已修改 ***
                            addTerminalListener(extension, conn.terminal, conn.address); // *** 已修改 ***
                        }
                        // ===============================================

                    } catch (Exception e) {
                        System.out.println("[" + extension + "] 無法取得地址或終端: " + e.getMessage());
                    }
                }
                
                conn.isReady = true;
                extensions.put(extension, conn);
                
                String resultMessage;
                if ("cti".equals(conn.userType)) {
                    resultMessage = "CTI 用戶 " + extension + " 登入成功，具備分機控制權限";
                    System.out.println("[" + extension + "] CTI 用戶登入成功");
                    
                    // ================== 重要：為主要分機建立JTAPI事件監聽器 ==================
                    // 只要是 CTI 用戶登入，就設置事件監聽器（不限定特定用戶名）
                    setupMainExtensionListeners(conn);
                    System.out.println("[" + extension + "] 已為 CTI 用戶設置事件監聽器");
                    // ================================================================
                } else {
                    resultMessage = "分機 " + extension + " 登入成功";
                    System.out.println("[" + extension + "] 分機登入成功");
                }
                
                return resultMessage;
                
            } catch (Exception e) {
                System.err.println("[" + extension + "] 登入失敗: " + e.getMessage());
                e.printStackTrace();
                return "用戶 " + extension + " 登入失敗: " + e.getMessage();
            }
        });
    }

    

    /**
     * 為主要分機(1420)建立JTAPI事件監聽器
     */
    private void setupMainExtensionListeners(ExtensionConnection ctiConn) {
        try {
            System.out.println("[SETUP_LISTENERS] 開始為主要分機建立監聽器...");
            
            // 為分機1420建立監聽器
            String[] mainExtensions = {"1420"};
            
            for (String ext : mainExtensions) {
                try {
                    Address address = ctiConn.provider.getAddress(ext);
                    Terminal[] terminals = address.getTerminals();
                    
                    if (terminals != null && terminals.length > 0) {
                        Terminal terminal = terminals[0];
                        addTerminalListener(ext, terminal, address); // *** 已修改 *** 傳入 address 物件
                        System.out.println("[SETUP_LISTENERS] ✅ 已為分機 " + ext + " 建立監聽器");
                    }else {
                        System.out.println("[SETUP_LISTENERS] ❌ 分機 " + ext + " 沒有可用的Terminal");
                    }
                } catch (Exception e) {
                    System.err.println("[SETUP_LISTENERS] ❌ 為分機 " + ext + " 建立監聽器失敗: " + e.getMessage());
                }
            }
            
            System.out.println("[SETUP_LISTENERS] 主要分機監聽器設置完成");
        } catch (Exception e) {
            System.err.println("[SETUP_LISTENERS] 設置主要分機監聽器時發生錯誤: " + e.getMessage());
        }
    }

    /**
     * 強制為指定分機設置監聽器
     */
    public String forceSetupListener(String extension) {
        try {
            ExtensionConnection ctiConn = getExtensionConnection("ctiuser");
            if (ctiConn == null || !ctiConn.isReady) {
                return "❌ CTI用戶未登入，請先登入CTI用戶";
            }
            
            System.out.println("[FORCE_SETUP] 開始為分機 " + extension + " 強制設置監聽器...");
            
            // 獲取分機地址和終端
            Address address = ctiConn.provider.getAddress(extension);
            Terminal[] terminals = address.getTerminals();
            
            if (terminals == null || terminals.length == 0) {
                return "❌ 分機 " + extension + " 沒有可用的Terminal";
            }
            
            Terminal terminal = terminals[0];
            
            // 設置監聽器
            addTerminalListener(extension, terminal, address);
            
            return "✅ 已為分機 " + extension + " 設置JTAPI事件監聽器\n現在來電/接聽/掛斷事件會自動推送到SSE";
            
        } catch (Exception e) {
            System.err.println("[FORCE_SETUP] 設置監聽器失敗: " + e.getMessage());
            return "❌ 設置監聽器失敗: " + e.getMessage();
        }
    }

    /**
     * 新增方法：發送更新事件到前端
     */
    private void sendUpdateEvent(String extension, String eventType, String message) {
        Map<String, String> eventData = new HashMap<>();
        eventData.put("extension", extension);
        eventData.put("eventType", eventType);
        eventData.put("message", message);
        eventData.put("timestamp", String.valueOf(System.currentTimeMillis()));

        // 使用 SseService 推送事件，事件名稱統一為 "phone_event"
        sseService.sendEvent(extension, "phone_event", eventData);
    }

    
    /**
     * 撥打電話 (支援 CTI 控制)
     */
    public String makeCall(String callerExt, String calleeExt) {
        try {
            System.out.println("[CALL] 嘗試撥打: " + callerExt + " → " + calleeExt);
            
            // 檢查被叫方Agent狀態
            if (!isAgentAvailable(calleeExt)) {
                return "撥打失敗: 分機 " + calleeExt + " 的Agent目前不接受來電";
            }
            
            // 方法1: 檢查分機是否直接登入
            ExtensionConnection directConn = extensions.get(callerExt);
            if (directConn != null && directConn.isReady && directConn.terminal != null) {
                System.out.println("[CALL] 使用直接登入模式");
                Call call = directConn.provider.createCall();
                call.connect(directConn.terminal, directConn.address, calleeExt);
                return "分機 " + callerExt + " 正在撥打給 " + calleeExt + " (直接模式)";
            }
            
            // 方法2: 使用 CTI 控制模式
            ExtensionConnection ctiConn = null;
            
            // 尋找可用的 CTI 連線
            for (Map.Entry<String, ExtensionConnection> entry : extensions.entrySet()) {
                ExtensionConnection conn = entry.getValue();
                if (conn.isReady && conn.provider != null && "cti".equals(conn.userType)) {
                    ctiConn = conn;
                    System.out.println("[CALL] 找到 CTI 連線: " + entry.getKey());
                    break;
                }
            }
            
            // 如果沒有 CTI 連線，尋找任何可用的連線
            if (ctiConn == null) {
                for (Map.Entry<String, ExtensionConnection> entry : extensions.entrySet()) {
                    ExtensionConnection conn = entry.getValue();
                    if (conn.isReady && conn.provider != null) {
                        ctiConn = conn;
                        System.out.println("[CALL] 找到可用連線: " + entry.getKey());
                        break;
                    }
                }
            }
            
            if (ctiConn == null) {
                return "錯誤：沒有可用的連線，請先登入用戶";
            }
            
            System.out.println("[CALL] 使用 CTI 控制模式控制分機 " + callerExt);
            
            // 使用 CTI 連線取得目標分機的地址和終端
            Address callerAddress = null;
            Terminal callerTerminal = null;
            
            try {
                callerAddress = ctiConn.provider.getAddress(callerExt);
                System.out.println("[CALL] 成功取得分機 " + callerExt + " 的地址");
                
                // 嘗試取得終端
                Terminal[] terminals = callerAddress.getTerminals();
                if (terminals != null && terminals.length > 0) {
                    callerTerminal = terminals[0];
                    System.out.println("[CALL] 成功取得分機 " + callerExt + " 的終端");
                } else {
                    // 如果沒有終端陣列，嘗試直接取得
                    try {
                        callerTerminal = ctiConn.provider.getTerminal(callerExt);
                        System.out.println("[CALL] 直接取得分機 " + callerExt + " 的終端");
                    } catch (Exception e) {
                        System.out.println("[CALL] 無法取得分機 " + callerExt + " 的終端: " + e.getMessage());
                        return "錯誤：無法控制分機 " + callerExt + "，可能沒有權限或分機不存在";
                    }
                }
            } catch (Exception e) {
                System.err.println("[CALL] 取得分機地址失敗: " + e.getMessage());
                return "錯誤：無法找到分機 " + callerExt + "，請檢查分機號碼是否正確";
            }
            
            if (callerTerminal == null) {
                return "錯誤：分機 " + callerExt + " 沒有可用的終端設備";
            }
            
            // ================== 確保監聽器已設置 ==================
            try {
                // *** 已修正 *** 把 callerAddress 作為第3個參數傳入
                addTerminalListener(callerExt, callerTerminal, callerAddress); 
                System.out.println("[CALL] 已確保分機 " + callerExt + " 監聽器設置");
            } catch (Exception e) {
                System.out.println("[CALL] 設置監聽器失敗: " + e.getMessage());
            }
            // ===================================================
            
            // 建立通話
            System.out.println("[CALL] 開始建立通話連線...");
            Call call = ctiConn.provider.createCall();
            call.connect(callerTerminal, callerAddress, calleeExt);
            
            System.out.println("[CALL] CTI 控制成功：" + callerExt + " → " + calleeExt);
            return "CTI 控制：分機 " + callerExt + " 正在撥打給 " + calleeExt;
            
        } catch (Exception e) {
            System.err.println("[CALL] 撥打失敗: " + e.getMessage());
            e.printStackTrace();
            return "撥打失敗: " + e.getMessage();
        }
    }
    
    /**
     * 接聽電話 (支援 CTI 控制)
     */
    public String answerCall(String extension) {
        try {
            System.out.println("[ANSWER] 嘗試接聽分機 " + extension + " 的電話");
            
            // 檢查分機是否直接登入
            ExtensionConnection directConn = extensions.get(extension);
            if (directConn != null && directConn.isReady && directConn.terminal != null) {
                String result = answerCallDirect(extension, directConn);
                if (result.contains("已接聽")) {
                } else {
                }
                return result;
            }
            
            // 使用 CTI 控制模式
            ExtensionConnection ctiConn = findCTIConnection();
            if (ctiConn == null) {
                return "錯誤：沒有可用的 CTI 連線";
            }
            
            String result = answerCallByCTI(extension, ctiConn);
            if (result.contains("已接聽")) {
            } else {
            }
            return result;
            
        } catch (Exception e) {
            System.err.println("[ANSWER] 接聽失敗: " + e.getMessage());
            e.printStackTrace();
            return "接聽失敗: " + e.getMessage();
        }
    }
    
    /**
     * 掛斷電話 (支援 CTI 控制)
     */
    public String hangupCall(String extension) {
        try {
            System.out.println("[HANGUP] 嘗試掛斷分機 " + extension + " 的電話");
            
            // 檢查分機是否直接登入
            ExtensionConnection directConn = extensions.get(extension);
            if (directConn != null && directConn.isReady && directConn.terminal != null) {
                String result = hangupCallDirect(extension, directConn);
                if (result.contains("已掛斷")) {
                } else {
                }
                return result;
            }
            
            // 使用 CTI 控制模式
            ExtensionConnection ctiConn = findCTIConnection();
            if (ctiConn == null) {
                return "錯誤：沒有可用的 CTI 連線";
            }
            
            String result = hangupCallByCTI(extension, ctiConn);
            if (result.contains("已掛斷")) {
            } else {
            }
            return result;
            
        } catch (Exception e) {
            System.err.println("[HANGUP] 掛斷失敗: " + e.getMessage());
            e.printStackTrace();
            return "掛斷失敗: " + e.getMessage();
        }
    }
    
    /**
     * 登出分機
     */
    public String logoutExtension(String extension) {
        try {
            ExtensionConnection conn = extensions.remove(extension);
            if (conn != null) {
                // 關閉 Provider
                if (conn.provider != null) {
                    conn.provider.shutdown();
                }
                
                System.out.println("[" + extension + "] 已登出");
                return "用戶 " + extension + " 已登出";
            } else {
                return "用戶 " + extension + " 未登入";
            }
        } catch (Exception e) {
            System.err.println("[" + extension + "] 登出失敗: " + e.getMessage());
            e.printStackTrace();
            return "登出失敗: " + e.getMessage();
        }
    }
    
    /**
     * 取得所有用戶狀態
     */
    public String getExtensionStatus() {
        if (extensions.isEmpty()) {
            return "目前沒有已登入的用戶";
        }
        
        StringBuilder status = new StringBuilder("已登入的用戶:\n");
        for (String ext : extensions.keySet()) {
            ExtensionConnection conn = extensions.get(ext);
            String state = conn.isReady ? "就緒" : "未就緒";
            String terminalStatus = conn.terminal != null ? "有終端" : "無終端";
            String userType = "cti".equals(conn.userType) ? "(CTI控制用戶)" : "(分機)";
            status.append("用戶 ").append(ext).append(" - ").append(state)
                  .append(" (").append(terminalStatus).append(") ")
                  .append(userType).append("\n");
        }
        return status.toString();
    }
    
    // ========================================
    // 以下是新增的轉接支援方法
    // ========================================
    
    /**
     * 提供給其他服務存取連線信息的方法
     * 讓 TransferService 可以取得分機連線
     */
    /**
     * 取得所有分機連線 (供 AgentService 查詢所有 Agent 使用)
     */
    public ConcurrentHashMap<String, ExtensionConnection> getAllExtensionConnections() {
        return extensions;
    }
    
    /**
     * 取得 Provider (供 AgentService 使用)
     */
    public Provider getProvider() {
        // 嘗試找到任何可用的 Provider
        for (ExtensionConnection conn : extensions.values()) {
            if (conn.isReady && conn.provider != null) {
                return conn.provider;
            }
        }
        return null;
    }
    
    public ExtensionConnection getExtensionConnection(String extension) {
        // 先檢查直接登入的分機
        ExtensionConnection directConn = extensions.get(extension);
        if (directConn != null && directConn.isReady) {
            return directConn;
        }
        
        // 如果沒有直接登入，找 CTI 連線
        for (Map.Entry<String, ExtensionConnection> entry : extensions.entrySet()) {
            ExtensionConnection conn = entry.getValue();
            if (conn.isReady && conn.provider != null && "cti".equals(conn.userType)) {
                // 回傳 CTI 連線，但需要設定目標分機地址
                try {
                    ExtensionConnection ctiForExtension = new ExtensionConnection();
                    ctiForExtension.peer = conn.peer;
                    ctiForExtension.provider = conn.provider;
                    ctiForExtension.serviceName = conn.serviceName;
                    ctiForExtension.userType = "cti_proxy";
                    ctiForExtension.isReady = true;
                    
                    // 取得目標分機的地址和終端
                    ctiForExtension.address = conn.provider.getAddress(extension);
                    Terminal[] terminals = ctiForExtension.address.getTerminals();
                    if (terminals != null && terminals.length > 0) {
                        ctiForExtension.terminal = terminals[0];
                    } else {
                        ctiForExtension.terminal = conn.provider.getTerminal(extension);
                    }
                    
                    return ctiForExtension;
                } catch (Exception e) {
                    System.err.println("[CTI_PROXY] 建立 CTI 代理連線失敗: " + e.getMessage());
                }
            }
        }
        
        return null;
    }
    
    /**
     * 清理指定分機的連線（用於 Agent 硬登出）
     */
    public void clearExtensionConnection(String extension) {
        ExtensionConnection conn = extensions.get(extension);
        if (conn != null) {
            try {
                // 關閉 Provider 連線
                if (conn.provider != null) {
                    conn.provider.shutdown();
                }
                
                // 從連線池中移除
                extensions.remove(extension);
                System.out.println("[" + extension + "] 連線已從池中清理");
                
            } catch (Exception e) {
                System.err.println("[" + extension + "] 清理連線時發生錯誤: " + e.getMessage());
            }
        }
    }
    
    /**
     * 檢查分機是否有活躍通話
     */
    public boolean hasActiveCall(String extension) {
        try {
            ExtensionConnection conn = getExtensionConnection(extension);
            if (conn != null && conn.terminal != null) {
                TerminalConnection[] termConnections = conn.terminal.getTerminalConnections();
                if (termConnections != null) {
                    for (TerminalConnection termConn : termConnections) {
                        if (termConn.getState() == TerminalConnection.ACTIVE) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[CHECK_CALL] 檢查通話狀態失敗: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * 取得分機的活躍通話
     */
    public Call getActiveCall(String extension) {
        try {
            ExtensionConnection conn = getExtensionConnection(extension);
            if (conn != null && conn.terminal != null) {
                TerminalConnection[] termConnections = conn.terminal.getTerminalConnections();
                if (termConnections != null) {
                    for (TerminalConnection termConn : termConnections) {
                        if (termConn.getState() == TerminalConnection.ACTIVE) {
                            return termConn.getConnection().getCall();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GET_CALL] 取得活躍通話失敗: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 取得分機的所有通話 (包括保持中的通話)
     */
    public Call[] getAllCalls(String extension) {
        try {
            ExtensionConnection conn = getExtensionConnection(extension);
            if (conn != null && conn.terminal != null) {
                TerminalConnection[] termConnections = conn.terminal.getTerminalConnections();
                if (termConnections != null) {
                    Call[] calls = new Call[termConnections.length];
                    for (int i = 0; i < termConnections.length; i++) {
                        calls[i] = termConnections[i].getConnection().getCall();
                    }
                    return calls;
                }
            }
        } catch (Exception e) {
            System.err.println("[GET_ALL_CALLS] 取得所有通話失敗: " + e.getMessage());
        }
        return new Call[0];
    }
    
    /**
     * 檢查分機是否有保持中的通話
     */
    public boolean hasHeldCall(String extension) {
        try {
            ExtensionConnection conn = getExtensionConnection(extension);
            if (conn != null && conn.terminal != null) {
                TerminalConnection[] termConnections = conn.terminal.getTerminalConnections();
                if (termConnections != null) {
                    for (TerminalConnection termConn : termConnections) {
                        if (termConn instanceof CallControlTerminalConnection) {
                            CallControlTerminalConnection controlTermConn = (CallControlTerminalConnection) termConn;
                            if (controlTermConn.getCallControlState() == CallControlTerminalConnection.HELD) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[CHECK_HELD_CALL] 檢查保持通話狀態失敗: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * 新增方法：為 Terminal 和 Address 新增監聽器 (*** 已修正版本 ***)
     * 這是最關鍵的修正：我們現在傳入 Address 物件，並將監聽器(Observer)加到它上面。
     */
    /**
     * 新增方法：為 Terminal 和 Address 新增監聽器 (*** 已修正版本 ***)
     * 這是最關鍵的修正：我們現在傳入 Address 物件，並將監聽器(Observer)加到它上面。
     */
    private void addTerminalListener(String extension, Terminal terminal, Address address) {
        try {
            // 檢查此地址是否已有監聽器，避免重複添加
            CallObserver[] existingObservers = address.getCallObservers();
            if (existingObservers != null && existingObservers.length > 0) {
                // 如果已經有監聽器，我們可以假設它就是我們需要的類型
                // 為了保險起見，可以檢查一下監聽器類型，但通常跳過即可
                System.out.println("[" + extension + "] ✅ CallObserver 已經存在於此地址，跳過重複設定。");
                return;
            }

            CallObserver callObserver = new CallObserver() {
                /**
                 * 這是 JTAPI 處理所有事件的核心方法。
                 * Avaya Server 的所有事件都會被送到這裡。
                 */
                @Override
                public void callChangedEvent(CallEv[] eventList) {
                    // 遍歷收到的事件陣列
                    for (CallEv event : eventList) {
                        // 為了方便除錯，先用 sout 輸出，正式環境建議用 Logger
                        System.out.println("[JTAPI_EVENT] " + extension + " 收到事件: " + event.getClass().getSimpleName());

                        // *** 非常重要：確保事件是來自正確的 Terminal ***
                        TerminalConnection tc = null;
                        if (event instanceof TermConnEv) {
                            tc = ((TermConnEv) event).getTerminalConnection();
                            
                            // 調試：顯示 Terminal 資訊
                            if (tc != null) {
                                String eventTerminalName = tc.getTerminal().getName();
                                String targetTerminalName = terminal.getName();
                                System.out.println("[TERMINAL_DEBUG] 事件來源Terminal: " + eventTerminalName + ", 目標Terminal: " + targetTerminalName);
                                
                                // 只處理與我們目標 Terminal 相關的事件
                                if (!eventTerminalName.equals(targetTerminalName)) {
                                    System.out.println("[TERMINAL_DEBUG] Terminal不匹配，跳過此事件");
                                    continue;
                                } else {
                                    System.out.println("[TERMINAL_DEBUG] Terminal匹配，處理此事件");
                                }
                            } else {
                                System.out.println("[TERMINAL_DEBUG] 事件沒有TerminalConnection，直接處理");
                            }
                        } else {
                            System.out.println("[TERMINAL_DEBUG] 不是TermConnEv事件，直接處理");
                        }
                        
                        // 現在可以處理事件了 - 支援標準和 Avaya 特定事件
                        String eventClassName = event.getClass().getSimpleName();
                        System.out.println("[EVENT_DEBUG] 檢查事件: " + eventClassName);
                        
                        // 響鈴事件 - 支援多種事件類型
                        if (event instanceof TermConnRingingEv || eventClassName.contains("TermConnCreated") || eventClassName.contains("Ringing")) {
                            System.out.println(">>> [SSE_PUSH] " + extension + ": 電話響鈴 (RINGING). 事件: " + eventClassName);
                            sendUpdateEvent(extension, "RINGING", "電話響鈴");
                            
                            // *** UUI-MODIFIED ***: 
                            // 將事件物件 event 本身傳遞給 UuiService，在響鈴時嘗試提取 UUI
                            System.out.println("[UUI_TRIGGER] 響鈴事件觸發 UUI 解析...");
                            uuiService.extractAndStoreUuiData(event, extension);
                            
                        } else if (event instanceof TermConnActiveEv || eventClassName.contains("TermConnActive") || eventClassName.contains("Active")) {
                            System.out.println(">>> [SSE_PUSH] " + extension + ": 電話變為活躍 (ACTIVE). 事件: " + eventClassName);
                            sendUpdateEvent(extension, "ACTIVE", "電話變為活躍");
                            
                            // *** UUI-MODIFIED ***: 
                            // 在接聽時也嘗試提取 UUI（以防響鈴時沒有成功）
                            System.out.println("[UUI_TRIGGER] 通話活躍事件也嘗試 UUI 解析...");
                            uuiService.extractAndStoreUuiData(event, extension);
                            
                        } else if (event instanceof TermConnDroppedEv || eventClassName.contains("Dropped") || eventClassName.contains("Disconnected")) {
                            System.out.println(">>> [SSE_PUSH] " + extension + ": 電話被掛斷 (DROPPED). 事件: " + eventClassName);
                            sendUpdateEvent(extension, "DROPPED", "電話被掛斷");
                            
                            // *** UUI-MODIFIED ***: 當電話掛斷時，通知 UuiService 清除資料
                            uuiService.clearUuiData(extension);
                            
                        } else if (event instanceof CallCtlTermConnHeldEv || eventClassName.contains("Held")) {
                            System.out.println(">>> [SSE_PUSH] " + extension + ": 電話被保持 (HELD). 事件: " + eventClassName);
                            sendUpdateEvent(extension, "HELD", "電話被保持");
                        } else {
                            // 記錄未處理的事件類型以便調試
                            System.out.println("[JTAPI_EVENT] " + extension + ": 未處理的事件類型: " + eventClassName);
                        }
                    }
                }
            };
            
            // *** 最關鍵的修正 ***
            // 將監聽器(Observer)添加到 ADDRESS 上，而不是單一的現有通話(Call)。
            // 這能確保此地址上所有未來的通話也都會被監聽到。
            address.addCallObserver(callObserver);

            System.out.println("[" + extension + "] ✅✅✅ 成功將 CallObserver 添加到 Address: " + address.getName());
            System.out.println("[" + extension + "] 🎯 現在已開始監聽此分機的所有未來通話事件！");

        } catch (Exception e) {
            System.err.println("[" + extension + "] ❌ 將監聽器添加到 Address 失敗: " + e.getMessage());
        }
    }



    // ========================================
    // 以下是原有的輔助方法
    // ========================================
    
    private ExtensionConnection findCTIConnection() {
        // 優先尋找 CTI 用戶
        for (Map.Entry<String, ExtensionConnection> entry : extensions.entrySet()) {
            ExtensionConnection conn = entry.getValue();
            if (conn.isReady && conn.provider != null && "cti".equals(conn.userType)) {
                System.out.println("[CTI] 找到 CTI 連線: " + entry.getKey());
                return conn;
            }
        }
        
        // 如果沒有，尋找任何可用的連線
        for (Map.Entry<String, ExtensionConnection> entry : extensions.entrySet()) {
            ExtensionConnection conn = entry.getValue();
            if (conn.isReady && conn.provider != null) {
                System.out.println("[CTI] 找到可用連線: " + entry.getKey());
                return conn;
            }
        }
        
        return null;
    }
    
    private String answerCallDirect(String extension, ExtensionConnection conn) {
        try {
            TerminalConnection[] termConnections = conn.terminal.getTerminalConnections();
            if (termConnections != null && termConnections.length > 0) {
                for (TerminalConnection termConn : termConnections) {
                    if (termConn.getState() == TerminalConnection.RINGING) {
                        System.out.println("[ANSWER] 分機 " + extension + " 接聽電話 (直接模式)");
                        
                        if (termConn instanceof CallControlTerminalConnection) {
                            ((CallControlTerminalConnection) termConn).answer();
                        }
                        
                        return "分機 " + extension + " 已接聽電話 (直接模式)";
                    }
                }
            }
            
            return "分機 " + extension + " 沒有響鈴的來電";
            
        } catch (Exception e) {
            return "直接接聽失敗: " + e.getMessage();
        }
    }
    
    private String answerCallByCTI(String extension, ExtensionConnection ctiConn) {
        try {
            Address address = ctiConn.provider.getAddress(extension);
            Terminal terminal = address.getTerminals()[0];
            
            TerminalConnection[] termConnections = terminal.getTerminalConnections();
            if (termConnections != null && termConnections.length > 0) {
                for (TerminalConnection termConn : termConnections) {
                    if (termConn.getState() == TerminalConnection.RINGING) {
                        System.out.println("[ANSWER] CTI 控制分機 " + extension + " 接聽電話");
                        
                        if (termConn instanceof CallControlTerminalConnection) {
                            ((CallControlTerminalConnection) termConn).answer();
                        }
                        
                        return "CTI 控制：分機 " + extension + " 已接聽電話";
                    }
                }
            }
            
            return "CTI 控制：分機 " + extension + " 沒有響鈴的來電";
            
        } catch (Exception e) {
            return "CTI 控制接聽失敗: " + e.getMessage();
        }
    }
    
    private String hangupCallDirect(String extension, ExtensionConnection conn) {
        try {
            boolean hasActiveCall = false;
            
            TerminalConnection[] termConnections = conn.terminal.getTerminalConnections();
            if (termConnections != null && termConnections.length > 0) {
                for (TerminalConnection termConn : termConnections) {
                    int state = termConn.getState();
                    if (state == TerminalConnection.ACTIVE || 
                        state == TerminalConnection.RINGING) {
                        System.out.println("[HANGUP] 分機 " + extension + " 掛斷電話 (直接模式)");
                        termConn.getConnection().disconnect();
                        hasActiveCall = true;
                    }
                }
            }
            
            if (hasActiveCall) {
                return "分機 " + extension + " 已掛斷電話 (直接模式)";
            } else {
                return "分機 " + extension + " 沒有活躍的通話";
            }
            
        } catch (Exception e) {
            return "直接掛斷失敗: " + e.getMessage();
        }
    }
    
    private String hangupCallByCTI(String extension, ExtensionConnection ctiConn) {
        try {
            Address address = ctiConn.provider.getAddress(extension);
            Terminal terminal = address.getTerminals()[0];
            
            boolean hasActiveCall = false;
            
            TerminalConnection[] termConnections = terminal.getTerminalConnections();
            if (termConnections != null && termConnections.length > 0) {
                for (TerminalConnection termConn : termConnections) {
                    int state = termConn.getState();
                    if (state == TerminalConnection.ACTIVE || 
                        state == TerminalConnection.RINGING) {
                        System.out.println("[HANGUP] CTI 控制分機 " + extension + " 掛斷電話");
                        termConn.getConnection().disconnect();
                        hasActiveCall = true;
                    }
                }
            }
            
            if (hasActiveCall) {
                return "CTI 控制：分機 " + extension + " 已掛斷電話";
            } else {
                return "CTI 控制：分機 " + extension + " 沒有活躍的通話";
            }
            
        } catch (Exception e) {
            return "CTI 控制掛斷失敗: " + e.getMessage();
        }
    }
    
    // ========================================
    // Agent狀態檢查功能
    // ========================================
    
    /**
     * 檢查Agent是否可接受來電
     */
    public boolean isAgentAvailable(String extension) {
        try {
            // 使用ApplicationContext來避免循環依賴
            AgentService agentService = applicationContext.getBean(AgentService.class);
            
            // 先檢查Agent是否登入
            String agentStatus = agentService.getAgentStatus(extension);
            
            // 如果沒有Agent登入，則允許通話（普通分機模式）
            if (agentStatus.contains("沒有 Agent 登入")) {
                System.out.println("[AGENT_CHECK] 分機 " + extension + " 沒有Agent登入，允許通話");
                return true;
            }
            
            // 有Agent登入，檢查狀態
            if (agentStatus.contains("待機中")) {
                System.out.println("[AGENT_CHECK] 分機 " + extension + " Agent處於待機狀態，允許通話");
                return true;
            } else if (agentStatus.contains("忙碌中") || agentStatus.contains("休息中")) {
                System.out.println("[AGENT_CHECK] 分機 " + extension + " Agent處於" + 
                                 (agentStatus.contains("忙碌中") ? "忙碌" : "休息") + "狀態，拒絕通話");
                return false;
            }
            
            // 其他狀態預設允許
            return true;
            
        } catch (Exception e) {
            System.err.println("[AGENT_CHECK] 檢查Agent狀態失敗: " + e.getMessage());
            // 發生錯誤時預設允許通話
            return true;
        }
    }
    
    /**
     * 檢查分機是否可接受來電（對外API）
     */
    public String checkExtensionAvailability(String extension) {
        if (isAgentAvailable(extension)) {
            return "分機 " + extension + " 可接受來電";
        } else {
            try {
                AgentService agentService = applicationContext.getBean(AgentService.class);
                String agentStatus = agentService.getAgentStatus(extension);
                if (agentStatus.contains("忙碌中")) {
                    return "分機 " + extension + " 的Agent處於忙碌狀態，暫時無法接受來電";
                } else if (agentStatus.contains("休息中")) {
                    return "分機 " + extension + " 的Agent處於休息狀態，暫時無法接受來電";
                } else {
                    return "分機 " + extension + " 暫時無法接受來電";
                }
            } catch (Exception e) {
                return "分機 " + extension + " 暫時無法接受來電";
            }
        }
    }
}