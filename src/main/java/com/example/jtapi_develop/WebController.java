package com.example.jtapi_develop;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class WebController {
    
    /**
     * 提供話機模擬器網頁
     * 訪問: http://192.168.90.105:8080/phone
     */
    @GetMapping("/phone")
    @ResponseBody
    public String phoneInterface() {
        return "<!DOCTYPE html>\n" +
               "<html lang=\"zh-TW\">\n" +
               "<head>\n" +
               "    <meta charset=\"UTF-8\">\n" +
               "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
               "    <title>Avaya 話機模擬器</title>\n" +
               "    <style>\n" +
               "        * {\n" +
               "            margin: 0;\n" +
               "            padding: 0;\n" +
               "            box-sizing: border-box;\n" +
               "        }\n" +
               "\n" +
               "        body {\n" +
               "            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;\n" +
               "            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
               "            min-height: 100vh;\n" +
               "            display: flex;\n" +
               "            justify-content: center;\n" +
               "            align-items: center;\n" +
               "            padding: 20px;\n" +
               "        }\n" +
               "\n" +
               "        .phone-container {\n" +
               "            background: #2c3e50;\n" +
               "            border-radius: 20px;\n" +
               "            padding: 30px;\n" +
               "            box-shadow: 0 20px 40px rgba(0,0,0,0.3);\n" +
               "            max-width: 500px;\n" +
               "            width: 100%;\n" +
               "        }\n" +
               "\n" +
               "        .phone-header {\n" +
               "            text-align: center;\n" +
               "            color: #ecf0f1;\n" +
               "            margin-bottom: 20px;\n" +
               "        }\n" +
               "\n" +
               "        .phone-header h1 {\n" +
               "            font-size: 24px;\n" +
               "            margin-bottom: 5px;\n" +
               "        }\n" +
               "\n" +
               "        .extension-display {\n" +
               "            background: #34495e;\n" +
               "            color: #1abc9c;\n" +
               "            padding: 5px 10px;\n" +
               "            border-radius: 5px;\n" +
               "            font-family: monospace;\n" +
               "            font-size: 14px;\n" +
               "        }\n" +
               "\n" +
               "        .login-section {\n" +
               "            background: #34495e;\n" +
               "            border-radius: 10px;\n" +
               "            padding: 20px;\n" +
               "            margin-bottom: 20px;\n" +
               "            color: #ecf0f1;\n" +
               "        }\n" +
               "\n" +
               "        .login-form {\n" +
               "            display: flex;\n" +
               "            flex-direction: column;\n" +
               "            gap: 10px;\n" +
               "        }\n" +
               "\n" +
               "        .login-form input {\n" +
               "            padding: 10px;\n" +
               "            border: none;\n" +
               "            border-radius: 5px;\n" +
               "            font-size: 14px;\n" +
               "        }\n" +
               "\n" +
               "        .login-form button {\n" +
               "            padding: 10px;\n" +
               "            background: #27ae60;\n" +
               "            color: white;\n" +
               "            border: none;\n" +
               "            border-radius: 5px;\n" +
               "            cursor: pointer;\n" +
               "            font-size: 14px;\n" +
               "            transition: background 0.3s;\n" +
               "        }\n" +
               "\n" +
               "        .login-form button:hover {\n" +
               "            background: #2ecc71;\n" +
               "        }\n" +
               "\n" +
               "        .lcd-display {\n" +
               "            background: #000;\n" +
               "            color: #00ff00;\n" +
               "            font-family: 'Courier New', monospace;\n" +
               "            font-size: 14px;\n" +
               "            padding: 15px;\n" +
               "            border-radius: 8px;\n" +
               "            margin-bottom: 20px;\n" +
               "            min-height: 120px;\n" +
               "            border: 3px solid #555;\n" +
               "            white-space: pre-line;\n" +
               "            overflow-y: auto;\n" +
               "            line-height: 1.4;\n" +
               "        }\n" +
               "\n" +
               "        .line-buttons {\n" +
               "            display: grid;\n" +
               "            grid-template-columns: repeat(3, 1fr);\n" +
               "            gap: 10px;\n" +
               "            margin-bottom: 20px;\n" +
               "        }\n" +
               "\n" +
               "        .line-btn {\n" +
               "            background: #e74c3c;\n" +
               "            color: white;\n" +
               "            border: none;\n" +
               "            border-radius: 8px;\n" +
               "            padding: 12px 8px;\n" +
               "            cursor: pointer;\n" +
               "            font-size: 12px;\n" +
               "            font-weight: bold;\n" +
               "            transition: all 0.2s;\n" +
               "            position: relative;\n" +
               "        }\n" +
               "\n" +
               "        .line-btn:hover {\n" +
               "            background: #c0392b;\n" +
               "            transform: translateY(-2px);\n" +
               "        }\n" +
               "\n" +
               "        .line-btn.active {\n" +
               "            background: #27ae60;\n" +
               "            box-shadow: 0 0 15px rgba(39, 174, 96, 0.5);\n" +
               "        }\n" +
               "\n" +
               "        .line-btn.held {\n" +
               "            background: #f39c12;\n" +
               "        }\n" +
               "\n" +
               "        .function-buttons {\n" +
               "            display: grid;\n" +
               "            grid-template-columns: repeat(2, 1fr);\n" +
               "            gap: 10px;\n" +
               "            margin-bottom: 20px;\n" +
               "        }\n" +
               "\n" +
               "        .function-btn {\n" +
               "            background: #3498db;\n" +
               "            color: white;\n" +
               "            border: none;\n" +
               "            border-radius: 8px;\n" +
               "            padding: 15px 10px;\n" +
               "            cursor: pointer;\n" +
               "            font-size: 13px;\n" +
               "            font-weight: bold;\n" +
               "            transition: all 0.2s;\n" +
               "        }\n" +
               "\n" +
               "        .function-btn:hover {\n" +
               "            background: #2980b9;\n" +
               "            transform: translateY(-2px);\n" +
               "        }\n" +
               "\n" +
               "        .keypad {\n" +
               "            display: grid;\n" +
               "            grid-template-columns: repeat(3, 1fr);\n" +
               "            gap: 8px;\n" +
               "            margin-bottom: 20px;\n" +
               "        }\n" +
               "\n" +
               "        .key-btn {\n" +
               "            background: #95a5a6;\n" +
               "            color: #2c3e50;\n" +
               "            border: none;\n" +
               "            border-radius: 50%;\n" +
               "            width: 50px;\n" +
               "            height: 50px;\n" +
               "            cursor: pointer;\n" +
               "            font-size: 18px;\n" +
               "            font-weight: bold;\n" +
               "            transition: all 0.2s;\n" +
               "            margin: 0 auto;\n" +
               "        }\n" +
               "\n" +
               "        .key-btn:hover {\n" +
               "            background: #7f8c8d;\n" +
               "            transform: scale(1.1);\n" +
               "        }\n" +
               "\n" +
               "        .main-controls {\n" +
               "            display: grid;\n" +
               "            grid-template-columns: 1fr 1fr 1fr;\n" +
               "            gap: 15px;\n" +
               "        }\n" +
               "\n" +
               "        .control-btn {\n" +
               "            padding: 15px;\n" +
               "            border: none;\n" +
               "            border-radius: 10px;\n" +
               "            cursor: pointer;\n" +
               "            font-size: 14px;\n" +
               "            font-weight: bold;\n" +
               "            transition: all 0.2s;\n" +
               "        }\n" +
               "\n" +
               "        .answer-btn {\n" +
               "            background: #27ae60;\n" +
               "            color: white;\n" +
               "        }\n" +
               "\n" +
               "        .hangup-btn {\n" +
               "            background: #e74c3c;\n" +
               "            color: white;\n" +
               "        }\n" +
               "\n" +
               "        .dial-btn {\n" +
               "            background: #f39c12;\n" +
               "            color: white;\n" +
               "        }\n" +
               "\n" +
               "        .number-input {\n" +
               "            margin-bottom: 15px;\n" +
               "        }\n" +
               "\n" +
               "        .number-input input {\n" +
               "            width: 100%;\n" +
               "            padding: 12px;\n" +
               "            border: 2px solid #555;\n" +
               "            border-radius: 8px;\n" +
               "            background: #34495e;\n" +
               "            color: #ecf0f1;\n" +
               "            font-size: 16px;\n" +
               "            text-align: center;\n" +
               "            font-family: monospace;\n" +
               "        }\n" +
               "\n" +
               "        .status-message {\n" +
               "            margin-top: 15px;\n" +
               "            padding: 10px;\n" +
               "            border-radius: 5px;\n" +
               "            font-size: 12px;\n" +
               "            text-align: center;\n" +
               "        }\n" +
               "\n" +
               "        .phone-interface {\n" +
               "            display: none;\n" +
               "        }\n" +
               "\n" +
               "        .phone-interface.active {\n" +
               "            display: block;\n" +
               "        }\n" +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "    <div class=\"phone-container\">\n" +
               "        <div class=\"login-section\" id=\"loginSection\">\n" +
               "            <h2 style=\"text-align: center; margin-bottom: 15px;\">Avaya 話機登入</h2>\n" +
               "            <div class=\"login-form\">\n" +
               "                <input type=\"text\" id=\"extensionInput\" placeholder=\"分機號 (例如: ctiuser)\" value=\"ctiuser\">\n" +
               "                <input type=\"password\" id=\"passwordInput\" placeholder=\"密碼\" value=\"Avaya123!\">\n" +
               "                <button onclick=\"login()\">登入</button>\n" +
               "            </div>\n" +
               "            <div id=\"loginStatus\" class=\"status-message\" style=\"display: none;\"></div>\n" +
               "        </div>\n" +
               "\n" +
               "        <div class=\"phone-interface\" id=\"phoneInterface\">\n" +
               "            <div class=\"phone-header\">\n" +
               "                <h1>Avaya IP Phone</h1>\n" +
               "                <div class=\"extension-display\" id=\"extensionDisplay\">Extension: 1420</div>\n" +
               "            </div>\n" +
               "\n" +
               "            <div class=\"lcd-display\" id=\"lcdDisplay\">\n" +
               "=== 話機 1420 ===\n" +
               "所有線路空閒\n" +
               "\n" +
               "準備就緒...</div>\n" +
               "\n" +
               "            <div class=\"line-buttons\">\n" +
               "                <button class=\"line-btn\" onclick=\"selectLine('L1')\" id=\"lineBtn1\">\n" +
               "                    L1<br><small>空閒</small>\n" +
               "                </button>\n" +
               "                <button class=\"line-btn\" onclick=\"selectLine('L2')\" id=\"lineBtn2\">\n" +
               "                    L2<br><small>空閒</small>\n" +
               "                </button>\n" +
               "                <button class=\"line-btn\" onclick=\"selectLine('L3')\" id=\"lineBtn3\">\n" +
               "                    L3<br><small>空閒</small>\n" +
               "                </button>\n" +
               "                <button class=\"line-btn\" onclick=\"selectLine('L4')\" id=\"lineBtn4\">\n" +
               "                    L4<br><small>空閒</small>\n" +
               "                </button>\n" +
               "                <button class=\"line-btn\" onclick=\"selectLine('L5')\" id=\"lineBtn5\">\n" +
               "                    L5<br><small>空閒</small>\n" +
               "                </button>\n" +
               "                <button class=\"line-btn\" onclick=\"selectLine('L6')\" id=\"lineBtn6\">\n" +
               "                    L6<br><small>空閒</small>\n" +
               "                </button>\n" +
               "            </div>\n" +
               "\n" +
               "            <div class=\"function-buttons\">\n" +
               "                <button class=\"function-btn\" onclick=\"holdCall()\">Hold</button>\n" +
               "                <button class=\"function-btn\" onclick=\"unholdCall()\">Unhold</button>\n" +
               "                <button class=\"function-btn\" onclick=\"transferCall()\">Transfer</button>\n" +
               "                <button class=\"function-btn\" onclick=\"conference()\">Conference</button>\n" +
               "                <button class=\"function-btn\" onclick=\"toggleHold()\">Hold切換</button>\n" +
               "                <button class=\"function-btn\" onclick=\"showAgentPanel()\">Agent面板</button>\n" +
               "            </div>\n" +
               "\n" +
               "            <div class=\"agent-section\" id=\"agentSection\" style=\"display: none;\">\n" +
               "                <h3 style=\"color: #ecf0f1; margin-bottom: 10px;\">Agent 功能</h3>\n" +
               "                <div class=\"agent-controls\">\n" +
               "                    <input type=\"text\" id=\"agentIdInput\" placeholder=\"Agent ID\" style=\"margin-bottom: 10px; width: 100%; padding: 8px;\">\n" +
               "                    <div style=\"display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 10px;\">\n" +
               "                        <button class=\"function-btn\" onclick=\"agentLogin()\">Agent 登入</button>\n" +
               "                        <button class=\"function-btn\" onclick=\"agentLogout()\">Agent 登出</button>\n" +
               "                    </div>\n" +
               "                    <div style=\"display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 5px;\">\n" +
               "                        <button class=\"function-btn\" onclick=\"setAgentStatus('AVAILABLE')\">待機</button>\n" +
               "                        <button class=\"function-btn\" onclick=\"setAgentStatus('BUSY')\">忙碌</button>\n" +
               "                        <button class=\"function-btn\" onclick=\"setAgentStatus('BREAK')\">休息</button>\n" +
               "                    </div>\n" +
               "                    <div id=\"agentStatus\" style=\"margin-top: 10px; color: #1abc9c; font-size: 12px;\">未登入</div>\n" +
               "                </div>\n" +
               "            </div>\n" +
               "\n" +
               "            <div class=\"dial-section\">\n" +
               "                <div class=\"number-input\">\n" +
               "                    <input type=\"text\" id=\"numberInput\" placeholder=\"輸入號碼...\" maxlength=\"10\">\n" +
               "                </div>\n" +
               "                \n" +
               "                <div class=\"line-selection\" style=\"margin-bottom: 15px;\">\n" +
               "                    <label style=\"color: #ecf0f1; margin-bottom: 5px; display: block;\">選擇線路:</label>\n" +
               "                    <select id=\"lineSelector\" style=\"width: 100%; padding: 8px; border-radius: 5px; border: none;\">\n" +
               "                        <option value=\"\">自動選擇</option>\n" +
               "                        <option value=\"L1\">線路 1</option>\n" +
               "                        <option value=\"L2\">線路 2</option>\n" +
               "                        <option value=\"L3\">線路 3</option>\n" +
               "                        <option value=\"L4\">線路 4</option>\n" +
               "                        <option value=\"L5\">線路 5</option>\n" +
               "                        <option value=\"L6\">線路 6</option>\n" +
               "                    </select>\n" +
               "                </div>\n" +
               "            </div>\n" +
               "\n" +
               "            <div class=\"keypad\">\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('1')\">1</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('2')\">2</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('3')\">3</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('4')\">4</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('5')\">5</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('6')\">6</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('7')\">7</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('8')\">8</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('9')\">9</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('*')\">*</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('0')\">0</button>\n" +
               "                <button class=\"key-btn\" onclick=\"addDigit('#')\">#</button>\n" +
               "            </div>\n" +
               "\n" +
               "            <div class=\"main-controls\">\n" +
               "                <button class=\"control-btn answer-btn\" onclick=\"answerCall()\">\n" +
               "                    📞 接聽\n" +
               "                </button>\n" +
               "                <button class=\"control-btn dial-btn\" onclick=\"makeCall()\">\n" +
               "                    📤 撥號\n" +
               "                </button>\n" +
               "                <button class=\"control-btn hangup-btn\" onclick=\"hangupCall()\">\n" +
               "                    📵 掛斷\n" +
               "                </button>\n" +
               "            </div>\n" +
               "\n" +
               "            <div id=\"statusMessage\" class=\"status-message\" style=\"display: none;\"></div>\n" +
               "        </div>\n" +
               "    </div>\n" +
               "\n" +
               "    <script>\n" +
               "        let currentExtension = '';\n" +
               "        let apiBase = window.location.origin;\n" +
               "        let refreshInterval;\n" +
               "        let transferMode = false;\n" +
               "\n" +
               "        async function login() {\n" +
               "            const extension = document.getElementById('extensionInput').value;\n" +
               "            const password = document.getElementById('passwordInput').value;\n" +
               "            \n" +
               "            if (!extension || !password) {\n" +
               "                showLoginStatus('請輸入分機號和密碼', 'error');\n" +
               "                return;\n" +
               "            }\n" +
               "\n" +
               "            showLoginStatus('登入中...', 'info');\n" +
               "            \n" +
               "            try {\n" +
               "                const response = await fetch(`${apiBase}/api/unified-phone/login?extension=${extension}&password=${password}`);\n" +
               "                const result = await response.text();\n" +
               "                \n" +
               "                if (result.includes('成功') || result.includes('登入成功')) {\n" +
               "                    currentExtension = '1420';\n" +
               "                    document.getElementById('extensionDisplay').textContent = `Extension: ${currentExtension}`;\n" +
               "                    \n" +
               "                    document.getElementById('loginSection').style.display = 'none';\n" +
               "                    document.getElementById('phoneInterface').classList.add('active');\n" +
               "                    \n" +
               "                    startAutoRefresh();\n" +
               "                    showStatus('登入成功！', 'success');\n" +
               "                } else {\n" +
               "                    showLoginStatus('登入失敗: ' + result, 'error');\n" +
               "                }\n" +
               "            } catch (error) {\n" +
               "                showLoginStatus('連線錯誤: ' + error.message, 'error');\n" +
               "            }\n" +
               "        }\n" +
               "\n" +
               "        function showLoginStatus(message, type) {\n" +
               "            const statusEl = document.getElementById('loginStatus');\n" +
               "            statusEl.textContent = message;\n" +
               "            statusEl.className = `status-message status-${type}`;\n" +
               "            statusEl.style.display = 'block';\n" +
               "        }\n" +
               "\n" +
               "        function showStatus(message, type = 'info') {\n" +
               "            const statusEl = document.getElementById('statusMessage');\n" +
               "            statusEl.textContent = message;\n" +
               "            statusEl.className = `status-message status-${type}`;\n" +
               "            statusEl.style.display = 'block';\n" +
               "            \n" +
               "            setTimeout(() => {\n" +
               "                statusEl.style.display = 'none';\n" +
               "            }, 3000);\n" +
               "        }\n" +
               "\n" +
               "        function addDigit(digit) {\n" +
               "            const input = document.getElementById('numberInput');\n" +
               "            input.value += digit;\n" +
               "        }\n" +
               "\n" +
               "        async function callAPI(endpoint, showResult = true) {\n" +
               "            try {\n" +
               "                const response = await fetch(`${apiBase}/api/unified-phone/${endpoint}?ext=${currentExtension}`);\n" +
               "                const result = await response.text();\n" +
               "                \n" +
               "                if (showResult) {\n" +
               "                    if (result.includes('失敗') || result.includes('錯誤')) {\n" +
               "                        showStatus(result, 'error');\n" +
               "                    } else {\n" +
               "                        showStatus(result, 'success');\n" +
               "                    }\n" +
               "                }\n" +
               "                \n" +
               "                setTimeout(updateDisplay, 500);\n" +
               "                return result;\n" +
               "            } catch (error) {\n" +
               "                if (showResult) {\n" +
               "                    showStatus('API 呼叫失敗: ' + error.message, 'error');\n" +
               "                }\n" +
               "                return null;\n" +
               "            }\n" +
               "        }\n" +
               "\n" +
               "        async function answerCall() {\n" +
               "            await callAPI('answer');\n" +
               "        }\n" +
               "\n" +
               "        // ========================================\n" +
               "        // 線路選擇撥號功能\n" +
               "        // ========================================\n" +
               "        \n" +
               "        async function makeCallWithLineSelection() {\n" +
               "            const number = document.getElementById('numberInput').value;\n" +
               "            const selectedLine = document.getElementById('lineSelector').value;\n" +
               "            \n" +
               "            if (!number) {\n" +
               "                showStatus('請輸入號碼', 'error');\n" +
               "                return;\n" +
               "            }\n" +
               "        \n" +
               "            if (transferMode) {\n" +
               "                const response = await fetch(`${apiBase}/api/unified-phone/transfer?ext=${currentExtension}&target=${number}`);\n" +
               "                const result = await response.text();\n" +
               "                if (result.includes('失敗') || result.includes('錯誤')) {\n" +
               "                    showStatus(result, 'error');\n" +
               "                } else {\n" +
               "                    showStatus(result, 'success');\n" +
               "                }\n" +
               "                setTimeout(updateDisplay, 500);\n" +
               "                document.getElementById('numberInput').value = '';\n" +
               "                transferMode = false;\n" +
               "            } else {\n" +
               "                let endpoint = 'dial';\n" +
               "                let params = `ext=${currentExtension}&number=${number}`;\n" +
               "                \n" +
               "                if (selectedLine) {\n" +
               "                    endpoint = 'select-line-and-dial';\n" +
               "                    params += `&preferredLine=${selectedLine}`;\n" +
               "                }\n" +
               "                \n" +
               "                const response = await fetch(`${apiBase}/api/unified-phone/${endpoint}?${params}`);\n" +
               "                const result = await response.text();\n" +
               "                \n" +
               "                if (result.includes('失敗') || result.includes('錯誤')) {\n" +
               "                    showStatus(result, 'error');\n" +
               "                } else {\n" +
               "                    showStatus(result, 'success');\n" +
               "                }\n" +
               "                \n" +
               "                setTimeout(updateDisplay, 500);\n" +
               "                document.getElementById('numberInput').value = '';\n" +
               "                document.getElementById('lineSelector').value = '';\n" +
               "            }\n" +
               "        }\n" +
               "        \n" +
               "        // 修改原有的 makeCall 函數\n" +
               "        async function makeCall() {\n" +
               "            await makeCallWithLineSelection();\n" +
               "        }\n" +
               "\n" +
               "        async function hangupCall() {\n" +
               "            await callAPI('hangup');\n" +
               "        }\n" +
               "\n" +
               "        // ========================================\n" +
               "        // Hold/Unhold 功能\n" +
               "        // ========================================\n" +
               "        \n" +
               "        async function holdCall() {\n" +
               "            await callAPI('hold-active');\n" +
               "        }\n" +
               "        \n" +
               "        async function unholdCall() {\n" +
               "            await callAPI('unhold');\n" +
               "        }\n" +
               "        \n" +
               "        async function toggleHold() {\n" +
               "            await callAPI('toggle-hold');\n" +
               "        }\n" +
               "        \n" +
               "        async function resumeCall() {\n" +
               "            await callAPI('flash');\n" +
               "        }\n" +
               "\n" +
               "        async function transferCall() {\n" +
               "            if (!transferMode) {\n" +
               "                transferMode = true;\n" +
               "                showStatus('請輸入轉接目標號碼然後按撥號', 'info');\n" +
               "                document.getElementById('numberInput').placeholder = '轉接目標...';\n" +
               "            } else {\n" +
               "                await callAPI('transfer-complete');\n" +
               "                transferMode = false;\n" +
               "                document.getElementById('numberInput').placeholder = '輸入號碼...';\n" +
               "            }\n" +
               "        }\n" +
               "\n" +
               "        async function conference() {\n" +
               "            await callAPI('conference');\n" +
               "        }\n" +
               "\n" +
               "        async function selectLine(lineNumber) {\n" +
               "            const display = await callAPI('display', false);\n" +
               "            if (display) {\n" +
               "                const lines = display.split('\\\\n');\n" +
               "                let actualLineId = null;\n" +
               "                \n" +
               "                for (let line of lines) {\n" +
               "                    const match = line.match(/(\\\\s*)(>>>?\\\\s*)?L(\\\\d+):/);\n" +
               "                    if (match && match[3] === lineNumber.replace('L', '')) {\n" +
               "                        actualLineId = `${currentExtension}_L${match[3]}`;\n" +
               "                        break;\n" +
               "                    }\n" +
               "                }\n" +
               "                \n" +
               "                if (actualLineId) {\n" +
               "                    try {\n" +
               "                        const response = await fetch(`${apiBase}/api/unified-phone/line?ext=${currentExtension}&line=${actualLineId}`);\n" +
               "                        const result = await response.text();\n" +
               "                        \n" +
               "                        if (result.includes('失敗') || result.includes('錯誤')) {\n" +
               "                            showStatus(result, 'error');\n" +
               "                        } else {\n" +
               "                            showStatus(result, 'success');\n" +
               "                        }\n" +
               "                        \n" +
               "                        setTimeout(updateDisplay, 500);\n" +
               "                    } catch (error) {\n" +
               "                        showStatus('API 呼叫失敗: ' + error.message, 'error');\n" +
               "                    }\n" +
               "                } else {\n" +
               "                    showStatus('該線路無通話', 'info');\n" +
               "                }\n" +
               "            }\n" +
               "        }\n" +
               "\n" +
               "        async function updateDisplay() {\n" +
               "            const display = await callAPI('display', false);\n" +
               "            if (display) {\n" +
               "                document.getElementById('lcdDisplay').textContent = display;\n" +
               "                updateLineButtons(display);\n" +
               "            }\n" +
               "        }\n" +
               "\n" +
               "        function updateLineButtons(display) {\n" +
               "            const lines = display.split('\\\\n');\n" +
               "            \n" +
               "            for (let i = 1; i <= 6; i++) {\n" +
               "                const btn = document.getElementById(`lineBtn${i}`);\n" +
               "                btn.className = 'line-btn';\n" +
               "                btn.innerHTML = `L${i}<br><small>空閒</small>`;\n" +
               "            }\n" +
               "            \n" +
               "            for (let line of lines) {\n" +
               "                const match = line.match(/(\\\\s*)(>>>?\\\\s*)?L(\\\\d+):\\\\s*(\\\\S+)\\\\s+(.+)/);\n" +
               "                if (match) {\n" +
               "                    const isActive = match[2] && match[2].includes('>>>');\n" +
               "                    const lineNum = parseInt(match[3]);\n" +
               "                    const state = match[4];\n" +
               "                    const info = match[5];\n" +
               "                    \n" +
               "                    if (lineNum >= 1 && lineNum <= 6) {\n" +
               "                        const btn = document.getElementById(`lineBtn${lineNum}`);\n" +
               "                        btn.innerHTML = `L${lineNum}<br><small>${info.substring(0, 15)}</small>`;\n" +
               "                        \n" +
               "                        if (isActive) {\n" +
               "                            btn.classList.add('active');\n" +
               "                        } else if (state.includes('保持') || state.includes('HELD') || info.includes('保持')) {\n" +
               "                            btn.classList.add('held');\n" +
               "                        }\n" +
               "                    }\n" +
               "                }\n" +
               "            }\n" +
               "        }\n" +
               "\n" +
               "        // ========================================\n" +
               "        // Agent 功能\n" +
               "        // ========================================\n" +
               "        \n" +
               "        function showAgentPanel() {\n" +
               "            const agentSection = document.getElementById('agentSection');\n" +
               "            agentSection.style.display = agentSection.style.display === 'none' ? 'block' : 'none';\n" +
               "        }\n" +
               "        \n" +
               "        async function agentLogin() {\n" +
               "            const agentId = document.getElementById('agentIdInput').value;\n" +
               "            if (!agentId) {\n" +
               "                showStatus('請輸入 Agent ID', 'error');\n" +
               "                return;\n" +
               "            }\n" +
               "        \n" +
               "            try {\n" +
               "                const response = await fetch(`${apiBase}/api/agent/login?extension=${currentExtension}&agentId=${agentId}`);\n" +
               "                const result = await response.text();\n" +
               "                \n" +
               "                if (result.includes('成功')) {\n" +
               "                    showStatus(result, 'success');\n" +
               "                    updateAgentStatus();\n" +
               "                } else {\n" +
               "                    showStatus(result, 'error');\n" +
               "                }\n" +
               "            } catch (error) {\n" +
               "                showStatus('Agent 登入失敗: ' + error.message, 'error');\n" +
               "            }\n" +
               "        }\n" +
               "        \n" +
               "        async function agentLogout() {\n" +
               "            try {\n" +
               "                const response = await fetch(`${apiBase}/api/agent/logout?extension=${currentExtension}`);\n" +
               "                const result = await response.text();\n" +
               "                \n" +
               "                if (result.includes('成功')) {\n" +
               "                    showStatus(result, 'success');\n" +
               "                    document.getElementById('agentIdInput').value = '';\n" +
               "                    updateAgentStatus();\n" +
               "                } else {\n" +
               "                    showStatus(result, 'error');\n" +
               "                }\n" +
               "            } catch (error) {\n" +
               "                showStatus('Agent 登出失敗: ' + error.message, 'error');\n" +
               "            }\n" +
               "        }\n" +
               "        \n" +
               "        async function setAgentStatus(status) {\n" +
               "            try {\n" +
               "                const response = await fetch(`${apiBase}/api/agent/set-status?extension=${currentExtension}&status=${status}`);\n" +
               "                const result = await response.text();\n" +
               "                \n" +
               "                if (result.includes('成功') || result.includes('已更新')) {\n" +
               "                    showStatus(result, 'success');\n" +
               "                    updateAgentStatus();\n" +
               "                } else {\n" +
               "                    showStatus(result, 'error');\n" +
               "                }\n" +
               "            } catch (error) {\n" +
               "                showStatus('設定狀態失敗: ' + error.message, 'error');\n" +
               "            }\n" +
               "        }\n" +
               "        \n" +
               "        async function updateAgentStatus() {\n" +
               "            try {\n" +
               "                const response = await fetch(`${apiBase}/api/agent/status?extension=${currentExtension}`);\n" +
               "                const result = await response.text();\n" +
               "                \n" +
               "                const statusElement = document.getElementById('agentStatus');\n" +
               "                if (result.includes('沒有 Agent 登入')) {\n" +
               "                    statusElement.textContent = '未登入';\n" +
               "                    statusElement.style.color = '#e74c3c';\n" +
               "                } else {\n" +
               "                    // 簡化顯示\n" +
               "                    const lines = result.split('\\\\n');\n" +
               "                    let agentInfo = '';\n" +
               "                    for (let line of lines) {\n" +
               "                        if (line.includes('Agent ID:')) {\n" +
               "                            agentInfo += line.replace('Agent ID:', 'ID:') + ' ';\n" +
               "                        }\n" +
               "                        if (line.includes('狀態:')) {\n" +
               "                            agentInfo += line + ' ';\n" +
               "                        }\n" +
               "                    }\n" +
               "                    statusElement.textContent = agentInfo || '已登入';\n" +
               "                    statusElement.style.color = '#1abc9c';\n" +
               "                }\n" +
               "            } catch (error) {\n" +
               "                console.error('更新 Agent 狀態失敗:', error);\n" +
               "            }\n" +
               "        }\n" +
               "        \n" +
               "        // 修改 startAutoRefresh 函數，加入 Agent 狀態更新\n" +
               "        function startAutoRefresh() {\n" +
               "            updateDisplay();\n" +
               "            updateAgentStatus();\n" +
               "            refreshInterval = setInterval(() => {\n" +
               "                updateDisplay();\n" +
               "                updateAgentStatus();\n" +
               "            }, 3000); // 改為 3 秒更新一次\n" +
               "        }\n" +
               "\n" +
               "        // 新增鍵盤快捷鍵\n" +
               "        document.addEventListener('keydown', function(event) {\n" +
               "            if (document.getElementById('phoneInterface').classList.contains('active')) {\n" +
               "                switch(event.key) {\n" +
               "                    case 'Enter':\n" +
               "                        event.preventDefault();\n" +
               "                        makeCall();\n" +
               "                        break;\n" +
               "                    case 'Escape':\n" +
               "                        event.preventDefault();\n" +
               "                        hangupCall();\n" +
               "                        break;\n" +
               "                    case ' ':\n" +
               "                        event.preventDefault();\n" +
               "                        answerCall();\n" +
               "                        break;\n" +
               "                    case 'h':\n" +
               "                    case 'H':\n" +
               "                        if (event.ctrlKey) {\n" +
               "                            event.preventDefault();\n" +
               "                            toggleHold();\n" +
               "                        }\n" +
               "                        break;\n" +
               "                }\n" +
               "            }\n" +
               "        });\n" +
               "\n" +
               "        document.addEventListener('DOMContentLoaded', function() {\n" +
               "            console.log('Avaya 話機模擬器已載入');\n" +
               "            document.getElementById('extensionInput').focus();\n" +
               "        });\n" +
               "\n" +
               "        window.addEventListener('beforeunload', function() {\n" +
               "            if (refreshInterval) {\n" +
               "                clearInterval(refreshInterval);\n" +
               "            }\n" +
               "        });\n" +
               "    </script>\n" +
               "</body>\n" +
"</html>";
    }

    /**
     * 首頁重定向到話機介面
     */
    @GetMapping("/")
    public String home() {
        return "redirect:/phone";
    }
}