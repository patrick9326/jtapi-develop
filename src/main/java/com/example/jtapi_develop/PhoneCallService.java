package com.example.jtapi_develop;

import org.springframework.stereotype.Service;
import javax.telephony.*;
import javax.telephony.callcontrol.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

@Service
public class PhoneCallService {
    
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
                    } catch (Exception e) {
                        System.out.println("[" + extension + "] 無法取得地址或終端: " + e.getMessage());
                        // 對於分機，這可能是問題，但我們仍然繼續
                    }
                }
                
                conn.isReady = true;
                extensions.put(extension, conn);
                
                String resultMessage;
                if ("cti".equals(conn.userType)) {
                    resultMessage = "CTI 用戶 " + extension + " 登入成功，具備分機控制權限";
                    System.out.println("[" + extension + "] CTI 用戶登入成功");
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
     * 撥打電話 (支援 CTI 控制)
     */
    public String makeCall(String callerExt, String calleeExt) {
        try {
            System.out.println("[CALL] 嘗試撥打: " + callerExt + " → " + calleeExt);
            
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
                return answerCallDirect(extension, directConn);
            }
            
            // 使用 CTI 控制模式
            ExtensionConnection ctiConn = findCTIConnection();
            if (ctiConn == null) {
                return "錯誤：沒有可用的 CTI 連線";
            }
            
            return answerCallByCTI(extension, ctiConn);
            
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
                return hangupCallDirect(extension, directConn);
            }
            
            // 使用 CTI 控制模式
            ExtensionConnection ctiConn = findCTIConnection();
            if (ctiConn == null) {
                return "錯誤：沒有可用的 CTI 連線";
            }
            
            return hangupCallByCTI(extension, ctiConn);
            
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
}