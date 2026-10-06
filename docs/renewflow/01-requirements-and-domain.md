# 需求與領域分析

狀態：規劃中 → T01／T02 部分已實作待驗證（見 `docs/learning/`）
來源：`RenewFlow-v2-Java-EIP-Learning-Guide.md`

## 1. 事項分級

| 類別 | 內容 |
|---|---|
| **已確定**（來自使用者／指南） | 後端用 Java；本機可運行、單一組織；角色為員工／主管／管理員；Session＋HttpOnly Cookie＋CSRF；PostgreSQL、Flyway、Spring Security、JPA、Jakarta Validation；前端 React＋TypeScript；採 RenewFlow v2 流程；本輪只做 T01、T02 |
| **提案假設**（尚未獲公司確認） | 見 `02-assumptions.md` |
| **待確認規則** | 跨日／輪班、補登、請假單位、工時上限、送出後可否修改、主管自己申請的審核人（皆屬 T03 之後，本輪不實作） |

## 2. 角色

| 角色 | 本輪能做的事 | 後續階段 |
|---|---|---|
| EMPLOYEE（員工） | 登入、登出、看自己的基本資料與首頁框架 | 打卡、工時、請假 |
| MANAGER（主管） | 同上 | 審核指定下屬的請假 |
| ADMIN（管理員） | 同上 | 必要設定（員工／專案／審核人）；第一版不能任意改審核結果 |

角色只回答「能做哪一類操作」；資料範圍（能操作誰的資料）在後續各功能以登入身份＋審核關係判斷。本輪唯一的資料存取是「讀自己的資料」，其員工 ID 取自 Session，不接受前端傳入。

## 3. 本輪使用案例

| 編號 | 使用案例 | 主要角色 | 正常結果 | 異常結果 |
|---|---|---|---|---|
| UC-01 | 登入 | 所有角色 | 建立 Session，回傳目前使用者 | 帳密錯誤／帳號停用 → 401；缺 CSRF → 403 |
| UC-02 | 取得目前使用者 | 已登入者 | 回傳 id、帳號、姓名、角色 | 未登入／Session 失效 → 401 |
| UC-03 | 登出 | 已登入者 | Session 失效、Cookie 清除 | 缺 CSRF → 403 |
| UC-04 | 檢視首頁框架 | 已登入者 | 看到側邊選單、使用者資訊、尚未啟用的功能卡片 | 未登入 → 導向登入頁 |

## 4. 領域名詞

- **Employee（員工）**：系統中可登入的人，具唯一帳號（username）、員工編號（employee_no）、顯示名稱、角色、啟用狀態。
- **Role（角色）**：EMPLOYEE／MANAGER／ADMIN 三選一。本輪用單一欄位表達；若日後出現「一人多角色」才拆表。
- **Session**：伺服器記錄「這個瀏覽器已登入為誰」，瀏覽器只保存無意義的 JSESSIONID。
- **CSRF Token**：證明寫入請求來自本站頁面，而不是別的網站借用瀏覽器 Cookie 發出的。

## 5. 不變條件（本輪）

1. username 全小寫且唯一；employee_no 唯一。
2. 密碼只以雜湊保存，任何 API 回應都不含雜湊。
3. 停用（enabled=false）的員工不能登入；已登入者的既有 Session 在**下一次 `/me` 檢查時**才被發現並作廢（不是即時中斷）。其他受保護 API 的逐請求檢查留待 T03 設計（見 `06-security-design.md`）。
4. 密碼以 UTF-8 位元組計不得超過 72（bcrypt 限制），超過在認證前以 400 拒絕。
5. 未登入不得取得任何受保護資料（除登入端點外，`/api/**` 全部需要登入）。

> T05 前置（部門、員工管理、直屬主管）的需求與領域見 [`t05-foundation/01-requirements-and-domain.md`](t05-foundation/01-requirements-and-domain.md)；T05 請假見 [`t05/01-requirements-and-domain.md`](t05/01-requirements-and-domain.md)。

## 6. 本輪不做

打卡、工時、請假、專案、部門、審核關係、員工管理畫面、忘記密碼／改密碼、登入失敗鎖定、稽核紀錄。首頁對應區塊僅顯示「尚未啟用」。
