-- 為 Agent 2420 添加對分機 1423 的監聽權限
INSERT INTO MonitorPermissions (supervisor_id, target_extension) 
VALUES ('2420', '1423');

-- 驗證權限是否添加成功
SELECT * FROM MonitorPermissions WHERE supervisor_id = '2420';