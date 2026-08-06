#!/system/bin/sh
echo "=== modules_state ==="
sqlite3 /data/adb/lspd/config/modules_config.db "SELECT * FROM modules_state WHERE module_pkg_name='com.clydeenke.mediapill';"
echo "=== all modules_state ==="
sqlite3 /data/adb/lspd/config/modules_config.db "SELECT * FROM modules_state;"
