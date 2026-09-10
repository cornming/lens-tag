# lens-tag

用手機相機即時框出畫面中的物件，辨識出來後可以自訂顯示名稱、翻譯成指定語言；
之後看到同類物件會直接顯示存好的名稱。

## 架構

1. **相機影像** — CameraX 擷取即時畫面
2. **物件偵測與追蹤** — ML Kit Object Detection & Tracking（STREAM_MODE），
   每影格輸出框的位置＋追蹤 ID
3. **物件辨識** — 只在畫面出現「新的追蹤 ID」時觸發，把裁切出來的物件圖片
   丟給裝置端的 Gemini Nano（ML Kit GenAI Prompt API），開放詞彙辨識出
   「這是什麼」，不用自己訓練固定類別的分類模型
4. **自訂名稱與翻譯查詢** — 辨識出的文字去查本地對照表，第一次看到的物件
   讓使用者自訂顯示名稱／語言，之後同類物件直接查表顯示
5. **AR 疊加顯示** — Compose Canvas 疊在相機預覽上畫框＋文字

## 目前狀態（第一版骨架）

已經動的部分：
- CameraX 預覽 + ML Kit Object Detection & Tracking，能拿到框位置和追蹤 ID
- `ObjectRecognizer`：接上 ML Kit GenAI Prompt API 的 `checkStatus()` /
  `download()` / `generateContent()` 標準流程
- `CameraScreen` 裡已經有「新追蹤 ID 才觸發辨識」的快取骨架

還沒做、下一步要接的：
- **裁切 Bitmap**：目前辨識觸發點還是空的，要從 `ImageProxy` 依偵測框裁出
  該物件的 Bitmap 再丟給 `ObjectRecognizer.recognize()`（建議接在
  `ObjectAnalyzer` 那層，原始影格還在那裡）
- **座標換算**：Canvas 疊加框目前直接用 ML Kit 回傳的分析影像座標畫，還沒
  對齊 `PreviewView` 實際顯示的縮放/裁切，框的位置在大多數機型上會偏移
- **自訂名稱／翻譯的本地儲存**：目前辨識出文字後還沒有查表/存表的邏輯，
  也還沒有讓使用者輸入自訂名稱的畫面
- 沒有 app icon、沒有設定 CI

## 重要限制

- ML Kit GenAI Prompt API（Gemini Nano）目前只支援搭載 AICore 的機型
  （Google Tensor、部分 Qualcomm Snapdragon、部分 MediaTek Dimensity），
  而且**不支援解鎖過 bootloader 的裝置**。實機測試前務必先確認你的測試機
  在支援範圍內，或跑一次 `ObjectRecognizer.ensureReady()` 實測。
- `minSdk` 設成 26，這是 GenAI Prompt API 的最低要求。
- `gradle/libs.versions.toml` 裡有幾個版本號（core-ktx、lifecycle-runtime-ktx、
  activity-compose）沒有實際查證，只是合理預設值；開 Android Studio 同步時
  讓它建議更新即可。

## 建置

本機沙箱環境連不到 Google Maven，所以這個專案還沒有實際編譯驗證過，
請在 Android Studio 開啟後 sync 一次確認。
