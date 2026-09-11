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

- **標記單字＋單字本**：長按框跳出的對話框裡可以「標記這個單字」，標記
  存在本機（`SharedPreferences` 存一份 JSON，特意不用 Room，避免多裝一個
  KSP annotation processor 增加建置風險），跨 App 重開也還在；上方控制列
  的「單字本」按鈕可以看目前標記過哪些單字、逐一移除。同一個對話框裡也有
  「查字典」按鈕，會開瀏覽器查 Google 的字義搜尋，語言不限
- **上方按鈕點擊問題**：`targetSdk` 36 預設 edge-to-edge，原本按鈕列沒有
  處理狀態列的間距，會被狀態列蓋住一部分導致點不準；已加
  `statusBarsPadding()` 修正，另外也加了橫向捲動，避免窄螢幕上三個按鈕
  擠在一起點錯

還沒做：
- **物件位置對照名稱的持久化**：目前的自訂名稱／翻譯是綁在單一次辨識結果
  上，還沒有跟「標記單字」共用同一份儲存（也就是重新命名跟標記是兩套
  邏輯，還沒整合成同一份單字資料庫）
- **改語言後不會回溯**：切換翻譯語言只影響之後新辨識的物件，已經辨識過
  的物件不會自動用新語言重問一次（刻意簡化，避免每次調設定就重打模型）
- **多次觀測取共識**：目前單次辨識就定案，還沒做多角度投票
- **固定 debug 簽章**：`keystore/debug.keystore` 是固定存進 repo 的 debug
  keystore，`app/build.gradle.kts` 的 `signingConfigs.debug` 指到這把，本機和
  CI 建置都用同一把簽。不這樣做的話，CI runner 每次都是全新機器，會自動生一把
  新的 debug key，裝置上新版跟舊版簽章對不上，Android 會拒絕覆蓋安裝，只能先
  解除安裝再裝新版
- **更新檢查與安裝**：開 App 時會靜靜查一次 GitHub 最新 Release（比對
  tag_name 裡的 build number 跟目前 `versionCode`），有新版會跳對話框，
  按「下載並安裝」直接在 App 內用 `DownloadManager` 下載 APK，下載完自動跳
  系統安裝畫面，不用自己開瀏覽器、找下載資料夾、手動點開檔案。Android 8+
  第一次會需要跳系統設定頁允許「安裝未知應用程式」，允許後會自動接著下載
- **App 內下載進度條**：畫面下方會顯示下載進度（輪詢 `DownloadManager` 的
  已下載/總大小算百分比），系統通知列的進度也還在，兩邊都看得到
- **點擊互動分成兩種**：短按框會用 TTS 把目前顯示的文字唸出來（原文固定
  用繁中發音，翻譯文字依「翻譯語言」設定猜對應語系），長按框才會跳出
  重新命名的對話框，兩個功能不會互相干擾
- **App icon**：`res/drawable/ic_launcher_background.xml`（深藍底）＋
  `ic_launcher_foreground.xml`（白色取景框角括號＋綠色菱形標記，綠色跟
  CameraScreen 裡「已命名」狀態的框線同一個顏色）組成 Adaptive Icon，另外
  有 `ic_launcher_monochrome.xml` 給 Android 13+ 的主題化圖示用。因為
  `minSdk` 本來就是 26（Adaptive Icon 剛好也是 API 26 才有的機制），不需要
  再另外準備舊版密度分層的 PNG 圖示
- **VR cardboard 模式**：上方「VR 模式」按鈕會把畫面切成左右兩半（塞進
  cardboard 這類 VR 眼鏡用），互動方式從觸控改成「準星停留在框裡一段時間
  （1.5 秒）＝選取」，觸發後會唸出目前顯示的文字。這是刻意選擇凝視／停留
  而不是手指觸控：手機真的放進 cardboard viewer 之後是摸不到螢幕的，這也
  是當年 Google Cardboard SDK 用「凝視＋停留」取代觸控的原因；如果實際上
  不打算把手機放進真的 viewer，只是想要分割畫面效果，這裡可以再調整成手指
  觸控停留。VR 模式下畫面更新用的是每次分析節流後的靜態影格（大約每秒
  6-7 張，不是流暢的即時視訊），换來的是完全不影響一般模式的效能／發熱表現
  ——只有切到 VR 模式才會多做「整影格轉成 Bitmap」這筆運算

## 已知問題修正紀錄

- **框歪掉**：ML Kit 回傳的 boundingBox 是「旋轉後」座標系，但一開始拿來
  換算的來源影像寬高用的是旋轉前的 `imageProxy.width/height`，手機直立時
  （rotation 90/270）寬高剛好對調，導致框整個偏移。已在 `ObjectAnalyzer`
  裡依 rotation 交換寬高修正。

## 效能與發熱

手機發燙主要是因為 CameraX 原生 fps（常見 30fps）下持續全速跑 ML Kit 推論，
物件偵測這種用途根本不需要跟到這個頻率。已經做兩個調整：
- `ObjectAnalyzer` 加了節流，兩次偵測間至少間隔 150ms（約每秒 6-7 次），
  跳過的影格直接關閉不處理，相機預覽本身不受影響
- `ImageAnalysis` 的分析解析度用 `ResolutionSelector` 降到 640x480 等級，
  不需要跟預覽一樣高解析度，每次推論運算量小很多

這兩個都只是把「跑模型」這件事降頻降解析度，沒有改邏輯，畫面體感應該不會
差太多，但確實沒有實機量過溫度數字，如果還是燙可能要進一步拉長節流間隔。

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
