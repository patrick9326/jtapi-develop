(function() {
    // --- 可自訂設定 ---
    const config = {
        phoneAppUrl: '/avaya-phone.html', // 電話 App 的路徑
        loginWidget: { // 階段一：登入小插件的設定
            position: 'bottom-right', // 可選 'bottom-right', 'bottom-left', 'top-right', 'top-left'
            width: '420px',
            height: '650px'
        }
    };
    // ------------------

    // 避免重複載入
    if (document.getElementById('avaya-widget-container')) {
        return;
    }

    let phoneAppIframe = null; // 用來儲存 iframe 的引用

    // 階段一：建立並顯示登入插件
    function createLoginWidget() {
        console.log('[Loader] 建立登入插件...');
        
        const widgetContainer = document.createElement('div');
        widgetContainer.id = 'avaya-widget-container';
        
        phoneAppIframe = document.createElement('iframe');
        phoneAppIframe.id = 'avaya-phone-iframe';
        phoneAppIframe.src = config.phoneAppUrl;
        
        // 監聽 iframe 載入完成
        phoneAppIframe.onload = function() {
            console.log('[Loader] iframe 載入完成');
        };
        
        phoneAppIframe.onerror = function() {
            console.error('[Loader] iframe 載入失敗');
        };

        widgetContainer.appendChild(phoneAppIframe);
        document.body.appendChild(widgetContainer);
        
        injectStyles();
        
        console.log('[Loader] 登入插件已建立');
    }
    
    // 階段二：轉換為頂部工具列
    async function transformToTopBar() {
        const widgetContainer = document.getElementById('avaya-widget-container');
        if (widgetContainer) {
            // 1. 平滑地隱藏登入插件
            widgetContainer.style.opacity = '0';
            widgetContainer.style.pointerEvents = 'none';
        }

        try {
            // 2. 再次獲取 avaya-phone.html 的原始碼來提取 control-bar
            const response = await fetch(config.phoneAppUrl);
            const htmlText = await response.text();
            const parser = new DOMParser();
            const doc = parser.parseFromString(htmlText, 'text/html');

            const controlBarElement = doc.querySelector('.control-bar');
            if (controlBarElement) {
                const topBar = document.createElement('div');
                topBar.id = 'avaya-top-bar';
                topBar.appendChild(controlBarElement);
                document.body.appendChild(topBar);
                
                // 3. 重新綁定頂部工具列按鈕的事件
                setupTopBarListeners();
            }
        } catch (error) {
            console.error('轉換為頂部工具列失敗:', error);
        }
    }

    // 綁定頂部工具列按鈕的監聽器
    function setupTopBarListeners() {
        const topBar = document.getElementById('avaya-top-bar');
        if (!topBar) return;

        const buttons = topBar.querySelectorAll('.control-btn, .logout-btn');
        
        buttons.forEach(button => {
            const originalOnclick = button.getAttribute('onclick');
            if (originalOnclick) {
                button.removeAttribute('onclick'); // 移除原始事件
                button.addEventListener('click', () => {
                    console.log('[Loader] 按鈕被點擊:', originalOnclick);
                    // 直接在 iframe 中執行函數
                    if (phoneAppIframe && phoneAppIframe.contentWindow) {
                        try {
                            // 使用 eval 在 iframe 中執行函數
                            phoneAppIframe.contentWindow.eval(originalOnclick);
                        } catch (error) {
                            console.error('[Loader] 執行函數失敗:', error);
                        }
                    }
                });
            }
        });
    }

    // 監聽來自 iframe 的訊息
    window.addEventListener('message', function(event) {
        console.log('[Loader] 收到訊息:', event.data, '來源:', event.origin);
        
        // 確保訊息來自我們的 iframe（修正檢查邏輯）
        if (phoneAppIframe && event.source !== phoneAppIframe.contentWindow) {
            console.log('[Loader] 訊息來源不匹配，忽略');
            return;
        }

        const data = event.data;
        if (data && data.event === 'loginSuccess') {
            console.log('[Loader] 收到登入成功訊息，開始轉換介面...');
            transformToTopBar();
        } else if (data && data.event === 'logout') {
            console.log('[Loader] 收到登出訊息，重置介面...');
            resetToLoginWidget();
        }
    });

    // 注入所有需要的 CSS 樣式
    function injectStyles() {
        const styleSheet = document.createElement('style');
        styleSheet.textContent = `
            /* 階段一：登入插件的樣式 */
            #avaya-widget-container {
                position: fixed;
                z-index: 9998;
                width: ${config.loginWidget.width};
                height: ${config.loginWidget.height};
                ${getPositionCss(config.loginWidget.position)}
                box-shadow: 0 5px 20px rgba(0,0,0,0.25);
                border-radius: 10px;
                overflow: hidden;
                transition: opacity 0.5s ease-in-out;
                opacity: 1;
                pointer-events: auto;
            }
            #avaya-phone-iframe {
                width: 100%;
                height: 100%;
                border: none;
                background: white;
            }

            /* 階段二：頂部工具列的樣式 */
            body {
                /* 預留空間給頂部工具列，使用 transition 讓下推過程更平滑 */
                transition: padding-top 0.5s ease-in-out;
            }
            #avaya-top-bar {
                position: fixed;
                top: 0;
                left: 0;
                width: 100%;
                height: 80px;
                z-index: 9999;
                display: flex;
                justify-content: center;
                align-items: center;
                background: #f8f9fa; /* 給一個背景色以防透明 */
            }
        `;
        document.head.appendChild(styleSheet);

        // 當頂部工具列被建立時，才真的把主頁面往下推
        const observer = new MutationObserver(mutations => {
            if (document.getElementById('avaya-top-bar')) {
                document.body.style.paddingTop = '80px';
                observer.disconnect(); // 完成任務後停止觀察
            }
        });
        observer.observe(document.body, { childList: true });
    }

    // 根據設定計算位置的 CSS
    function getPositionCss(position) {
        const margin = '20px';
        switch(position) {
            case 'bottom-left': return `bottom: ${margin}; left: ${margin};`;
            case 'top-right': return `top: ${margin}; right: ${margin};`;
            case 'top-left': return `top: ${margin}; left: ${margin};`;
            default: return `bottom: ${margin}; right: ${margin};`; // 預設右下角
        }
    }

    // 重置為登入插件狀態
    function resetToLoginWidget() {
        // 移除頂部工具列
        const topBar = document.getElementById('avaya-top-bar');
        if (topBar) {
            topBar.remove();
        }
        
        // 重置 body 的 padding
        document.body.style.paddingTop = '0';
        
        // 顯示登入插件
        const widgetContainer = document.getElementById('avaya-widget-container');
        if (widgetContainer) {
            widgetContainer.style.opacity = '1';
            widgetContainer.style.pointerEvents = 'auto';
        }
    }

    // 啟動插件
    createLoginWidget();

})();