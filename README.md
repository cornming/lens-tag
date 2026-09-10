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

## 目前狀態

已經動的部分：
- CameraX 預覽 + ML Kit Object Detection & Tracking，拿到框位置和追蹤 ID
- 分析影像座標 -> 螢幕座標換算（FILL_CENTER 等比放大置中），框會對齊預覽畫面
- 物件穩定度判斷：框連續 5 影格位移都很小才觸發辨識
- 從當前影格依偵測框裁切 Bitmap，丟給 Gemini Nano 辨識
- 標籤三種狀態：Unknown（灰、顯示「?」）、Recognizing（黃、顯示「…」）、Named（綠、顯示名稱）
- 點擊任一個框可以手動命名
- 框消失後保留 500ms 寬限期，避免遮擋造成標籤閃爍

- **雙語標籤**：辨識時同時問 Gemini Nano 要繁中原文和翻譯名稱（格式
  `原文|翻譯`），畫面上方有兩個按鈕可以切換顯示模式（原文／翻譯／雙語）
  和翻譯目標語言（自由輸入語言名稱，不限英文）
- **文字防溢出**：標籤文字用 `textMeasurer` 實際量過寬高，水平會夾在
  螢幕範圍內，框上方放不下就改畫在框內側，不會再被擠出畫面

還沒做：
- **本地儲存**：自訂名稱和翻譯目前只存在記憶體，關掉 App 就沒了。要接
  Room，存「辨識文字 -> 自訂顯示名稱 -> 翻譯」的對照表
- **改語言後不會回溯**：切換翻譯語言只影響之後新辨識的物件，已經辨識過
  的物件不會自動用新語言重問一次（刻意簡化，避免每次調設定就重打模型）
- **多次觀測取共識**：目前單次辨識就定案，還沒做多角度投票
- **固定 debug 簽章**：`keystore/debug.keystore` 是固定存進 repo 的 debug
  keystore，`app/build.gradle.kts` 的 `signingConfigs.debug` 指到這把，本機和
  CI 建置都用同一把簽。不這樣做的話，CI runner 每次都是全新機器，會自動生一把
  新的 debug key，裝置上新版跟舊版簽章對不上，Android 會拒絕覆蓋安裝，只能先
  解除安裝再裝新版
- **更新檢查**：開 App 時會靜靜查一次 GitHub 最新 Release（比對 tag_name 裡的
  build number 跟目前 `versionCode`），有新版會跳對話框，按「前往下載」會開
  瀏覽器連到 APK 下載連結；目前是開瀏覽器下載後要手動安裝，還沒做 App 內直接
  下載安裝那一段（需要額外處理 `REQUEST_INSTALL_PACKAGES` 權限跟 FileProvider）
- 沒有 app icon

## 已知問題修正紀錄

- **框歪掉**：ML Kit 回傳的 boundingBox 是「旋轉後」座標系，但一開始拿來
  換算的來源影像寬高用的是旋轉前的 `imageProxy.width/height`，手機直立時
  （rotation 90/270）寬高剛好對調，導致框整個偏移。已在 `ObjectAnalyzer`
  裡依 rotation 交換寬高修正。

## 設計原則（借自 Tesla Vision）

Tesla 從純相機系統學到的教訓是：傳統物件偵測只認得訓練資料裡的類別，
看到不在資料集裡的東西就等於什麼都沒看到（他們稱為 ontology cracks），
所以解法是「幾何優先於分類」——先確認那裡有東西，再問那是什麼。
這個專案套用了其中三點：

1. **框先於名稱**：`ObjectAnalyzer` 只負責「有沒有東西、在哪裡」，完全不管類別。
   `LabelState.Unknown` 是合法狀態，辨識失敗或裝置不支援 Gemini Nano 時，
   框照樣畫、可以手動命名，功能不會整個掛掉。
2. **靜止才辨識**：借用 Tesla 區分靜止/移動物件的概念，用框中心的位移量判斷
   物件是否穩定，穩定才觸發辨識。同時解決裁切品質、AICore quota 節流、
   以及「辨識到一半物件跑掉」三個問題。
3. **時間上的寬限期**：Tesla 融合 t-1、t-2 的資料處理遮擋；這裡的簡化版是
   框消失後保留 500ms 再移除，避免標籤閃爍。

## 自動建置與發版

`.github/workflows/release.yml`：每次 push 到 `main` 會自動觸發，用
`github.run_number` 當版號（`versionCode` = run number，`versionName` =
`0.1.<run number>`），組出 debug APK，然後建立一個 GitHub Release，
附上 APK，並用 `generate_release_notes: true` 自動把這次 push 之間的
commit 訊息整理成異動內容。也就是說：

- 想看目前建置成功了沒、有沒有 APK → 看 repo 的 **Actions** 頁籤
- 想抓 APK、看版本間差異 → 看 repo 的 **Releases** 頁籤

目前是組 debug APK（用 debug key 自動簽署），還沒有設定正式簽署金鑰；
之後如果要發布到 Play Store 或想要 release 簽署版，需要另外準備
keystore 並存進 repo 的 Actions secrets。

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
