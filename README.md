<h1 align="center">Az Meteor Client</h1>
<p align="center">基於 <a href="https://github.com/MeteorDevelopment/meteor-client">Meteor Client</a> 的 Minecraft 26.2 Fabric 工具模組分支,內建繁體中文與可自訂的語言包系統。<br>
A fork of Meteor Client for Minecraft 26.2 (Fabric) with a language pack system, a Dynamic Island and a music player.</p>

<p align="center">
<b>維護者 / Maintainer:</b> <a href="https://github.com/arno721">arno721</a>
</p>

## 特色

### 語言包系統
- 所有文字(模組、設定、分組、指令、介面)集中在**單一 JSON 檔**,內建完整的**繁體中文**。
- 在 `Config` 分頁的「語言包」區可以**匯出**目前語言或英文範本,翻譯成任何語言後再**匯入**。匯入的語言包放在 `meteor-client/languages/`。
- 語言設定選 `auto` 會跟隨 Minecraft 語言。需要 CJK 等字元的語言會自動改用原版字型。
- 檔案格式見 [`LanguageManager`](src/main/java/meteordevelopment/meteorclient/utils/i18n/LanguageManager.java) 的說明,以及匯出檔內的 `_meta.help`。

### 動態島(Dynamic Island)
- 畫面頂端會變形的膠囊,**三個膠囊**:主島在中間,左右各一個小膠囊,像 iPhone 一樣黏稠地分裂與融合。
- 形狀由距離場著色器逐像素繪製,邊緣、光暈與陰影都抗鋸齒;著色器不能用時自動改用三角形備援繪製。
- 與很多功能聯動(每項都能單獨關閉):
  - 戰鬥:低血量、殺戮光環/長矛光環/水晶光環/重生錨光環/床光環/弓箭自瞄的目標、圖騰觸發。
  - 移動與尋路:鞘翅導航、Baritone 尋路、自由視角、封包暫存、變速。
  - 實用:進食進度、音符盒、耐久度不足、伺服器沒有回應。
  - 事件:模組開關(大量切換會合併)、模組警告與錯誤、位置被校正、切換維度、死亡位置、聊天提及。
- **自訂待機頁面**:用 Starscript 自由排列要輪流顯示的內容,例如 `[HEART#FF5555] {player} | {player.health} HP`。
- 其他模組可以實作 `IslandSource` 加入動態島,一次性通知用 `DynamicIsland.show(...)`。

### 音樂播放器(僅限 Windows)
- 讀取 Windows 媒體控制(Spotify、瀏覽器、Windows 媒體播放器等):歌名、歌手、專輯、封面、進度。
- 同步歌詞:從 [LRCLIB](https://lrclib.net) 取得(可關閉),也可以放自己的 `.lrc` 到 `meteor-client/music/lyrics`。
- 四種畫面樣式、可自由排列的文字行、卡拉 OK 逐字填色、封面取色、按鍵控制,以及 `.music` 指令。
- 可在動態島上顯示,並提供 `{music.title}`、`{music.lyric}` 等 Starscript 變數。

### 其他
- **鞘翅導航**:自動升空、規劃路線、用煙火加速,並估算煙火與耐久,抵達時可發出 Windows 通知。
- **長矛光環**、殺戮光環修正、**目標標記**(多種樣式)、**實體列表** HUD(堆疊、排序、醒目標示)。

## 建置

需要 Java 25。

```
./gradlew build
```

成品在 `build/libs`。開發用客戶端:`./gradlew runClient`。

## 安裝

把 `build/libs` 裡的 jar 放進安裝了 [Fabric Loader](https://fabricmc.net) 與 Fabric API 的 Minecraft 26.2 的 `mods` 資料夾。

## 回報問題與建議

這個分支新增或修改的功能,請到[本倉庫的 Issues](https://github.com/arno721/Az-Meteor-Client/issues) 回報。原版 Meteor Client 的問題請到[上游倉庫](https://github.com/MeteorDevelopment/meteor-client/issues)。

## 貢獻

- 所有 Java 檔案都要加上授權標頭。
- IDE 或系統相關的檔案請加到 `.gitignore`,不要提交。
- 請讓程式碼風格與現有的程式碼一致,可讀性優先於精簡。參考 [Google Java 風格指南](https://google.github.io/styleguide/javaguide.html)。
- 新增模組、設定或指令時,請同時在 `src/main/resources/assets/meteor-client/languages/` 的 `en_us.json` 與 `zh_tw.json` 加上文字。

## 致謝

- [Meteor Development](https://github.com/MeteorDevelopment/meteor-client) 的 Meteor Client,本專案的基礎。
- [Cabaletta](https://github.com/cabaletta) 與 [WagYourTail](https://github.com/wagyourtail) 的 [Baritone](https://github.com/cabaletta/baritone)。
- [Fabric 團隊](https://github.com/FabricMC) 的 [Fabric](https://github.com/FabricMC/fabric-loader)。
- [LRCLIB](https://lrclib.net) 提供歌詞資料。

## 授權

本專案使用 [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html) 授權,與上游相同。

如果你使用了本專案的**任何**程式碼:
- 你必須公開你修改後的原始碼,以及取自本專案的原始碼,不得用於閉源或混淆的應用程式。
- 你必須向所有使用者清楚說明你使用了本專案的程式碼。
- 你的應用程式也必須使用相同的授權。
